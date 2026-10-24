#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-agent-core-loadtest}"
mkdir -p .run

# Start from a clean slate every run. This only touches project-scoped
# containers (agent-core-loadtest) and their throwaway data.
docker compose -f docker-compose.yml down -v --remove-orphans >/dev/null 2>&1 || true
docker compose -f docker-compose.yml up -d --wait

# Fake embedding/rerank sidecar used by kb sessions (and KB ingestion).
if [[ -f .run/fake-embedding.pid ]]; then
  kill "$(cat .run/fake-embedding.pid)" 2>/dev/null || true
  rm -f .run/fake-embedding.pid
fi
nohup npx tsx src/fake-embedding.ts >.run/fake-embedding.log 2>&1 &
echo $! >.run/fake-embedding.pid
for _ in $(seq 1 100); do
  if curl -fsS "http://127.0.0.1:${LOADTEST_EMBEDDING_PORT:-18099}/health" >/dev/null 2>&1; then
    echo "stack ready (compose project: ${COMPOSE_PROJECT_NAME})"
    exit 0
  fi
  sleep 0.2
done
echo "fake embedding server did not become ready; see .run/fake-embedding.log" >&2
exit 1
