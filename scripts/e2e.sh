#!/usr/bin/env bash
# scripts/e2e.sh — full regression: unit → up → smoke → playwright → down.
#   ./scripts/e2e.sh                 full run, tears down at the end
#   ./scripts/e2e.sh --no-teardown   leave the stack up for debugging
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"
TEARDOWN=1
[ "${1:-}" = "--no-teardown" ] && TEARDOWN=0

cleanup() { [ "$TEARDOWN" = "1" ] && "${REPO_ROOT}/scripts/e2e-down.sh" --volumes || true; }
trap cleanup EXIT

# Per-component unit suites first — the fast gate, run before the heavy stack so a unit
# regression fails in seconds instead of after Keycloak/Temporal/MinIO spin up. Folding
# them here makes this one entrypoint the full regression: everything that runs e2e — local
# dev, the e2e.yml CI job, and the agent Test node — also runs unit. Toolchains (Java 25 +
# Go 1.25 + Node 22) are provided by e2e.yml and the choruskube-dev agent image.
( cd "${REPO_ROOT}/api-server"   && ./gradlew test )
( cd "${REPO_ROOT}/orchestrator" && go test ./... )
( cd "${REPO_ROOT}/web-ui"       && npm ci && npm run test )

"${REPO_ROOT}/scripts/e2e-up.sh"
"${REPO_ROOT}/scripts/e2e-smoke.sh"
"${REPO_ROOT}/scripts/e2e-pipeline.sh"
