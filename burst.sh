#!/usr/bin/env bash
# One-command on-sale stampede against a running service.
#
#   ./burst.sh https://seats.amogh.cloud
#   ./burst.sh http://localhost:8080 --stampede 30000 --concurrency 2000
#
# Options (all optional): --stampede N (15000)  --hot-seats N (5)
#   --buyers-per-hot-seat N (500)  --rows N (20)  --seats-per-row N (100)
#   --concurrency N (1000 in flight)  --admin-secret S  --no-metrics-check
#
# Uses a local JDK 25+ if there is one, otherwise runs inside Docker.
# Exit code is non-zero if any correctness check fails.
set -euo pipefail

if [[ $# -lt 1 || "$1" == -* ]]; then
  sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'
  exit 2
fi
BASE_URL="$1"; shift
cd "$(dirname "$0")"

java_major() {
  java -version 2>&1 | awk -F'"' '/version/ { split($2, v, "."); print v[1]; exit }'
}

run_in_docker() {
  docker build -q -t seat-burst -f loadtest/Dockerfile . >/dev/null
  exec docker run --rm "$@"
}

# Local compose stack: talk to it over its own Docker network. That avoids
# Docker Desktop's port proxy, which can't open ~1000 connections at once.
if [[ "$BASE_URL" =~ ^https?://(localhost|127\.0\.0\.1)(:8080)?/?$ ]] && docker network inspect seats_default >/dev/null 2>&1; then
  echo "(local compose stack detected: running the burst client on its network)"
  run_in_docker --network seats_default seat-burst "http://app:8080" "$@"
fi

if command -v java >/dev/null 2>&1 && [[ "$(java_major)" -ge 25 ]]; then
  ./mvnw -q -B -f loadtest/pom.xml package
  exec java -jar loadtest/target/burst.jar "$BASE_URL" "$@"
fi

echo "(no local JDK 25: running the burst client in Docker)"
BASE_URL="${BASE_URL/localhost/host.docker.internal}"
BASE_URL="${BASE_URL/127.0.0.1/host.docker.internal}"
run_in_docker --add-host=host.docker.internal:host-gateway seat-burst "$BASE_URL" "$@"
