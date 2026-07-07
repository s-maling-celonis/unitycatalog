#!/usr/bin/env bash
# Shared helpers for docker/tests JUnit integration suite (celospark stack).
set -euo pipefail

_uc_lib_dir() {
  if [[ -n "${BASH_SOURCE:-}" ]]; then
    cd "$(dirname "${BASH_SOURCE[0]}")" && pwd
  else
    cd "$(dirname "$0")" && pwd
  fi
}

UC_JAVA_ROOT="${UC_JAVA_ROOT:-$(cd "$(_uc_lib_dir)/../.." && pwd)}"
UC_TESTS_POM="$UC_JAVA_ROOT/docker/tests/pom.xml"
UC_VERSION="0.5.0-SNAPSHOT"
UC_CLIENT_JAR="$UC_JAVA_ROOT/clients/java/target/unitycatalog-client-${UC_VERSION}.jar"
UC_CONTROL_API_JAR="$UC_JAVA_ROOT/target/control/java/target/unitycatalog-controlapi-${UC_VERSION}.jar"
UC_CONTROL_MODELS_JAR="$UC_JAVA_ROOT/server/target/controlmodels/target/unitycatalog-controlmodels-${UC_VERSION}.jar"

run_with_timeout() {
  local secs="$1"
  shift
  if command -v gtimeout >/dev/null 2>&1; then
    gtimeout --signal=TERM --kill-after=10 "$secs" "$@"
    return $?
  fi
  if command -v timeout >/dev/null 2>&1; then
    timeout --signal=TERM --kill-after=10 "$secs" "$@"
    return $?
  fi

  "$@" &
  local pid=$!
  local watcher
  (
    sleep "$secs"
    if kill -0 "$pid" 2>/dev/null; then
      echo "==> TIMEOUT after ${secs}s — stopping test run (pid $pid)" >&2
      pkill -TERM -P "$pid" 2>/dev/null || true
      kill -TERM "$pid" 2>/dev/null || true
      sleep 3
      pkill -KILL -P "$pid" 2>/dev/null || true
      kill -KILL "$pid" 2>/dev/null || true
    fi
  ) &
  watcher=$!
  set +e
  wait "$pid"
  local rc=$?
  set -e
  kill "$watcher" 2>/dev/null || true
  wait "$watcher" 2>/dev/null || true
  if kill -0 "$pid" 2>/dev/null; then
    return 124
  fi
  return "$rc"
}

_uc_java_install_local_jar() {
  local file="$1" artifact="$2"
  mvn -q install:install-file \
    -Dfile="$file" \
    -DgroupId=io.unitycatalog \
    -DartifactId="$artifact" \
    -Dversion="$UC_VERSION" \
    -Dpackaging=jar
}

resolve_celospark_docker_dir() {
  if [[ -n "${CELOSPARK_DOCKER_DIR:-}" ]]; then
    echo "$(cd "$CELOSPARK_DOCKER_DIR" && pwd)"
    return
  fi
  local sibling="$UC_JAVA_ROOT/../celospark/docker"
  if [[ -d "$sibling" ]]; then
    echo "$(cd "$sibling" && pwd)"
    return
  fi
  echo "$sibling"
}

source_celospark_oauth_env() {
  local celospark_dir
  celospark_dir="$(resolve_celospark_docker_dir)"
  if [[ -f "$celospark_dir/uc/oidc/resolve-oauth-tenant.sh" ]]; then
    # shellcheck source=/dev/null
    source "$celospark_dir/uc/oidc/resolve-oauth-tenant.sh"
  else
    : "${UC_OAUTH_TEAM:=dev}"
    : "${UC_OAUTH_REALM:=dev.celonis.cloud}"
    UC_OAUTH_HOST="${UC_OAUTH_TEAM}.${UC_OAUTH_REALM}"
  fi
  : "${UC_OAUTH_CONNECT_URL:=http://127.0.0.1:9010}"
}

resolve_oauth_connect_url() {
  if [[ -n "${UC_OAUTH_CONNECT_URL:-}" ]]; then
    echo "$UC_OAUTH_CONNECT_URL"
    return
  fi
  source_celospark_oauth_env
  echo "$UC_OAUTH_CONNECT_URL"
}

resolve_admin_token() {
  if [[ -n "${UC_ADMIN_TOKEN:-}" ]]; then
    echo "$UC_ADMIN_TOKEN"
    return
  fi
  local metastore_token
  metastore_token="$(
    docker exec unitycatalog cat /home/unitycatalog/etc/conf/token.txt 2>/dev/null | tr -d '[:space:]'
  )" || true
  if [[ -n "$metastore_token" ]]; then
    echo "$metastore_token"
    return
  fi
  if [[ -f "$UC_JAVA_ROOT/etc/conf/token.txt" ]]; then
    tr -d '[:space:]' <"$UC_JAVA_ROOT/etc/conf/token.txt"
    return
  fi
  source_celospark_oauth_env
  local oauth_connect_url oauth_host
  oauth_connect_url="$(resolve_oauth_connect_url)"
  oauth_host="${UC_OAUTH_HOST:-dev.dev.celonis.cloud}"
  local token
  token="$(curl -sf "${oauth_connect_url}/oauth2/token" \
    -H "Host: ${oauth_host}" \
    -u unity-catalog-local:unity-catalog-local-secret \
    -d grant_type=client_credentials \
    -d scope=openid \
    | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")" || true
  if [[ -n "$token" && "$token" != "null" ]]; then
    echo "$token"
    return
  fi
  echo "ERROR: set UC_ADMIN_TOKEN or ensure celospark OAuth is running" >&2
  exit 1
}

ensure_celospark_stack() {
  if [[ "${DOCKER_TESTS_ENSURE_STACK:-1}" == "0" ]]; then
    return
  fi

  local url="${UC_SERVER_URL:-http://localhost:8181}"
  local code
  code="$(curl -s -o /dev/null -w "%{http_code}" "${url}/api/2.1/unity-catalog/catalogs" || echo "000")"
  if [[ "$code" == "401" || "$code" == "200" ]]; then
    echo "==> UC API reachable at ${url} (HTTP ${code})" >&2
    return
  fi

  local celospark_dir
  celospark_dir="$(resolve_celospark_docker_dir)"
  echo "ERROR: Celospark UC stack not reachable at ${url} (HTTP ${code})" >&2
  echo "Start the stack from celospark/docker:" >&2
  echo "  cd ${celospark_dir}" >&2
  echo "  cp example.env .env   # set GITHUB_TOKEN, UC_SERVER_IMAGE*, etc." >&2
  echo "  docker compose -f compose.uc.yml -f uc/oidc/compose.yaml up -d --build" >&2
  exit 1
}

resolve_celospark_dotenv_var() {
  local key="$1"
  local default="${2:-}"
  if [[ -n "${!key:-}" ]]; then
    echo "${!key}"
    return
  fi
  local celospark_dir env_file line name value
  celospark_dir="$(resolve_celospark_docker_dir)"
  env_file="$celospark_dir/.env"
  if [[ -f "$env_file" ]]; then
    while IFS= read -r line || [[ -n "$line" ]]; do
      line="${line%%#*}"
      line="${line#"${line%%[![:space:]]*}"}"
      line="${line%"${line##*[![:space:]]}"}"
      [[ -z "$line" ]] && continue
      [[ "$line" != *=* ]] && continue
      name="${line%%=*}"
      value="${line#*=}"
      if [[ "$name" == "$key" ]]; then
        echo "$value"
        return
      fi
    done <"$env_file"
  fi
  echo "$default"
}

to_s3_uri() {
  local bucket="$1"
  if [[ "$bucket" == s3://* ]]; then
    echo "$bucket"
  else
    echo "s3://${bucket}"
  fi
}

docker_test_env() {
  source_celospark_oauth_env
  local celospark_bucket dp_bucket
  celospark_bucket="$(resolve_celospark_dotenv_var CELOSPARK_BUCKET_NAME celospark-bucket)"
  dp_bucket="$(resolve_celospark_dotenv_var DP_BUCKET_NAME data-pipeline-bucket)"
  echo "UC_SERVER_URL=${UC_SERVER_URL:-http://localhost:8181}"
  echo "SPARK_UC_SERVER_URI=${SPARK_UC_SERVER_URI:-http://unitycatalog:8080}"
  echo "UC_OAUTH_CONNECT_URL=$(resolve_oauth_connect_url)"
  echo "UC_OAUTH_HOST=${UC_OAUTH_HOST:-dev.dev.celonis.cloud}"
  echo "UC_ADMIN_TOKEN=$(resolve_admin_token)"
  echo "BUCKET=${BUCKET:-$(to_s3_uri "$celospark_bucket")}"
  echo "DATA_BUCKET=${DATA_BUCKET:-$(to_s3_uri "$dp_bucket")}"
}

ensure_uc_client_jars() {
  if [[ ! -f "$UC_CLIENT_JAR" || ! -f "$UC_CONTROL_API_JAR" || ! -f "$UC_CONTROL_MODELS_JAR" ]]; then
    echo "==> Building Unity Catalog Java client + control API (first run, ~1-2 min)" >&2
    build/sbt -batch "controlModels/compile; controlApi/compile; client/package" >/dev/null
  fi
  local stamp="$UC_JAVA_ROOT/docker/tests/target/.uc-client-jars-installed"
  local client_mtime
  if [[ "$(uname -s)" == "Darwin" ]]; then
    client_mtime="$(stat -f '%m' "$UC_CLIENT_JAR")"
  else
    client_mtime="$(stat -c '%Y' "$UC_CLIENT_JAR")"
  fi
  if [[ -f "$stamp" && "$(cat "$stamp")" == "$client_mtime" ]]; then
    return
  fi
  mkdir -p "$(dirname "$stamp")"
  _uc_java_install_local_jar "$UC_CLIENT_JAR" unitycatalog-client
  _uc_java_install_local_jar "$UC_CONTROL_API_JAR" unitycatalog-controlapi
  _uc_java_install_local_jar "$UC_CONTROL_MODELS_JAR" unitycatalog-controlmodels
  echo "$client_mtime" >"$stamp"
}

run_docker_tests() {
  local test_class="$1"
  shift
  local timeout_secs="${DOCKER_TESTS_TIMEOUT_SECS:-600}"
  local log="${DOCKER_TESTS_LOG:-$UC_JAVA_ROOT/docker/tests/target/last-test-run.log}"
  ensure_celospark_stack
  ensure_uc_client_jars
  mkdir -p "$(dirname "$log")"
  echo "==> Running $test_class (timeout ${timeout_secs}s, ETA ~2-4 min)" >&2
  echo "==> Live log: $log" >&2
  set -o pipefail
  run_with_timeout "$timeout_secs" env UC_REPO_ROOT="$UC_JAVA_ROOT" $(docker_test_env) \
    mvn -f "$UC_TESTS_POM" test -Dtest="$test_class" "$@" 2>&1 | tee "$log"
  return "${PIPESTATUS[0]}"
}

run_all_docker_tests() {
  local timeout_secs="${DOCKER_TESTS_TIMEOUT_SECS:-480}"
  local log="${DOCKER_TESTS_LOG:-$UC_JAVA_ROOT/docker/tests/target/last-test-run.log}"
  ensure_celospark_stack
  ensure_uc_client_jars
  mkdir -p "$(dirname "$log")"
  echo "==> Running all docker tests (hard timeout ${timeout_secs}s, ETA ~4-6 min)" >&2
  echo "==> Started at $(date '+%H:%M:%S'); will abort after ${timeout_secs}s" >&2
  echo "==> Live log: $log  (tail -f \"$log\" in another terminal)" >&2
  set -o pipefail
  run_with_timeout "$timeout_secs" env UC_REPO_ROOT="$UC_JAVA_ROOT" $(docker_test_env) \
    mvn -f "$UC_TESTS_POM" test "$@" 2>&1 | tee "$log"
  return "${PIPESTATUS[0]}"
}

bootstrap_tenant_java() {
  ensure_celospark_stack
  ensure_uc_client_jars
  local args=""
  for arg in "$@"; do
    args="$args $(printf '%q' "$arg")"
  done
  env UC_REPO_ROOT="$UC_JAVA_ROOT" $(docker_test_env) mvn -q -f "$UC_TESTS_POM" \
    org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
    -Dexec.mainClass=io.unitycatalog.docker.tests.support.BootstrapCli \
    -Dexec.args="$args"
}
