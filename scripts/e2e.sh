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
#
# The three suites share no state (separate processes, separate toolchains), so they run
# concurrently instead of one after another. Each is launched as a background subprocess;
# we `wait` on every PID individually (rather than a bare `wait`) so we can capture each
# one's own exit code and fail on the first non-zero one we see, instead of letting a later
# success in the `wait` loop mask an earlier failure.
( cd "${REPO_ROOT}/api-server"   && ./gradlew test )        &
API_SERVER_PID=$!
( cd "${REPO_ROOT}/orchestrator" && go test ./... )          &
ORCHESTRATOR_PID=$!
( cd "${REPO_ROOT}/web-ui"       && npm ci && npm run test ) &
WEB_UI_PID=$!

UNIT_TEST_FAILED=0
wait "$API_SERVER_PID"   || { echo "!!! api-server unit tests failed"   >&2; UNIT_TEST_FAILED=1; }
wait "$ORCHESTRATOR_PID" || { echo "!!! orchestrator unit tests failed" >&2; UNIT_TEST_FAILED=1; }
wait "$WEB_UI_PID"       || { echo "!!! web-ui unit tests failed"       >&2; UNIT_TEST_FAILED=1; }
[ "$UNIT_TEST_FAILED" = "0" ] || exit 1

"${REPO_ROOT}/scripts/e2e-up.sh"
"${REPO_ROOT}/scripts/e2e-smoke.sh"
"${REPO_ROOT}/scripts/e2e-pipeline.sh"
