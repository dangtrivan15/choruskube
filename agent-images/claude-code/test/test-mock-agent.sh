#!/bin/bash
# Unit tests for mock-agent.sh logic fragments (no live container needed)
set -euo pipefail

PASS=0
FAIL=0
TESTDIR=$(mktemp -d)
trap 'rm -rf "$TESTDIR"' EXIT

ok() { echo "PASS: $1"; PASS=$((PASS+1)); }
fail() { echo "FAIL: $1"; FAIL=$((FAIL+1)); }

MOCK_AGENT="$(dirname "${BASH_SOURCE[0]}")/../mock-agent.sh"

# --- Harness ---
# mock-agent.sh reads an absolute path (/workspace/in), so the assertion block is extracted
# and run against a fake root via $WORKSPACE_IN rather than writing to a real /workspace.
# Same extract-the-real-fragment approach as test-config-parsing.sh's Tests 19/21, so these
# assertions track the block that ships instead of a hand-copied re-implementation.
build_expect_harness() {
  # $1 = fake /workspace/in root; remaining args = --expect-input values
  local ws_in="$1"
  shift
  {
    echo 'set -euo pipefail'
    printf 'WORKSPACE_IN=%q\n' "$ws_in"
    echo 'EXPECT_INPUTS=()'
    local rel
    for rel in "$@"; do
      printf 'EXPECT_INPUTS+=(%q)\n' "$rel"
    done
    awk '/^# --- Input artifact assertions/{f=1} /^# --- Helpers ---/{f=0} f' "$MOCK_AGENT"
    echo 'echo "SCENARIO_REACHED"'
  }
}

run_expect_harness() {
  # $1 = fake /workspace/in root; remaining args = --expect-input values.
  # Leaves combined output in EXPECT_OUT and the exit status in EXPECT_RC.
  build_expect_harness "$@" > "$TESTDIR/expect_harness.sh"
  set +e
  EXPECT_OUT=$(bash "$TESTDIR/expect_harness.sh" 2>&1)
  EXPECT_RC=$?
  set -e
}

# --- Test 1: a present, non-empty input artifact passes ---
IN_OK="$TESTDIR/in_ok"
mkdir -p "$IN_OK/step_1"
echo "step 1 output" > "$IN_OK/step_1/step-1-done"
run_expect_harness "$IN_OK" "step_1/step-1-done"
[ "$EXPECT_RC" -eq 0 ] && ok "present non-empty input artifact passes" \
  || fail "present non-empty input artifact passes ($EXPECT_OUT)"
echo "$EXPECT_OUT" | grep -q "Input artifact present: $IN_OK/step_1/step-1-done" \
  && ok "present input artifact logs one confirmation line" \
  || fail "present input artifact logs one confirmation line ($EXPECT_OUT)"
echo "$EXPECT_OUT" | grep -q "SCENARIO_REACHED" \
  && ok "assertions pass through to the scenario dispatch" \
  || fail "assertions pass through to the scenario dispatch"

# --- Test 2: a missing input artifact fails non-zero with a diagnosable error ---
# The failure mode this guards against: the api-server resolved the manifest but nothing
# reached disk. The listing of what IS present is what separates "download loop broke" from
# "manifest was empty", so assert it is emitted alongside the error.
IN_MISSING="$TESTDIR/in_missing"
mkdir -p "$IN_MISSING/step_1"
echo "some other file" > "$IN_MISSING/step_1/unrelated.txt"
run_expect_harness "$IN_MISSING" "step_1/step-1-done"
[ "$EXPECT_RC" -ne 0 ] && ok "missing input artifact exits non-zero" \
  || fail "missing input artifact exits non-zero"
echo "$EXPECT_OUT" | grep -q "expected input artifact not found: $IN_MISSING/step_1/step-1-done" \
  && ok "missing input artifact names the path it looked for" \
  || fail "missing input artifact names the path it looked for ($EXPECT_OUT)"
echo "$EXPECT_OUT" | grep -q "unrelated.txt" \
  && ok "missing input artifact lists what did arrive" \
  || fail "missing input artifact lists what did arrive ($EXPECT_OUT)"
echo "$EXPECT_OUT" | grep -q "SCENARIO_REACHED" \
  && fail "missing input artifact still ran the scenario" \
  || ok "missing input artifact aborts before the scenario runs"

# --- Test 3: an empty (zero-byte) input artifact fails ---
# A zero-byte file is the shape a half-finished/failed download leaves behind, so existence
# alone is not enough — the check is -s, not -e.
IN_EMPTY="$TESTDIR/in_empty"
mkdir -p "$IN_EMPTY/step_1"
: > "$IN_EMPTY/step_1/step-1-done"
run_expect_harness "$IN_EMPTY" "step_1/step-1-done"
[ "$EXPECT_RC" -ne 0 ] && ok "empty input artifact exits non-zero" \
  || fail "empty input artifact exits non-zero"
echo "$EXPECT_OUT" | grep -q "expected input artifact is empty: $IN_EMPTY/step_1/step-1-done" \
  && ok "empty input artifact is reported as empty, not missing" \
  || fail "empty input artifact is reported as empty, not missing ($EXPECT_OUT)"

# --- Test 4: a missing /workspace/in directory is reported, not swallowed ---
run_expect_harness "$TESTDIR/in_absent" "step_1/step-1-done"
[ "$EXPECT_RC" -ne 0 ] && ok "absent input directory exits non-zero" \
  || fail "absent input directory exits non-zero"
echo "$EXPECT_OUT" | grep -q "No such directory: $TESTDIR/in_absent" \
  && ok "absent input directory is named in the error" \
  || fail "absent input directory is named in the error ($EXPECT_OUT)"

# --- Test 5: multiple --expect-input flags are all checked ---
IN_MULTI="$TESTDIR/in_multi"
mkdir -p "$IN_MULTI/step_1" "$IN_MULTI/step_2"
echo a > "$IN_MULTI/step_1/step-1-done"
echo b > "$IN_MULTI/step_2/step-2-done"
run_expect_harness "$IN_MULTI" "step_1/step-1-done" "step_2/step-2-done"
[ "$EXPECT_RC" -eq 0 ] && ok "all present multi-flag inputs pass" \
  || fail "all present multi-flag inputs pass ($EXPECT_OUT)"
[ "$(echo "$EXPECT_OUT" | grep -c "Input artifact present:")" -eq 2 ] \
  && ok "each --expect-input gets its own confirmation line" \
  || fail "each --expect-input gets its own confirmation line ($EXPECT_OUT)"

# 5b: the SECOND flag being absent must still fail — a loop that only checks the first
# would pass this and silently stop asserting anything beyond one file.
rm "$IN_MULTI/step_2/step-2-done"
run_expect_harness "$IN_MULTI" "step_1/step-1-done" "step_2/step-2-done"
[ "$EXPECT_RC" -ne 0 ] && ok "a later --expect-input is checked too" \
  || fail "a later --expect-input is checked too"
echo "$EXPECT_OUT" | grep -q "step_2/step-2-done" \
  && ok "the failing path is the one named in the error" \
  || fail "the failing path is the one named in the error ($EXPECT_OUT)"

# --- Test 6: no --expect-input at all is a clean no-op ---
# The script runs under `set -euo pipefail`, where a bare "${ARR[@]}" over an empty array
# aborts on older bash. This is the assertion that keeps the empty-array-safe expansion
# (`${ARR[@]+"${ARR[@]}"}`, same idiom as entrypoint.sh's ADD_DIR_ARGS) in place.
run_expect_harness "$TESTDIR/in_absent"
[ "$EXPECT_RC" -eq 0 ] && ok "no --expect-input flags is a clean no-op" \
  || fail "no --expect-input flags is a clean no-op ($EXPECT_OUT)"
echo "$EXPECT_OUT" | grep -q "SCENARIO_REACHED" \
  && ok "no-op case still reaches the scenario dispatch" \
  || fail "no-op case still reaches the scenario dispatch ($EXPECT_OUT)"
echo "$EXPECT_OUT" | grep -q "Input artifact present:" \
  && fail "no-op case logged a confirmation for nothing" \
  || ok "no-op case logs no confirmation lines"

# --- Test 7: the flag is parsed and defaults to /workspace/in (structural) ---
# The harness above supplies WORKSPACE_IN/EXPECT_INPUTS directly, so it cannot see whether
# argument parsing actually populates them. Grep the real script for both, mirroring
# test-config-parsing.sh's grep-on-script-content assertions.
grep -qF -- '--expect-input)' "$MOCK_AGENT" \
  && ok "--expect-input is an accepted option" || fail "--expect-input is an accepted option"
grep -qF 'EXPECT_INPUTS+=("$2")' "$MOCK_AGENT" \
  && ok "--expect-input appends, making it repeatable" || fail "--expect-input appends, making it repeatable"
grep -qF 'WORKSPACE_IN="${WORKSPACE_IN:-/workspace/in}"' "$MOCK_AGENT" \
  && ok "input base defaults to /workspace/in" || fail "input base defaults to /workspace/in"

# --- Test 8: the assertion block precedes scenario dispatch in the real script ---
# Everything above runs an extracted fragment, which cannot prove where that fragment sits.
# If it moved below `case "$SCENARIO"`, only whichever scenario happened to fall through
# would be checked.
ASSERT_LINE=$(grep -n '^# --- Input artifact assertions' "$MOCK_AGENT" | cut -d: -f1)
DISPATCH_LINE=$(grep -n '^# --- Scenario dispatch ---' "$MOCK_AGENT" | cut -d: -f1)
PARSE_LINE=$(grep -n '^# --- Parse arguments ---' "$MOCK_AGENT" | cut -d: -f1)
[ -n "$ASSERT_LINE" ] && [ -n "$DISPATCH_LINE" ] && [ "$ASSERT_LINE" -lt "$DISPATCH_LINE" ] \
  && ok "assertions run before scenario dispatch, so every scenario is covered" \
  || fail "assertions run before scenario dispatch, so every scenario is covered"
[ -n "$PARSE_LINE" ] && [ "$ASSERT_LINE" -gt "$PARSE_LINE" ] \
  && ok "assertions run after argument parsing" || fail "assertions run after argument parsing"

# --- rate_limited: parks with a near-term reset, then succeeds on resume ---
# First invocation reports rate_limited; the resumed iteration sees session_id in
# config.json and completes. This is the only way E2E can exercise the park path,
# since a real quota event cannot be summoned on demand.
#
# Unlike the fragment-extraction tests above, rate_limited is run as a real
# subprocess (`bash "$MOCK" rate_limited`), because its behavior branches on
# /workspace/config.json content across two separate invocations (park, then
# resume) and calls send-callback directly. /workspace/config.json is swapped for
# a fixture path the same way test-check-prs.sh's Test 1+ swaps check-prs's config
# path (sed over a copy of the real script — Test 18's technique in
# test-config-parsing.sh), and send-callback is stubbed via a real executable on
# PATH standing in for the network the same way test-check-prs.sh stubs curl —
# rather than sourcing a fragment, so this exercises mock-agent.sh's real,
# unmodified control flow end to end.
#
# GNU date shim: mirrors test-quota-park.sh's Test 30 GNU_DATE_DIR technique, so
# the resume_at assertion below exercises the real `date -u -d "@epoch"` call the
# scenario makes (Linux/agent-image semantics) rather than silently no-op'ing on a
# BSD/macOS dev shell whose plain `date` lacks -d.
RL_GNU_DATE_DIR=""
if date -u -d "@0" '+%Y' >/dev/null 2>&1; then
  :
elif command -v gdate >/dev/null 2>&1 && gdate -u -d "@0" '+%Y' >/dev/null 2>&1; then
  RL_GNU_DATE_DIR="$TESTDIR/rl-gnu-date-bin"
  mkdir -p "$RL_GNU_DATE_DIR"
  ln -sf "$(command -v gdate)" "$RL_GNU_DATE_DIR/date"
fi

RL_BIN="$TESTDIR/rl_bin"
mkdir -p "$RL_BIN"
RL_CALLBACK_CAPTURE="$TESTDIR/rl_callback_body.json"
# Stub send-callback rather than curl: mock-agent.sh's own contract with it is a
# bare call with the JSON body as $1 (see send-callback's own header comment), so
# capturing at that boundary tests exactly what mock-agent.sh constructs without
# also having to reproduce send-callback's particular curl flag shape. Written to
# a capture file rather than echoed to stdout — jq -n's default output is
# pretty-printed (entrypoint.sh's own CALLBACK_BODY construction doesn't pass -c
# either, so this matches the real agent's formatting), and a file survives that
# multi-line body intact where scraping it back out of combined stdout/stderr
# would not. Same capture-file idiom as test-quota-park.sh's Test 30.
cat > "$RL_BIN/send-callback" <<STUB
#!/bin/bash
set -euo pipefail
printf '%s' "\$1" > "$RL_CALLBACK_CAPTURE"
STUB
chmod +x "$RL_BIN/send-callback"

RL_CONFIG="$TESTDIR/rl_config.json"
RL_OUT="$TESTDIR/rl_out"
RL_MOCK="$TESTDIR/mock-agent_rate_limited.sh"
# Also swaps /workspace/out (write_artifact's hardcoded target, used on the
# resumed/completed path below) to a fixture dir — the config.json swap alone
# only covers the park path's config read; the resume path's write_artifact call
# needs a writable target too, since this dev sandbox has no real /workspace.
sed -e "s#/workspace/config.json#$RL_CONFIG#g" -e "s#/workspace/out#$RL_OUT#g" \
  "$MOCK_AGENT" > "$RL_MOCK"
chmod +x "$RL_MOCK"

run_rate_limited() {
  # $1 = NODE_EXECUTION_ID. The real orchestrator invalidates the parked
  # execution and creates a fresh one for the resumed iteration (this task's
  # own brief), so the resumed call below uses a different id than the park
  # call — matching that, and also closing a real vacuity gap: mock-agent's
  # SESSION is derived deterministically from NODE_EXECUTION_ID alone, so
  # reusing the same id would let a broken "always re-park" implementation
  # coincidentally echo the same session text the original park used, passing
  # the "acknowledges the parked session" assertion below for the wrong reason.
  local rl_path="$RL_BIN:$PATH"
  [ -n "$RL_GNU_DATE_DIR" ] && rl_path="$RL_GNU_DATE_DIR:$rl_path"
  PATH="$rl_path" NODE_EXECUTION_ID="$1" RUN_ID="run-1" MOCK_RESUME_SECONDS=5 \
    bash "$RL_MOCK" rate_limited
}

# --- First iteration: config.json carries no session_id yet -> parks ---
echo '{}' > "$RL_CONFIG"
rm -f "$RL_CALLBACK_CAPTURE"
set +e
OUT=$(run_rate_limited exec-1 2>&1)
RC=$?
set -e
[ "$RC" -eq 0 ] && ok "rate_limited exits 0 when parking" \
  || fail "rate_limited exits 0 when parking (exit $RC: $OUT)"
[ -s "$RL_CALLBACK_CAPTURE" ] && ok "rate_limited sends a callback body" \
  || fail "rate_limited sends a callback body ($OUT)"
grep -q '"status": "rate_limited"' "$RL_CALLBACK_CAPTURE" \
  && ok "rate_limited reports the park status" \
  || fail "rate_limited reports the park status ($(cat "$RL_CALLBACK_CAPTURE" 2>/dev/null))"
grep -q '"resume_at": "20' "$RL_CALLBACK_CAPTURE" \
  && ok "rate_limited reports an RFC3339 resume_at" \
  || fail "rate_limited reports an RFC3339 resume_at ($(cat "$RL_CALLBACK_CAPTURE" 2>/dev/null))"
grep -q '"session_id":' "$RL_CALLBACK_CAPTURE" \
  && ok "rate_limited reports a session id" \
  || fail "rate_limited reports a session id ($(cat "$RL_CALLBACK_CAPTURE" 2>/dev/null))"

# The greps above would also pass against a body with unrelated fields mixed in,
# so parse the exact JSON send-callback received and check it field-for-field
# against the real agent's callback contract (entrypoint.sh's Step 6): all nine
# keys present, and non-null exactly where the real agent's park path is always
# non-null (status/resume_at/session_id), null where a mock never uploads a real
# transcript (session_artifact_path).
jq -e \
  '(.node_execution_id == "exec-1") and (.run_id == "run-1")
   and (.status == "rate_limited") and (.session_id | startswith("mock-session-"))
   and (.session_artifact_path == null) and (.error_message != null)
   and (.artifact_refs != null) and (.result != null)
   and (.resume_at | test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$"))' \
  "$RL_CALLBACK_CAPTURE" >/dev/null 2>&1 \
  && ok "rate_limited's callback body matches the real agent's field-for-field" \
  || fail "rate_limited's callback body matches the real agent's field-for-field ($(cat "$RL_CALLBACK_CAPTURE" 2>/dev/null))"

# --- Second iteration: config.json carries the session_id the first park sent ---
# (the same key entrypoint.sh's RESUME_SESSION_ID reads, and the same one
# activities.go writes back into the next iteration's config.json once
# params.SessionID is non-empty), so this run must complete rather than park again.
SESSION_ID=$(jq -r '.session_id // empty' "$RL_CALLBACK_CAPTURE" 2>/dev/null || true)
# Guard against the pattern going vacuous: an empty SESSION_ID would make the
# grep below match unconditionally (an empty pattern matches every line), so a
# broken park step that never produced a session id must fail here explicitly
# rather than let the resume check pass for the wrong reason.
[ -n "$SESSION_ID" ] && ok "the park step produced a non-empty session id to resume with" \
  || fail "the park step produced a non-empty session id to resume with"
jq -n --arg sid "$SESSION_ID" '{session_id: $sid, session_artifact_path: null}' > "$RL_CONFIG"
rm -f "$RL_CALLBACK_CAPTURE"
set +e
RESUME_OUT=$(run_rate_limited exec-2 2>&1)
RESUME_RC=$?
set -e
[ "$RESUME_RC" -eq 0 ] && ok "resumed rate_limited exits 0" \
  || fail "resumed rate_limited exits 0 (exit $RESUME_RC: $RESUME_OUT)"
if [ -n "$SESSION_ID" ] && echo "$RESUME_OUT" | grep -qF "$SESSION_ID"; then
  ok "resumed iteration acknowledges the parked session id"
else
  fail "resumed iteration acknowledges the parked session id (session_id='$SESSION_ID' out=$RESUME_OUT)"
fi
[ -e "$RL_CALLBACK_CAPTURE" ] \
  && fail "resumed iteration parked again instead of completing" \
  || ok "resumed iteration does not send a second rate_limited callback"
# -n "$SESSION_ID" guards the same way the earlier session-id checks do: an
# empty SESSION_ID would make grep -F's empty pattern match any non-empty file.
if [ -n "$SESSION_ID" ] && [ -s "$RL_OUT/result.txt" ] && grep -qF "$SESSION_ID" "$RL_OUT/result.txt"; then
  ok "resumed iteration writes a completion artifact naming the resumed session"
else
  fail "resumed iteration writes a completion artifact naming the resumed session"
fi

# --- Non-vacuity control: without a session_id, the same config still parks ---
# Proves the resumed-iteration assertion above is actually keyed on session_id
# being present, not just on this being the second call.
echo '{}' > "$RL_CONFIG"
rm -f "$RL_CALLBACK_CAPTURE"
set +e
run_rate_limited exec-3 >/dev/null 2>&1
set -e
[ -s "$RL_CALLBACK_CAPTURE" ] \
  && ok "a config.json with no session_id parks again (not a one-shot toggle)" \
  || fail "a config.json with no session_id parks again (not a one-shot toggle)"

# --- Summary ---
echo ""
echo "Results: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
