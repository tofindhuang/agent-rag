#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

source "$(dirname "$0")/stack-env.sh"
export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-agent-core-loadtest}"
if [[ -f .run/fake-embedding.pid ]]; then
  kill "$(cat .run/fake-embedding.pid)" 2>/dev/null || true
  rm -f .run/fake-embedding.pid
fi
docker compose -f docker-compose.yml down -v --remove-orphans
rm -rf .run
echo "stack removed (compose project: ${COMPOSE_PROJECT_NAME})"
