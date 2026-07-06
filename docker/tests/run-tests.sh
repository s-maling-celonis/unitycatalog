#!/usr/bin/env bash
# Run docker/tests JUnit integration suite against the celospark UC Docker stack.
#
# Prerequisites — start celospark/docker (sibling checkout or CELOSPARK_DOCKER_DIR):
#   cd ../celospark/docker
#   cp example.env .env
#   docker compose -f compose.uc.yml -f uc/oidc/compose.yaml up -d --build
#
# Then from this repo:
#   ./docker/tests/run-tests.sh
#   ./docker/tests/run-tests.sh io.unitycatalog.docker.tests.BootstrapTenantTest
#
# Environment:
#   CELOSPARK_DOCKER_DIR     path to celospark/docker (default: ../celospark/docker)
#   UC_SERVER_URL            default http://localhost:8181
#   UC_OAUTH_BASE_URL        default from celospark resolve-oauth-tenant.sh
#   UC_ADMIN_TOKEN           optional; otherwise client_credentials from OAuth
#   DOCKER_TESTS_ENSURE_STACK=0  skip reachability check
#   DOCKER_TESTS_KEEP=1      keep tenant resources after tests
#   DOCKER_TESTS_TIMEOUT_SECS  hard timeout for mvn test (default 480)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=ucJava.sh
source "$SCRIPT_DIR/ucJava.sh"

if [[ $# -gt 0 ]]; then
  run_docker_tests "$@"
else
  run_all_docker_tests
fi
