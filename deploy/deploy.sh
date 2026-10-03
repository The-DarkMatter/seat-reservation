#!/usr/bin/env bash
# Rolls the VM to a given image tag (the git sha CI built), waits for
# readiness, and rolls back to the previous tag if it doesn't come up.
#
#   /opt/seats/deploy/deploy.sh <tag> [git-ref]
set -euo pipefail

TAG="${1:?usage: deploy.sh <image-tag> [git-ref]}"
REF="${2:-$TAG}"
cd /opt/seats

compose() {
  local profiles=()
  # Ship metrics/logs to Grafana Cloud only once it's configured.
  if grep -q '^GRAFANA_CLOUD_API_KEY=glc_' .env 2>/dev/null; then
    profiles=(--profile cloud)
  fi
  docker compose -f docker-compose.yml -f deploy/docker-compose.prod.yml "${profiles[@]}" "$@"
}

wait_ready() {
  for _ in $(seq 1 90); do
    if curl -fsS -o /dev/null http://127.0.0.1:8080/health/ready; then
      return 0
    fi
    sleep 2
  done
  return 1
}

set_tag() {
  if grep -q '^APP_TAG=' .env; then
    sed -i "s/^APP_TAG=.*/APP_TAG=$1/" .env
  else
    echo "APP_TAG=$1" >> .env
  fi
}

PREVIOUS="$(grep '^APP_TAG=' .env | cut -d= -f2 || true)"

# Compose files and configs come from the same commit as the image.
if [[ "$REF" != "latest" ]]; then
  git fetch --quiet origin
  git checkout --quiet --force "$REF"
fi

set_tag "$TAG"
compose pull app
compose up -d --no-build --remove-orphans

if wait_ready; then
  echo "deployed $TAG (was ${PREVIOUS:-none})"
  docker image prune -f >/dev/null
  exit 0
fi

echo "!! $TAG did not become ready; rolling back to ${PREVIOUS:-nothing}" >&2
compose logs --tail 80 app >&2 || true
if [[ -n "$PREVIOUS" && "$PREVIOUS" != "$TAG" ]]; then
  set_tag "$PREVIOUS"
  [[ "$PREVIOUS" != "latest" ]] && git checkout --quiet --force "$PREVIOUS" || true
  compose up -d --no-build
  wait_ready && echo "rolled back to $PREVIOUS" >&2
fi
exit 1
