#!/usr/bin/env bash
# Install reactor dependencies (needed so the second Maven invocation can resolve
# SNAPSHOT siblings from ~/.m2 instead of GitHub Packages), then test one module.
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "usage: $0 <module> [maven-args...]" >&2
  echo "example: $0 server" >&2
  echo "example: $0 connectors/spark -DsparkVersion=4.1" >&2
  exit 1
fi

MODULE="$1"
shift

mvn -pl "$MODULE" -am install -DskipTests "$@"
mvn -pl "$MODULE" test "$@"
