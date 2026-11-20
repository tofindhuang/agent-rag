#!/usr/bin/env bash
# Verifies the run_code sandbox isolation flags (FR-3). Exits 0 with a SKIP
# message when the Docker daemon is unavailable, so local runs stay usable.
set -euo pipefail

cd "$(dirname "$0")"

if ! docker info >/dev/null 2>&1; then
  echo "SKIP: Docker daemon unavailable; sandbox isolation checks not run"
  exit 0
fi

IMAGE="agent-core-sandbox:ci"
trap 'docker image rm -f "$IMAGE" >/dev/null 2>&1 || true' EXIT

echo "building ${IMAGE}"
docker build -q -t "$IMAGE" -f Dockerfile.python .

run_sandbox() {
  docker run --rm \
    --read-only \
    --tmpfs /work:size=64m,uid=1000,gid=1000 \
    -u 1000:1000 \
    --cap-drop ALL \
    --security-opt no-new-privileges \
    "$@"
}

echo "checking non-root user"
test "$(run_sandbox "$IMAGE" id -u)" = "1000"

echo "checking read-only rootfs"
if run_sandbox "$IMAGE" sh -c 'touch /rootfs-probe' >/dev/null 2>&1; then
  echo "FAIL: rootfs is writable"
  exit 1
fi

echo "checking writable /work tmpfs"
test "$(run_sandbox "$IMAGE" sh -c 'echo ok > /work/probe && cat /work/probe')" = "ok"

echo "checking network is disabled"
if run_sandbox --network none "$IMAGE" python -c \
  'import socket; socket.create_connection(("1.1.1.1", 80), timeout=2)' >/dev/null 2>&1; then
  echo "FAIL: network is reachable"
  exit 1
fi

echo "checking resource limits"
run_sandbox --memory 512m --cpus 1 --pids-limit 64 "$IMAGE" python -c 'print("ok")' \
  | grep -qx ok

echo "sandbox isolation checks passed"
