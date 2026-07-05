#!/usr/bin/env bash
# scripts/e2e.sh — root entrypoint: up → smoke → playwright → down.
#   ./scripts/e2e.sh                 full run, tears down at the end
#   ./scripts/e2e.sh --no-teardown   leave the stack up for debugging
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEARDOWN=1
[ "${1:-}" = "--no-teardown" ] && TEARDOWN=0

cleanup() { [ "$TEARDOWN" = "1" ] && "${REPO_ROOT}/scripts/e2e-down.sh" --volumes || true; }
trap cleanup EXIT

"${REPO_ROOT}/scripts/e2e-up.sh"
"${REPO_ROOT}/scripts/e2e-smoke.sh"
"${REPO_ROOT}/scripts/e2e-pipeline.sh"
