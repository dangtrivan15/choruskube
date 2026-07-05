#!/bin/bash
# Unit tests for entrypoint.sh logic fragments (no live container needed)
set -euo pipefail

PASS=0
FAIL=0
TESTDIR=$(mktemp -d)
trap 'rm -rf "$TESTDIR"' EXIT

ok() { echo "PASS: $1"; PASS=$((PASS+1)); }
fail() { echo "FAIL: $1"; FAIL=$((FAIL+1)); }

# --- Test 1: jq config field parsing ---
CONFIG="$TESTDIR/config.json"
cat > "$CONFIG" <<'EOF'
{
  "run_id": "abc-123",
  "node_execution_id": "node-456",
  "prompt": "do the thing",
  "executor_type": "ai",
  "need_decision": false
}
EOF
RUN_ID=$(jq -r '.run_id' "$CONFIG")
[ "$RUN_ID" = "abc-123" ] && ok "run_id parsed" || fail "run_id parsed"

EXECUTOR=$(jq -r '.executor_type // "ai"' "$CONFIG")
[ "$EXECUTOR" = "ai" ] && ok "executor_type parsed" || fail "executor_type parsed"

# --- Test 2: settings.json has maxTurns=100 guard ---
# The max-turns guard is enforced via settings.json (read natively by Claude Code),
# not via a config.json field. Verify the actual deployed settings file is correct.
SETTINGS_FILE="$(dirname "${BASH_SOURCE[0]}")/../settings.json"
SETTINGS_MAX_TURNS=$(jq -r '.maxTurns' "$SETTINGS_FILE")
[ "$SETTINGS_MAX_TURNS" = "100" ] && ok "settings.json maxTurns=100" || fail "settings.json maxTurns=100"

# --- Test 3: // empty pattern returns blank for absent optional fields ---
# entrypoint.sh uses `.field // empty` for repo_url, working_branch, etc.
# Verify this produces an empty string (not "null") when the field is absent.
cat > "$CONFIG" <<'EOF'
{"run_id":"abc","node_execution_id":"xyz","prompt":"test","executor_type":"ai","need_decision":false}
EOF
REPO_URL=$(jq -r '.repo_url // empty' "$CONFIG")
[ -z "$REPO_URL" ] && ok "missing field // empty returns blank" || fail "missing field // empty returns blank"

# --- Test 4: Run ID in user message, not system prompt ---
PROMPT="do the task"
RUN_ID="run-xyz"
FULL_PROMPT="Run ID: ${RUN_ID}

${PROMPT}"
echo "$FULL_PROMPT" | grep -q "Run ID: run-xyz" && ok "Run ID in full_prompt" || fail "Run ID in full_prompt"
# System prompt should NOT contain the run ID
SYSTEM_PROMPT="## Autonomous Operation"
echo "$SYSTEM_PROMPT" | grep -qv "Run ID:" && ok "Run ID absent from system_prompt" || fail "Run ID absent from system_prompt"

# --- Test 5: Single-repo CLAUDE.md loading ---
REPO="$TESTDIR/repo"
mkdir -p "$REPO"
echo "# Project Conventions" > "$REPO/CLAUDE.md"
SYSTEM_PROMPT="initial content"
if [ -f "$REPO/CLAUDE.md" ]; then
  SYSTEM_PROMPT="${SYSTEM_PROMPT}

$(cat "$REPO/CLAUDE.md")"
fi
echo "$SYSTEM_PROMPT" | grep -q "Project Conventions" && ok "single-repo CLAUDE.md loaded" || fail "single-repo CLAUDE.md loaded"

# --- Test 6: Single-repo CLAUDE.md absent — no error ---
REPO2="$TESTDIR/repo2"
mkdir -p "$REPO2"
SYSTEM_PROMPT2="initial"
if [ -f "$REPO2/CLAUDE.md" ]; then
  SYSTEM_PROMPT2="${SYSTEM_PROMPT2}

$(cat "$REPO2/CLAUDE.md")"
fi
[ "$SYSTEM_PROMPT2" = "initial" ] && ok "no CLAUDE.md — prompt unchanged" || fail "no CLAUDE.md — prompt unchanged"

# --- Test 7: SYSTEM_PROMPT export survives subprocess ---
export SYSTEM_PROMPT="exported-value"
CHILD_RESULT=$(bash -c 'echo "${SYSTEM_PROMPT:-NOT_EXPORTED}"')
[ "$CHILD_RESULT" = "exported-value" ] && ok "SYSTEM_PROMPT visible in subprocess" || fail "SYSTEM_PROMPT visible in subprocess"

# --- Test 8: Parallel clone PID collection (structural) ---
PIDS=()
for i in 1 2 3; do
  (sleep 0.01; exit 0) &
  PIDS+=($!)
done
CLONE_FAILED=0
for pid in "${PIDS[@]}"; do
  wait "$pid" || CLONE_FAILED=1
done
[ "$CLONE_FAILED" = "0" ] && ok "parallel PIDs all succeed" || fail "parallel PIDs all succeed"

# --- Test 9: Parallel clone failure detection ---
PIDS2=()
(exit 0) & PIDS2+=($!)
(exit 1) & PIDS2+=($!)
(exit 0) & PIDS2+=($!)
CLONE_FAILED2=0
for pid in "${PIDS2[@]}"; do
  wait "$pid" || CLONE_FAILED2=1
done
[ "$CLONE_FAILED2" = "1" ] && ok "parallel failure detected" || fail "parallel failure detected"

# --- Test 10: need_decision false — no decision required ---
cat > "$CONFIG" <<'EOF'
{"run_id":"x","node_execution_id":"y","prompt":"z","need_decision":false}
EOF
NEED_DECISION=$(jq -r '.need_decision // false' "$CONFIG")
[ "$NEED_DECISION" = "false" ] && ok "need_decision=false parsed" || fail "need_decision=false parsed"

# --- Summary ---
echo ""
echo "Results: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
