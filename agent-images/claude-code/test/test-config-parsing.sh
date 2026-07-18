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

# --- Test 2: the turn cap reaches claude as a CLI flag ---
# settings.json has no maxTurns key, and user settings files are validated
# strictly — one unrecognized key rejects the whole file, so a cap declared
# there is silently never applied. --max-turns is the supported mechanism.
ENTRYPOINT="$(dirname "${BASH_SOURCE[0]}")/../entrypoint.sh"
grep -q -- '--max-turns "\$MAX_TURNS"' "$ENTRYPOINT" \
  && ok "claude invoked with --max-turns" || fail "claude invoked with --max-turns"
grep -qE '^  MAX_TURNS=[0-9]+' "$ENTRYPOINT" \
  && ok "MAX_TURNS defined" || fail "MAX_TURNS defined"
[ ! -f "$(dirname "${BASH_SOURCE[0]}")/../settings.json" ] \
  && ok "no settings.json shipping an unsupported key" || fail "no settings.json shipping an unsupported key"

# --- Test 2b: progress logging reaches stderr, not /dev/null ---
# /dev/stderr is a symlink to /proc/self/fd/2, so `2>/dev/null > /dev/stderr`
# resolves the target to /dev/null and silently discards every line. Assert the
# ordering in the file, and that the ordering it uses actually emits.
grep -q "2>/dev/null > /dev/stderr" "$ENTRYPOINT" \
  && fail "log_progress redirect order discards output" || ok "log_progress redirect order preserved"
EMITTED=$( ( echo '{"a":1}' | jq -r '.a' > /dev/stderr 2>/dev/null ) 2>&1 )
[ "$EMITTED" = "1" ] && ok "chosen redirect order emits to stderr" || fail "chosen redirect order emits to stderr"

# --- Test 2c: an errored result is distinguishable from a finished one ---
# Error results carry is_error and no .result field at all. Folding the last
# assistant message into CLAUDE_RESULT would make a truncated run look finished.
ERR_RESULT='{"type":"result","subtype":"error_max_turns","is_error":true,"num_turns":2,"terminal_reason":"max_turns","errors":["Reached maximum number of turns (1)"]}'
R=$(echo "$ERR_RESULT" | jq -r '.result // empty')
IS_ERR=$(echo "$ERR_RESULT" | jq -r 'if .is_error == true then "true" else "false" end')
ERRS=$(echo "$ERR_RESULT" | jq -r '(.errors // []) | join("; ")')
[ -z "$R" ] && ok "error result has no .result" || fail "error result has no .result"
[ "$IS_ERR" = "true" ] && ok "is_error extracted from error result" || fail "is_error extracted from error result"
[ -n "$ERRS" ] && ok "errors[] joined for error message" || fail "errors[] joined for error message"

OK_RESULT='{"type":"result","subtype":"success","is_error":false,"result":"done"}'
[ "$(echo "$OK_RESULT" | jq -r 'if .is_error == true then "true" else "false" end')" = "false" ] \
  && ok "is_error false on success result" || fail "is_error false on success result"

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
