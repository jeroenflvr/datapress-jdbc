#!/usr/bin/env bash
# Start (or stop) a DataPress server in Docker for the JDBC integration tests.
#
#   scripts/run-server.sh up [datafusion|duckdb]   # start, wait for readiness
#   scripts/run-server.sh up-nosql [datafusion]     # start with the SQL endpoint disabled
#   scripts/run-server.sh down                      # stop and remove
#
# On "up" it prints the line to export, e.g.:
#   export DATAPRESS_URL=jdbc:datapress://127.0.0.1:8080/
#
# "up-nosql" runs a second server on DATAPRESS_NOSQL_PORT (default 8081) and prints
#   export DATAPRESS_NOSQL_URL=jdbc:datapress://127.0.0.1:8081/
# for the SQL-disabled integration test.
#
# Then run:  DATAPRESS_URL=… ./gradlew integrationTest
set -euo pipefail

IMAGE="${DATAPRESS_IMAGE:-jeroenflvr/datapress:latest}"
CONTAINER="${DATAPRESS_CONTAINER:-datapress-it}"
PORT="${DATAPRESS_PORT:-8080}"
NOSQL_CONTAINER="${DATAPRESS_NOSQL_CONTAINER:-datapress-it-nosql}"
NOSQL_PORT="${DATAPRESS_NOSQL_PORT:-8081}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FIXTURES="$REPO_ROOT/src/integrationTest/resources/fixtures"
CONFIG_SRC="$REPO_ROOT/src/integrationTest/resources/datasets.toml"

down() {
  docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
  docker rm -f "$NOSQL_CONTAINER" >/dev/null 2>&1 || true
}

wait_ready() {
  local port="$1" container="$2" i
  for i in $(seq 1 60); do
    if curl -fsS "http://127.0.0.1:$port/readyz" >/dev/null 2>&1; then
      return 0
    fi
    sleep 0.5
  done
  echo "server did not become ready; logs:" >&2
  docker logs "$container" >&2 || true
  return 1
}

up() {
  local backend="${1:-datafusion}"
  case "$backend" in
    datafusion | duckdb) ;;
    *)
      echo "unknown backend: $backend (use datafusion or duckdb)" >&2
      exit 2
      ;;
  esac

  docker rm -f "$CONTAINER" >/dev/null 2>&1 || true

  # Render a config with the requested backend.
  local config
  config="$(mktemp -t datapress-it-config.XXXXXX)"
  sed "s/^backend[[:space:]]*=.*/backend = \"$backend\"/" "$CONFIG_SRC" >"$config"

  docker run -d --name "$CONTAINER" \
    -p "$PORT:8080" \
    -v "$config:/etc/datapress/datasets.toml:ro" \
    -v "$FIXTURES:/fixtures:ro" \
    "$IMAGE" >/dev/null

  wait_ready "$PORT" "$CONTAINER" || {
    down
    exit 1
  }

  echo "DataPress ($backend) ready on port $PORT."
  echo "export DATAPRESS_URL=jdbc:datapress://127.0.0.1:$PORT/"
}

up_nosql() {
  local backend="${1:-datafusion}"
  docker rm -f "$NOSQL_CONTAINER" >/dev/null 2>&1 || true

  # Strip the [sql] block so POST /api/v1/sql returns 404.
  local config
  config="$(mktemp -t datapress-it-nosql.XXXXXX)"
  awk '
    /^\[sql\]/ { skip = 1; next }
    /^\[/ && $0 !~ /^\[sql\]/ { skip = 0 }
    skip { next }
    { print }
  ' "$CONFIG_SRC" | sed "s/^backend[[:space:]]*=.*/backend = \"$backend\"/" >"$config"

  docker run -d --name "$NOSQL_CONTAINER" \
    -p "$NOSQL_PORT:8080" \
    -v "$config:/etc/datapress/datasets.toml:ro" \
    -v "$FIXTURES:/fixtures:ro" \
    "$IMAGE" >/dev/null

  wait_ready "$NOSQL_PORT" "$NOSQL_CONTAINER" || {
    docker rm -f "$NOSQL_CONTAINER" >/dev/null 2>&1 || true
    exit 1
  }

  echo "DataPress ($backend, SQL disabled) ready on port $NOSQL_PORT."
  echo "export DATAPRESS_NOSQL_URL=jdbc:datapress://127.0.0.1:$NOSQL_PORT/"
}

case "${1:-up}" in
  up) up "${2:-datafusion}" ;;
  up-nosql) up_nosql "${2:-datafusion}" ;;
  down) down ;;
  *)
    echo "usage: $0 {up [datafusion|duckdb] | up-nosql [datafusion] | down}" >&2
    exit 2
    ;;
esac
