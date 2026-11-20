#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
source "$ROOT/tools/loadtest/scripts/stack-env.sh"
cd "$ROOT/gateway"
exec npm run start
