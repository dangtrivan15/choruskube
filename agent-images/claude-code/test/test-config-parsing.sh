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
grep -qF '  MAX_TURNS="${MAX_TURNS:-100}"' "$ENTRYPOINT" \
  && ok "MAX_TURNS defined, defaulting to 100" || fail "MAX_TURNS defined, defaulting to 100"
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

# --- Test 11: open_blockers parsing — two entries ---
cat > "$CONFIG" <<'EOF'
{
  "run_id": "abc-123",
  "node_execution_id": "node-456",
  "prompt": "do the thing",
  "task_context": {
    "task_id": "task-1",
    "task_title": "Blocked task",
    "open_blockers": [
      {"item_type": "task", "item_id": "b1", "title": "Prereq A", "status": "in_progress"},
      {"item_type": "story", "item_id": "b2", "title": "Prereq B", "status": "backlog"}
    ]
  }
}
EOF
OPEN_BLOCKERS_JSON=$(jq -c '.task_context.open_blockers // []' "$CONFIG")
BLOCKER_COUNT=$(echo "$OPEN_BLOCKERS_JSON" | jq 'length')
[ "$BLOCKER_COUNT" -eq 2 ] && ok "open_blockers parsed to 2-element array" || fail "open_blockers parsed to 2-element array"

# --- Test 12: entrypoint.sh narrates an "## Open Blockers" section ---
# No existing test covers the "Triggering Task" narration block to mirror — that
# block is untested today, so this establishes the pattern (grep-on-script-content,
# matching this file's existing `--max-turns` flag assertion) rather than copying one.
grep -q "## Open Blockers" "$ENTRYPOINT" \
  && ok "entrypoint narrates Open Blockers section" || fail "entrypoint narrates Open Blockers section"
grep -q "does not prevent the run" "$ENTRYPOINT" \
  && ok "Open Blockers narration states it's informational only" || fail "Open Blockers narration states it's informational only"

# --- Test 13: open_blockers absent (older API server) — defaults to empty, no crash ---
cat > "$CONFIG" <<'EOF'
{
  "run_id": "abc-123",
  "node_execution_id": "node-456",
  "prompt": "do the thing",
  "task_context": {
    "task_id": "task-1",
    "task_title": "Task from an older API server"
  }
}
EOF
OPEN_BLOCKERS_JSON=$(jq -c '.task_context.open_blockers // []' "$CONFIG")
[ "$OPEN_BLOCKERS_JSON" = "[]" ] && ok "missing open_blockers defaults to empty array" || fail "missing open_blockers defaults to empty array"
BLOCKER_COUNT=$(echo "$OPEN_BLOCKERS_JSON" | jq 'length')
[ "$BLOCKER_COUNT" -eq 0 ] && ok "missing open_blockers yields zero-length count, no crash" || fail "missing open_blockers yields zero-length count, no crash"

# --- Test 14: task_context entirely absent (true ad-hoc run, not started from a Task) ---
# Distinct from Test 13 (task_context present but its open_blockers key missing): here the
# whole task_context object is absent, as it always was pre-epic and still is for any run
# not triggered from a roadmap Task. Confirms the no-context path stays byte-for-byte
# unchanged: every derived field falls back to empty via `// empty` / `// []`, no crash.
cat > "$CONFIG" <<'EOF'
{
  "run_id": "abc-123",
  "node_execution_id": "node-456",
  "prompt": "do the thing"
}
EOF
TASK_ID=$(jq -r '.task_context.task_id // empty' "$CONFIG")
STORY_ID=$(jq -r '.task_context.story_id // empty' "$CONFIG")
EPIC_ID=$(jq -r '.task_context.epic_id // empty' "$CONFIG")
[ -z "$TASK_ID" ] && ok "task_context absent — TASK_ID empty" || fail "task_context absent — TASK_ID empty"
[ -z "$STORY_ID" ] && ok "task_context absent — STORY_ID empty" || fail "task_context absent — STORY_ID empty"
[ -z "$EPIC_ID" ] && ok "task_context absent — EPIC_ID empty" || fail "task_context absent — EPIC_ID empty"
OPEN_BLOCKERS_JSON=$(jq -c '.task_context.open_blockers // []' "$CONFIG")
[ "$OPEN_BLOCKERS_JSON" = "[]" ] && ok "task_context absent — OPEN_BLOCKERS_JSON defaults to empty array" || fail "task_context absent — OPEN_BLOCKERS_JSON defaults to empty array"
BLOCKER_COUNT=$(echo "$OPEN_BLOCKERS_JSON" | jq 'length')
[ "$BLOCKER_COUNT" -eq 0 ] && ok "task_context absent — zero-length blocker count, no crash" || fail "task_context absent — zero-length blocker count, no crash"
# Companion structural assertion (mirrors Test 2/12's grep-on-script-content style): both
# narration blocks are guarded behind the same TASK_ID check, so an ad-hoc run's prompt gets
# neither section, not a templated placeholder.
grep -q '## Triggering Task' "$ENTRYPOINT" \
  && ok "entrypoint narrates a Triggering Task section" || fail "entrypoint narrates a Triggering Task section"
grep -qE 'if \[ -n "\$TASK_ID" \]' "$ENTRYPOINT" \
  && ok "Triggering Task / Open Blockers narration guarded by TASK_ID check" || fail "Triggering Task / Open Blockers narration guarded by TASK_ID check"

# --- Test 15: --effort reaches run_claude()'s argv construction (structural) ---
# Mirrors the existing --max-turns assertion style (Test 2): grep the actual
# entrypoint.sh text rather than a hand-copied fragment, so the assertion tracks
# the real argv construction.
grep -qF '${EFFORT:+--effort "$EFFORT"}' "$ENTRYPOINT" \
  && ok "claude invoked with --effort interpolation" || fail "claude invoked with --effort interpolation"

# --- Test 16: effort field parsing — "xhigh" produces EFFORT=xhigh ---
# Same jq parsing logic used for MODEL (`.model // empty`).
cat > "$CONFIG" <<'EOF'
{"run_id":"abc","node_execution_id":"xyz","prompt":"test","effort":"xhigh"}
EOF
EFFORT=$(jq -r '.effort // empty' "$CONFIG")
[ "$EFFORT" = "xhigh" ] && ok "effort=xhigh parsed" || fail "effort=xhigh parsed"

# --- Test 17: effort field absent — EFFORT empty, no --effort token in argv ---
cat > "$CONFIG" <<'EOF'
{"run_id":"abc","node_execution_id":"xyz","prompt":"test"}
EOF
EFFORT=$(jq -r '.effort // empty' "$CONFIG")
[ -z "$EFFORT" ] && ok "missing effort field // empty returns blank" || fail "missing effort field // empty returns blank"
# The same ${EFFORT:+--effort "$EFFORT"} interpolation entrypoint.sh uses — with
# EFFORT empty, bash parameter expansion yields nothing, so no --effort token
# reaches claude's argv at all.
ARGV_FRAGMENT="${EFFORT:+--effort "$EFFORT"}"
[ -z "$ARGV_FRAGMENT" ] && ok "empty EFFORT produces no --effort token in argv" || fail "empty EFFORT produces no --effort token in argv"

# --- Test 18: any effort value reaches claude's argv verbatim ---
# The entrypoint keeps no allowlist: which levels exist is claude's contract, and
# a value it does not know only costs a fallback to default effort. Assert the
# pass-through directly, over levels claude documents plus one it does not, so a
# re-introduced allowlist fails here instead of silently dropping a valid level.
for level in low medium high xhigh max ultracode; do
  printf '{"run_id":"abc","node_execution_id":"xyz","prompt":"test","effort":"%s"}\n' "$level" > "$CONFIG"
  EFFORT=$(jq -r '.effort // empty' "$CONFIG")
  ARGV_FRAGMENT="${EFFORT:+--effort "$EFFORT"}"
  [ "$ARGV_FRAGMENT" = "--effort $level" ] \
    && ok "effort=$level reaches argv verbatim" || fail "effort=$level reaches argv verbatim"
done

# --- Test 18b: an unknown effort value does not abort the entrypoint ---
# Runs the actual entrypoint.sh text (CONFIG_FILE path swapped to a fixture) rather
# than a hand-copied re-implementation. Nothing between the top of the script and
# run_claude() inspects the effort value, so an unrecognized one must reach claude.
# The anchor is the JOB_SECRET check — the next thing that can halt the script after
# the effort read. Reaching it proves execution passed the read without a gate; an
# allowlist re-introduced above it would exit first and this message would be absent.
EFFORT_CONFIG="$TESTDIR/effort_passthrough_config.json"
ENTRYPOINT_COPY="$TESTDIR/entrypoint_effort_check.sh"
sed "s#/workspace/config.json#$EFFORT_CONFIG#g" "$ENTRYPOINT" > "$ENTRYPOINT_COPY"
chmod +x "$ENTRYPOINT_COPY"
cat > "$EFFORT_CONFIG" <<'EOF'
{"run_id":"abc","node_execution_id":"xyz","prompt":"test","effort":"something-else"}
EOF
# Both streams: the config guards report on stderr, but the JOB_SECRET check is a
# plain echo on stdout.
set +e
EFFORT_OUTPUT=$(bash "$ENTRYPOINT_COPY" 2>&1)
set -e
echo "$EFFORT_OUTPUT" | grep -q "JOB_SECRET environment variable not set" \
  && ok "unknown effort value runs past the effort read" \
  || fail "unknown effort value runs past the effort read"

# --- Test 19: input artifact download loop — nested keys, required vs optional ---
# Runs the real Step 1 block against a stub `artifact` so the required/optional branch is
# exercised without object storage. A shell function shadows the PATH binary.
IA_CONFIG="$TESTDIR/config_input_artifacts.json"
cat > "$IA_CONFIG" <<'EOF'
{
  "input_artifacts": {
    "spec_review/spec_and_plan.md": "org/runs/r/e/out/spec_and_plan.md",
    "spec_review/spec_review.md": "org/runs/r/e/out/spec_review.md"
  },
  "required_input_artifacts": ["spec_review/spec_and_plan.md"]
}
EOF

build_step1_harness() {
  # $1 = object path the stub should fail to fetch, $2 = workspace-in dir
  {
    echo 'set -euo pipefail'
    echo "CONFIG_FILE=\"$IA_CONFIG\""
    echo "WORKSPACE_IN=\"$2\""
    echo "artifact() { if [ \"\$2\" = \"$1\" ]; then return 1; fi; printf stub > \"\$3\"; return 0; }"
    awk '/^# --- Step 1: Pull input artifacts/{f=1} /^# Pull run log/{f=0} f' "$ENTRYPOINT"
  }
}

# 19a: an absent OPTIONAL artifact is skipped, and the rest still land
IA_IN_A="$TESTDIR/in_optional"
build_step1_harness "org/runs/r/e/out/spec_review.md" "$IA_IN_A" > "$TESTDIR/step1_optional.sh"
set +e
IA_OUT_A=$(bash "$TESTDIR/step1_optional.sh" 2>&1)
IA_RC_A=$?
set -e
[ "$IA_RC_A" -eq 0 ] && ok "absent optional input artifact does not fail the pod" \
  || fail "absent optional input artifact does not fail the pod ($IA_OUT_A)"
[ -f "$IA_IN_A/spec_review/spec_and_plan.md" ] \
  && ok "nested input artifact key creates its parent directory" \
  || fail "nested input artifact key creates its parent directory"
echo "$IA_OUT_A" | grep -q "Optional input artifact absent" \
  && ok "absent optional input artifact is logged" || fail "absent optional input artifact is logged"

# 19b: an absent REQUIRED artifact stops the pod with a clear error
IA_IN_B="$TESTDIR/in_required"
build_step1_harness "org/runs/r/e/out/spec_and_plan.md" "$IA_IN_B" > "$TESTDIR/step1_required.sh"
set +e
IA_OUT_B=$(bash "$TESTDIR/step1_required.sh" 2>&1)
IA_RC_B=$?
set -e
[ "$IA_RC_B" -ne 0 ] && ok "absent required input artifact exits non-zero" \
  || fail "absent required input artifact exits non-zero"
echo "$IA_OUT_B" | grep -q "required input artifact missing" \
  && ok "absent required input artifact logs a clear error" \
  || fail "absent required input artifact logs a clear error"

# --- Test 20: absent required_input_artifacts key — every input is best-effort ---
IA_LEGACY="$TESTDIR/config_legacy_inputs.json"
cat > "$IA_LEGACY" <<'EOF'
{"input_artifacts": {"run_input/notes.md": "org/runs/r/run_input/notes.md"}}
EOF
LEGACY_REQUIRED=$(jq -r '.required_input_artifacts // [] | .[]' "$IA_LEGACY")
[ -z "$LEGACY_REQUIRED" ] && ok "absent required_input_artifacts parses to empty" \
  || fail "absent required_input_artifacts parses to empty"

# --- Test 21: failure safety net — in-progress work is pushed before reporting ---
# Runs the real safety-net block against real git repos (a bare repo standing in for
# origin) rather than a hand-copied re-implementation, so the commit/push decisions
# under test are the ones that ship. Same extract-the-fragment style as Test 19.
SN_BRANCH="choruskube-run-test"

sn_setup_repo() {
  # $1 = repo name, $2 = run branch to check out ("" leaves it on main)
  local name="$1" branch="$2"
  local remote="$TESTDIR/$name.git" work="$TESTDIR/$name"
  git init -q --bare "$remote"
  git init -q "$work"
  git -C "$work" config user.email "agent@choruskube.local"
  git -C "$work" config user.name "ChorusKube Agent"
  git -C "$work" config commit.gpgsign false
  echo seed > "$work/seed.txt"
  git -C "$work" add -A
  git -C "$work" commit -q -m seed
  git -C "$work" branch -M main
  git -C "$work" remote add origin "$remote"
  git -C "$work" push -q -u origin main
  [ -n "$branch" ] && git -C "$work" checkout -q -b "$branch"
  return 0
}

sn_build_harness() {
  # $1 = REPOS_JSON, $2 = REPO_URL, $3 = WORKING_BRANCH. A failed AI node with no
  # result text is the production case: is_error set, .result absent, last assistant
  # message empty because the cut-off landed mid-tool-call.
  {
    echo 'set -euo pipefail'
    echo 'RUN_ID=run-1'
    echo 'NODE_EXECUTION_ID=exec-1'
    echo 'RESULT_STATUS=failed'
    echo 'ERROR_MESSAGE="Claude reported is_error after 3 attempts (subtype=error_max_turns)"'
    echo 'RESULT=""'
    printf 'REPOS_JSON=%q\n' "$1"
    printf 'REPO_URL=%q\n' "$2"
    printf 'WORKING_BRANCH=%q\n' "$3"
    awk '/^# --- Failure safety net/{f=1} /^# --- Step 5:/{f=0} f' "$ENTRYPOINT"
    echo 'echo "FINAL_STATUS:$RESULT_STATUS"'
    echo 'echo "$RESULT"'
  }
}

# 21a: multi-repo — a dirty repo is committed and pushed, a clean one is not
sn_setup_repo repo-a "$SN_BRANCH"
sn_setup_repo repo-b "$SN_BRANCH"
echo "new work" > "$TESTDIR/repo-a/feature.txt"
SN_REPOS_JSON=$(jq -nc --arg pa "$TESTDIR/repo-a" --arg pb "$TESTDIR/repo-b" --arg br "$SN_BRANCH" \
  '[{name:"repo-a",local_path:$pa,working_branch:$br},{name:"repo-b",local_path:$pb,working_branch:$br}]')
sn_build_harness "$SN_REPOS_JSON" "" "" > "$TESTDIR/safety_multi.sh"
set +e
SN_OUT_A=$(bash "$TESTDIR/safety_multi.sh" 2>&1)
SN_RC_A=$?
set -e
[ "$SN_RC_A" -eq 0 ] && ok "safety net exits clean on a failed node" \
  || fail "safety net exits clean on a failed node ($SN_OUT_A)"
[ "$(git -C "$TESTDIR/repo-a" rev-list --count HEAD)" = "2" ] \
  && ok "dirty repo gets a commit" || fail "dirty repo gets a commit"
[ "$(git -C "$TESTDIR/repo-a.git" rev-parse "$SN_BRANCH")" = "$(git -C "$TESTDIR/repo-a" rev-parse HEAD)" ] \
  && ok "dirty repo's commit reaches origin" || fail "dirty repo's commit reaches origin"
[ "$(git -C "$TESTDIR/repo-b" rev-list --count HEAD)" = "1" ] \
  && ok "clean repo gets no empty commit" || fail "clean repo gets no empty commit"
echo "$SN_OUT_A" | grep -q "repo-a: pushed $SN_BRANCH @ $(git -C "$TESTDIR/repo-a" rev-parse --short HEAD)" \
  && ok "result names the branch and short SHA pushed per repo" \
  || fail "result names the branch and short SHA pushed per repo"
echo "$SN_OUT_A" | grep -q "repo-b:" \
  && ok "every repo in a multi-repo run is reported" || fail "every repo in a multi-repo run is reported"
echo "$SN_OUT_A" | grep -q "error_max_turns" \
  && ok "failure result carries the original error message" \
  || fail "failure result carries the original error message"
echo "$SN_OUT_A" | grep -q "FINAL_STATUS:failed" \
  && ok "safety net leaves RESULT_STATUS failed" || fail "safety net leaves RESULT_STATUS failed"

# 21b: a second failure on the same branch pushes nothing new
# Retries reuse the run branch, so the safety net has to be idempotent — re-running
# it over an already-pushed branch must not manufacture a commit or a push.
set +e
SN_OUT_B=$(bash "$TESTDIR/safety_multi.sh" 2>&1)
SN_RC_B=$?
set -e
[ "$SN_RC_B" -eq 0 ] && ok "safety net is re-runnable on an already-pushed branch" \
  || fail "safety net is re-runnable on an already-pushed branch ($SN_OUT_B)"
[ "$(git -C "$TESTDIR/repo-a" rev-list --count HEAD)" = "2" ] \
  && ok "re-run adds no further commit" || fail "re-run adds no further commit"
echo "$SN_OUT_B" | grep -q "repo-a: nothing new to push" \
  && ok "re-run reports nothing new to push" || fail "re-run reports nothing new to push"

# 21c: a push that fails must neither crash the pod nor mask the original error
sn_setup_repo repo-c "$SN_BRANCH"
echo "new work" > "$TESTDIR/repo-c/feature.txt"
git -C "$TESTDIR/repo-c" remote set-url origin "$TESTDIR/does-not-exist.git"
SN_REPOS_C=$(jq -nc --arg p "$TESTDIR/repo-c" --arg br "$SN_BRANCH" \
  '[{name:"repo-c",local_path:$p,working_branch:$br}]')
sn_build_harness "$SN_REPOS_C" "" "" > "$TESTDIR/safety_pushfail.sh"
set +e
SN_OUT_C=$(bash "$TESTDIR/safety_pushfail.sh" 2>&1)
SN_RC_C=$?
set -e
[ "$SN_RC_C" -eq 0 ] && ok "unreachable origin does not crash the safety net" \
  || fail "unreachable origin does not crash the safety net ($SN_OUT_C)"
echo "$SN_OUT_C" | grep -q "error_max_turns" \
  && ok "push failure does not mask the original error" || fail "push failure does not mask the original error"
echo "$SN_OUT_C" | grep -q "FINAL_STATUS:failed" \
  && ok "push failure leaves RESULT_STATUS failed" || fail "push failure leaves RESULT_STATUS failed"
[ "$(git -C "$TESTDIR/repo-c" rev-list --count HEAD)" = "2" ] \
  && ok "work is still committed locally when the push fails" \
  || fail "work is still committed locally when the push fails"
echo "$SN_OUT_C" | grep -q "push failed" \
  && ok "failed push is reported in the result" || fail "failed push is reported in the result"

# 21d: a node with no run branch is left alone — its HEAD is the default branch
sn_setup_repo repo-d ""
echo "new work" > "$TESTDIR/repo-d/feature.txt"
SN_MAIN_BEFORE=$(git -C "$TESTDIR/repo-d.git" rev-parse main)
SN_REPOS_D=$(jq -nc --arg p "$TESTDIR/repo-d" '[{name:"repo-d",local_path:$p}]')
sn_build_harness "$SN_REPOS_D" "" "" > "$TESTDIR/safety_nobranch.sh"
set +e
SN_OUT_D=$(bash "$TESTDIR/safety_nobranch.sh" 2>&1)
SN_RC_D=$?
set -e
[ "$SN_RC_D" -eq 0 ] && ok "repo without a run branch does not crash the safety net" \
  || fail "repo without a run branch does not crash the safety net ($SN_OUT_D)"
[ "$(git -C "$TESTDIR/repo-d" rev-list --count HEAD)" = "1" ] \
  && ok "no run branch — nothing committed to the default branch" \
  || fail "no run branch — nothing committed to the default branch"
[ "$(git -C "$TESTDIR/repo-d.git" rev-parse main)" = "$SN_MAIN_BEFORE" ] \
  && ok "no run branch — origin's default branch is untouched" \
  || fail "no run branch — origin's default branch is untouched"
echo "$SN_OUT_D" | grep -q "no repository work to preserve" \
  && ok "empty preserve summary reported explicitly" || fail "empty preserve summary reported explicitly"

# 21e: single-repo mode uses the /workspace/repo clone and $WORKING_BRANCH
sn_setup_repo single-repo "$SN_BRANCH"
echo "new work" > "$TESTDIR/single-repo/feature.txt"
sn_build_harness "" "https://example.invalid/single-repo.git" "$SN_BRANCH" \
  | sed "s#\"/workspace/repo\"#\"$TESTDIR/single-repo\"#" > "$TESTDIR/safety_single.sh"
set +e
SN_OUT_E=$(bash "$TESTDIR/safety_single.sh" 2>&1)
SN_RC_E=$?
set -e
[ "$SN_RC_E" -eq 0 ] && ok "single-repo mode safety net exits clean" \
  || fail "single-repo mode safety net exits clean ($SN_OUT_E)"
[ "$(git -C "$TESTDIR/single-repo.git" rev-parse "$SN_BRANCH")" = "$(git -C "$TESTDIR/single-repo" rev-parse HEAD)" ] \
  && ok "single-repo mode pushes the working branch" || fail "single-repo mode pushes the working branch"

# --- Test 22: per-node turn/retry budget — configured values win, absent keeps 100/3 ---
# The read and the defaults sit in two different parts of entrypoint.sh (config parsing at
# the top, the assignment inside the AI branch), and a value read at the top is worthless
# if the assignment shadows it. Extract BOTH real fragments and run them together, same
# style as Tests 19/21, so the test observes the value claude would actually be launched with.
build_budget_harness() {
  # $1 = config.json fixture path
  {
    echo 'set -euo pipefail'
    printf 'CONFIG_FILE=%q\n' "$1"
    awk '/^# --- Per-node turn\/retry budget/{f=1} /^# Build system prompt/{f=0} f' "$ENTRYPOINT"
    grep -E '^  MAX_(TURNS|RETRIES)=' "$ENTRYPOINT"
    echo 'echo "MAX_TURNS=$MAX_TURNS MAX_RETRIES=$MAX_RETRIES"'
  }
}

# 22a: both configured — the config values reach the budget variables
BUDGET_CONFIG="$TESTDIR/config_budget.json"
cat > "$BUDGET_CONFIG" <<'EOF'
{"run_id":"abc","node_execution_id":"xyz","prompt":"test","max_turns":"250","max_retries":"5"}
EOF
build_budget_harness "$BUDGET_CONFIG" > "$TESTDIR/budget_set.sh"
BUDGET_OUT_A=$(bash "$TESTDIR/budget_set.sh")
[ "$BUDGET_OUT_A" = "MAX_TURNS=250 MAX_RETRIES=5" ] \
  && ok "configured max_turns/max_retries win over the defaults" \
  || fail "configured max_turns/max_retries win over the defaults ($BUDGET_OUT_A)"

# 22b: neither configured — today's budget, unchanged
cat > "$BUDGET_CONFIG" <<'EOF'
{"run_id":"abc","node_execution_id":"xyz","prompt":"test"}
EOF
build_budget_harness "$BUDGET_CONFIG" > "$TESTDIR/budget_unset.sh"
BUDGET_OUT_B=$(bash "$TESTDIR/budget_unset.sh")
[ "$BUDGET_OUT_B" = "MAX_TURNS=100 MAX_RETRIES=3" ] \
  && ok "absent budget config keeps the 100-turn / 3-attempt defaults" \
  || fail "absent budget config keeps the 100-turn / 3-attempt defaults ($BUDGET_OUT_B)"

# 22c: one configured, one not — they default independently
cat > "$BUDGET_CONFIG" <<'EOF'
{"run_id":"abc","node_execution_id":"xyz","prompt":"test","max_turns":300}
EOF
build_budget_harness "$BUDGET_CONFIG" > "$TESTDIR/budget_partial.sh"
BUDGET_OUT_C=$(bash "$TESTDIR/budget_partial.sh")
[ "$BUDGET_OUT_C" = "MAX_TURNS=300 MAX_RETRIES=3" ] \
  && ok "max_turns configured alone leaves max_retries at its default" \
  || fail "max_turns configured alone leaves max_retries at its default ($BUDGET_OUT_C)"

# --- Test 23: malformed budget values fail loudly before any claude invocation ---
# Same technique as Test 18 (run the real entrypoint.sh with CONFIG_FILE swapped for a
# fixture): the guard sits with the other config reads, ahead of JOB_SECRET validation,
# BuildKit setup and run_claude's definition, so a value that trips it never reaches claude.
run_budget_guard() {
  # $1 = config.json body. Leaves the run's stderr in BUDGET_ERR and its status in BUDGET_RC.
  local cfg="$TESTDIR/bad_budget_config.json"
  printf '%s\n' "$1" > "$cfg"
  local copy="$TESTDIR/entrypoint_budget_check.sh"
  sed "s#/workspace/config.json#$cfg#g" "$ENTRYPOINT" > "$copy"
  set +e
  BUDGET_ERR=$(bash "$copy" 2>&1 >/dev/null)
  BUDGET_RC=$?
  set -e
}

assert_budget_rejected() {
  # $1 = label, $2 = config.json body, $3 = substring the error must name
  run_budget_guard "$2"
  [ "$BUDGET_RC" -ne 0 ] && ok "$1 exits non-zero" || fail "$1 exits non-zero"
  echo "$BUDGET_ERR" | grep -qF "$3" \
    && ok "$1 logs a clear error" || fail "$1 logs a clear error ($BUDGET_ERR)"
  echo "$BUDGET_ERR" | grep -q "Claude Code exited" \
    && fail "$1 reached a claude invocation" || ok "$1 fires before any run_claude()/claude invocation"
}

assert_budget_rejected "non-numeric max_turns" \
  '{"run_id":"abc","node_execution_id":"xyz","prompt":"test","max_turns":"many"}' \
  "unsupported max_turns value 'many'"
assert_budget_rejected "zero max_turns" \
  '{"run_id":"abc","node_execution_id":"xyz","prompt":"test","max_turns":0}' \
  "unsupported max_turns value '0'"
assert_budget_rejected "negative max_retries" \
  '{"run_id":"abc","node_execution_id":"xyz","prompt":"test","max_retries":-1}' \
  "unsupported max_retries value '-1'"
assert_budget_rejected "whitespace-only max_retries" \
  '{"run_id":"abc","node_execution_id":"xyz","prompt":"test","max_retries":"  "}' \
  "must be a positive integer"

# --- Test 24: MAX_RETRIES bounds every attempt loop, and they share one counter ---
# The main retry, artifact-enforcement, decision-verification and PR-verification loops
# deliberately share $ATTEMPT so the budget caps total attempts across all phases, not per
# phase (Caveat 4: PR verification only gets whatever budget the first three phases didn't
# already spend). A per-node max_retries is only meaningful if that stays true.
ATTEMPT_LOOPS=$(grep -cF '[ $ATTEMPT -lt $MAX_RETRIES ]' "$ENTRYPOINT")
[ "$ATTEMPT_LOOPS" -eq 4 ] \
  && ok "all four attempt loops are bounded by the shared \$ATTEMPT/\$MAX_RETRIES pair" \
  || fail "all four attempt loops are bounded by the shared \$ATTEMPT/\$MAX_RETRIES pair (found $ATTEMPT_LOOPS)"

# --- Test 19: needs_pr field parsing (mirrors Test 10's need_decision coverage) ---
# A regression here (e.g. a typo turning .needs_pr into .need_pr) would silently disable
# the entire PR-completion gate feature with nothing else in this suite to catch it: the
# Go-side orchestrator tests only prove config.json gets written correctly, not that
# entrypoint.sh reads it back, and the E2E/mock-agent path never exercises this field
# either (NEED_PR is gated on EXECUTOR_TYPE != script, same as NEED_DECISION).
#
# The two jq assertions below exercise the `// false` idiom against sample JSON in
# isolation — they'd pass identically even if entrypoint.sh's own extraction line used
# a different field name entirely, so on their own they don't actually guard against the
# typo they're introduced to catch. The third assertion closes that gap by asserting on
# entrypoint.sh's own literal extraction line, the same way Test 20 below already does
# for the PR-verification block — confirmed by empirically reintroducing the exact
# `.needs_pr` -> `.need_pr` typo into entrypoint.sh and re-running this suite: without
# this third assertion, all tests still passed.
cat > "$CONFIG" <<'EOF'
{"run_id":"x","node_execution_id":"y","prompt":"z","needs_pr":true}
EOF
NEED_PR=$(jq -r '.needs_pr // false' "$CONFIG")
[ "$NEED_PR" = "true" ] && ok "needs_pr=true parsed" || fail "needs_pr=true parsed"

cat > "$CONFIG" <<'EOF'
{"run_id":"x","node_execution_id":"y","prompt":"z"}
EOF
NEED_PR=$(jq -r '.needs_pr // false' "$CONFIG")
[ "$NEED_PR" = "false" ] && ok "needs_pr absent defaults to false" || fail "needs_pr absent defaults to false"

grep -q "jq -r '\.needs_pr // false'" "$ENTRYPOINT" \
  && ok "entrypoint.sh itself extracts NEED_PR via .needs_pr (not just this test's own jq idiom)" \
  || fail "entrypoint.sh itself extracts NEED_PR via .needs_pr (not just this test's own jq idiom)"

# --- Test 20: entrypoint.sh's PR-verification block branches on check-prs's exit
# status (not stdout text, unlike DECISION's "(none)" string-equality idiom), and
# captures check-prs's stderr diagnostics too so a loud failure (Caveat 3) actually
# reaches the retry prompt / final error message instead of being silently dropped ---
grep -q "PR verification" "$ENTRYPOINT" \
  && ok "entrypoint narrates a PR verification block" || fail "entrypoint narrates a PR verification block"
grep -q 'PR_CHECK_STATUS=\$?' "$ENTRYPOINT" \
  && ok "PR verification branches on check-prs's exit status" || fail "PR verification branches on check-prs's exit status"
grep -q 'check-prs 2>&1' "$ENTRYPOINT" \
  && ok "PR verification captures check-prs's stderr, not just stdout" || fail "PR verification captures check-prs's stderr, not just stdout"

# --- Test: script-path RESULT points at the index only when one exists ---
# Not every script node runs run-all-tests (E2eTestDataSeeder wires ~10 script nodes to
# mock-agent.sh scenarios that write no test_report.md), so the index wording can't be
# unconditional — it has to be guarded by the file actually existing, with the original
# raw-output wording surviving as the fallback for everyone else.
ENTRY="$(dirname "${BASH_SOURCE[0]}")/../entrypoint.sh"
grep -qF 'RESULT="Read test_report.md' "$ENTRY" \
    && ok "script RESULT points at test_report.md" \
    || fail "script RESULT points at test_report.md"
grep -qF 'if [ -f /workspace/out/test_report.md ]; then' "$ENTRY" \
    && ok "index RESULT is guarded by a test_report.md existence check" \
    || fail "index RESULT is guarded by a test_report.md existence check"
grep -qF 'RESULT="Read test_output.txt for full script output"' "$ENTRY" \
    && ok "raw-output RESULT fallback is present for script nodes with no index" \
    || fail "raw-output RESULT fallback is present for script nodes with no index"

# --- Test: script-path RESULT behaves correctly for both branches (behavioral) ---
# Extracts the real fragment (SCRIPT_OUTPUT capture through the RESULT if/else) rather
# than a hand-copied reimplementation, same technique as Tests 19/21/22. Matched by exact
# line text, not a regex, so "set +e"'s literal `+` needs no escaping. API_SERVER_URL is
# left empty so the decision-submission curl call is skipped, the same guard the real
# script relies on. /workspace/out is swapped for a temp dir via sed (that path isn't
# writable outside a pod), mirroring Test 21e's swap of /workspace/repo.
build_script_result_harness() {
  # $1 = temp dir standing in for /workspace/out, $2 = COMMAND to eval
  {
    echo 'set -euo pipefail'
    echo 'API_SERVER_URL='
    echo 'export API_SERVER_URL'
    printf 'COMMAND=%q\n' "$2"
    awk '$0=="  set +e"{f=1} $0=="else"{f=0} f' "$ENTRY" | sed "s#/workspace/out#$1#g"
    echo 'echo "RESULT_IS:$RESULT"'
  }
}

# with-index: the command writes test_report.md itself, same as run-all-tests would
SR_DIR_A="$TESTDIR/script_result_index"
build_script_result_harness "$SR_DIR_A" \
  "mkdir -p \"$SR_DIR_A\" && printf 'verdict: pass' > \"$SR_DIR_A/test_report.md\"" \
  > "$TESTDIR/script_result_index.sh"
SR_OUT_A=$(bash "$TESTDIR/script_result_index.sh")
echo "$SR_OUT_A" | grep -qF 'RESULT_IS:Read test_report.md first' \
  && ok "RESULT points at the index when the command writes one" \
  || fail "RESULT points at the index when the command writes one ($SR_OUT_A)"
[ -f "$SR_DIR_A/test_output.txt" ] \
  && ok "test_output.txt still written when an index exists" \
  || fail "test_output.txt still written when an index exists"

# without-index: a plain script node (e.g. a mock-agent.sh scenario) writes no report
SR_DIR_B="$TESTDIR/script_result_noindex"
build_script_result_harness "$SR_DIR_B" "printf 'plain command output'" \
  > "$TESTDIR/script_result_noindex.sh"
SR_OUT_B=$(bash "$TESTDIR/script_result_noindex.sh")
echo "$SR_OUT_B" | grep -qF 'RESULT_IS:Read test_output.txt for full script output' \
  && ok "RESULT falls back to raw output when no index was written" \
  || fail "RESULT falls back to raw output when no index was written ($SR_OUT_B)"
[ -f "$SR_DIR_B/test_output.txt" ] \
  && ok "test_output.txt still written when no index exists" \
  || fail "test_output.txt still written when no index exists"

# --- Summary ---
echo ""
echo "Results: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
