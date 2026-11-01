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
grep -q "Input artifact present: $IN_OK/step_1/step-1-done" <<< "$EXPECT_OUT" \
  && ok "present input artifact logs one confirmation line" \
  || fail "present input artifact logs one confirmation line ($EXPECT_OUT)"
grep -q "SCENARIO_REACHED" <<< "$EXPECT_OUT" \
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
grep -q "expected input artifact not found: $IN_MISSING/step_1/step-1-done" <<< "$EXPECT_OUT" \
  && ok "missing input artifact names the path it looked for" \
  || fail "missing input artifact names the path it looked for ($EXPECT_OUT)"
grep -q "unrelated.txt" <<< "$EXPECT_OUT" \
  && ok "missing input artifact lists what did arrive" \
  || fail "missing input artifact lists what did arrive ($EXPECT_OUT)"
grep -q "SCENARIO_REACHED" <<< "$EXPECT_OUT" \
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
grep -q "expected input artifact is empty: $IN_EMPTY/step_1/step-1-done" <<< "$EXPECT_OUT" \
  && ok "empty input artifact is reported as empty, not missing" \
  || fail "empty input artifact is reported as empty, not missing ($EXPECT_OUT)"

# --- Test 4: a missing /workspace/in directory is reported, not swallowed ---
run_expect_harness "$TESTDIR/in_absent" "step_1/step-1-done"
[ "$EXPECT_RC" -ne 0 ] && ok "absent input directory exits non-zero" \
  || fail "absent input directory exits non-zero"
grep -q "No such directory: $TESTDIR/in_absent" <<< "$EXPECT_OUT" \
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
grep -q "step_2/step-2-done" <<< "$EXPECT_OUT" \
  && ok "the failing path is the one named in the error" \
  || fail "the failing path is the one named in the error ($EXPECT_OUT)"

# --- Test 6: no --expect-input at all is a clean no-op ---
# The script runs under `set -euo pipefail`, where a bare "${ARR[@]}" over an empty array
# aborts on older bash. This is the assertion that keeps the empty-array-safe expansion
# (`${ARR[@]+"${ARR[@]}"}`, same idiom as entrypoint.sh's ADD_DIR_ARGS) in place.
run_expect_harness "$TESTDIR/in_absent"
[ "$EXPECT_RC" -eq 0 ] && ok "no --expect-input flags is a clean no-op" \
  || fail "no --expect-input flags is a clean no-op ($EXPECT_OUT)"
grep -q "SCENARIO_REACHED" <<< "$EXPECT_OUT" \
  && ok "no-op case still reaches the scenario dispatch" \
  || fail "no-op case still reaches the scenario dispatch ($EXPECT_OUT)"
grep -q "Input artifact present:" <<< "$EXPECT_OUT" \
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
# keys present, non-null where the real agent's park path is always non-null
# (status/resume_at/session_id/session_artifact_path), and — the property a
# review round caught missing here — session_id and session_artifact_path are
# COUPLED, not just independently present: entrypoint.sh:1191 derives session_id
# as "${SESSION_ARTIFACT_PATH:+$CLAUDE_SESSION_ID}", so a non-null session_id next
# to a null session_artifact_path is a combination the real agent can never
# produce. The biconditional below pins that; the exact-path check pins the mock
# to the real runs/<run>/<exec>/session/<session-id>.jsonl shape rather than any
# arbitrary non-null string.
jq -e \
  '(.node_execution_id == "exec-1") and (.run_id == "run-1")
   and (.status == "rate_limited") and (.session_id | startswith("mock-session-"))
   and ((.session_id == null) == (.session_artifact_path == null))
   and (.session_artifact_path == ("runs/" + .run_id + "/" + .node_execution_id + "/session/" + .session_id + ".jsonl"))
   and (.error_message != null)
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
SESSION_ARTIFACT_PATH=$(jq -r '.session_artifact_path // empty' "$RL_CALLBACK_CAPTURE" 2>/dev/null || true)
# Guard against the pattern going vacuous: an empty SESSION_ID would make the
# grep below match unconditionally (an empty pattern matches every line), so a
# broken park step that never produced a session id must fail here explicitly
# rather than let the resume check pass for the wrong reason.
[ -n "$SESSION_ID" ] && ok "the park step produced a non-empty session id to resume with" \
  || fail "the park step produced a non-empty session id to resume with"
# Carries the park step's ACTUAL session_artifact_path through, rather than
# hardcoding null — activities.go writes config.json's session_id and
# session_artifact_path verbatim from whatever the callback sent (both together
# or neither), so a fixture that hardcoded null here would itself model the same
# impossible combination this round's fix removed from the mock's own emission.
jq -n --arg sid "$SESSION_ID" --arg spath "$SESSION_ARTIFACT_PATH" \
  '{session_id: $sid, session_artifact_path: $spath}' > "$RL_CONFIG"
rm -f "$RL_CALLBACK_CAPTURE"
set +e
RESUME_OUT=$(run_rate_limited exec-2 2>&1)
RESUME_RC=$?
set -e
[ "$RESUME_RC" -eq 0 ] && ok "resumed rate_limited exits 0" \
  || fail "resumed rate_limited exits 0 (exit $RESUME_RC: $RESUME_OUT)"
if [ -n "$SESSION_ID" ] && grep -qF "$SESSION_ID" <<< "$RESUME_OUT"; then
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

# --- drift_recheck: a stale base on the first pass only ---
# The approval gate holds an approval when GitHub's compare says main moved past the
# recorded base. The E2E stack's WireMock calls a base behind main only when it matches
# the stale-base stub, so the mock's first-pass base must match that stub's pattern, and
# the base the entrypoint records on later passes must not. Run as a real subprocess
# with register-pr/record-base stubbed on PATH, same as rate_limited above.
DR_BIN="$TESTDIR/dr_bin"
DR_CONFIG="$TESTDIR/dr_config.json"
DR_OUT="$TESTDIR/dr_out"
DR_BASES="$TESTDIR/dr_bases"
DR_MOCK="$TESTDIR/mock-agent_drift_recheck.sh"
COMPARE_STUBS="$(dirname "${BASH_SOURCE[0]}")/../../../e2e/wiremock-stubs/github-compare.json"
mkdir -p "$DR_BIN"
cat > "$DR_BIN/register-pr" <<STUB
#!/bin/bash
echo "\$*" >> "$TESTDIR/dr_register.log"
STUB
cat > "$DR_BIN/record-base" <<STUB
#!/bin/bash
cat "\$REPO_BASE_DIR"/*.json > "$TESTDIR/dr_recorded.json"
STUB
chmod +x "$DR_BIN/register-pr" "$DR_BIN/record-base"
sed -e "s#/workspace/config.json#$DR_CONFIG#g" -e "s#/workspace/out#$DR_OUT#g" \
  "$MOCK_AGENT" > "$DR_MOCK"

run_drift_recheck() {
  # $1 = iteration. Leaves combined output in DR_RUN_OUT and the exit status in DR_RUN_RC.
  jq -n --argjson it "$1" \
    '{iteration: $it, repos: [{id: "repo-id-1", name: "mock-repo",
      url: "https://github.com/e2e-test/mock-repo", local_path: "/unused"}]}' > "$DR_CONFIG"
  rm -f "$TESTDIR/dr_register.log" "$TESTDIR/dr_recorded.json"
  set +e
  DR_RUN_OUT=$(PATH="$DR_BIN:$PATH" REPO_BASE_DIR="$DR_BASES" bash "$DR_MOCK" drift_recheck --pr-number 7001 2>&1)
  DR_RUN_RC=$?
  set -e
}

# The stale-base stub's own pattern, matched the way WireMock matches a whole path.
STALE_PATTERN=$(jq -r '.mappings[] | select(.response.body | contains("\"behind\"")) | .request.urlPathPattern' \
  "$COMPARE_STUBS" 2>/dev/null || true)
matches_stale_stub() {
  [ -n "$STALE_PATTERN" ] && grep -Eq "^(${STALE_PATTERN})\$" <<< "/repos/e2e-test/mock-repo/compare/$1...main"
}

# --- First pass: registers the PR and records a base the compare stub calls stale ---
run_drift_recheck 1
[ "$DR_RUN_RC" -eq 0 ] && ok "drift_recheck first pass exits 0" \
  || fail "drift_recheck first pass exits 0 ($DR_RUN_OUT)"
grep -q -- "--repo-id repo-id-1 .*--pr-number 7001" "$TESTDIR/dr_register.log" 2>/dev/null \
  && ok "drift_recheck first pass registers the fixture PR" \
  || fail "drift_recheck first pass registers the fixture PR ($(cat "$TESTDIR/dr_register.log" 2>/dev/null))"
DR_BASE=$(jq -r 'select(.gitRepoId == "repo-id-1") | .baseSha' "$TESTDIR/dr_recorded.json" 2>/dev/null || true)
[ "$(jq -r 'select(.gitRepoId == "repo-id-1") | .outcome' "$TESTDIR/dr_recorded.json" 2>/dev/null)" = "clean" ] \
  && ok "drift_recheck first pass records a clean base through record-base" \
  || fail "drift_recheck first pass records a clean base through record-base ($DR_RUN_OUT)"
[ "$(jq -r 'select(.gitRepoId == "repo-id-1") | .defaultBranch' "$TESTDIR/dr_recorded.json" 2>/dev/null)" = "main" ] \
  && ok "drift_recheck first pass records the default branch the stub compares against" \
  || fail "drift_recheck first pass records the default branch the stub compares against ($(cat "$TESTDIR/dr_recorded.json" 2>/dev/null))"
matches_stale_stub "$DR_BASE" \
  && ok "drift_recheck's first-pass base hits the compare stub that reports main ahead" \
  || fail "drift_recheck's first-pass base hits the compare stub that reports main ahead (base='$DR_BASE' pattern='$STALE_PATTERN')"
matches_stale_stub "0123456789abcdef0123456789abcdef01234567" \
  && fail "the stale-base compare stub also catches an ordinary recorded base" \
  || ok "the stale-base compare stub leaves an ordinary recorded base current"
grep -q "pass 1" "$DR_OUT/result.txt" 2>/dev/null \
  && ok "drift_recheck first pass writes its result artifact" \
  || fail "drift_recheck first pass writes its result artifact"

# --- Later pass: keeps the base the entrypoint recorded ---
mkdir -p "$DR_BASES"
echo '{"gitRepoId":"repo-id-1","outcome":"clean","baseSha":"0123456789abcdef0123456789abcdef01234567","defaultBranch":"main","conflictedFiles":[]}' \
  > "$DR_BASES/mock-repo.json"
run_drift_recheck 2
[ "$DR_RUN_RC" -eq 0 ] && ok "drift_recheck later pass exits 0" \
  || fail "drift_recheck later pass exits 0 ($DR_RUN_OUT)"
[ ! -e "$TESTDIR/dr_register.log" ] && ok "drift_recheck later pass registers no second PR" \
  || fail "drift_recheck later pass registers no second PR"
[ ! -e "$TESTDIR/dr_recorded.json" ] && ok "drift_recheck later pass does not re-record a base" \
  || fail "drift_recheck later pass does not re-record a base"
[ "$(jq -r .baseSha "$DR_BASES/mock-repo.json")" = "0123456789abcdef0123456789abcdef01234567" ] \
  && ok "drift_recheck later pass leaves the entrypoint's record alone" \
  || fail "drift_recheck later pass leaves the entrypoint's record alone"
grep -q "pass 2" "$DR_OUT/result.txt" 2>/dev/null \
  && ok "drift_recheck later pass writes its result artifact" \
  || fail "drift_recheck later pass writes its result artifact"

# --- Pushes mirror the real agent's contract ---
# The entrypoint may rebase a run branch before the scenario runs; a plain push is then rejected.
if grep -nE '^[[:space:]]*git push' "$MOCK_AGENT" | grep -v -- '--force-with-lease' >/dev/null; then
  fail "every mock push uses --force-with-lease"
else
  ok "every mock push uses --force-with-lease"
fi

# --- Story Design / Task Delivery scenarios ---
# Run as a real subprocess like drift_recheck above: config.json and /workspace/out are swapped
# for fixture paths, $WORKSPACE_IN points at a fake input root, and report-result is a stub that
# records the decision the scenario submitted. The environment is a script node's: the
# entrypoint exports RUN_ID, NODE_EXECUTION_ID and API_SERVER_URL, but REPORT_RESULT_URL only
# for AI nodes, and the real report-result refuses to run without it — as the stub does.
SD_ROOT="$TESTDIR/sd"
SD_BIN="$SD_ROOT/bin"
SD_IN="$SD_ROOT/in"
SD_OUT="$SD_ROOT/out"
SD_CONFIG="$SD_ROOT/config.json"
SD_DECISION="$SD_ROOT/decision"
SD_DECISION_URL="$SD_ROOT/decision_url"
SD_MOCK="$TESTDIR/mock-agent_sd.sh"
mkdir -p "$SD_BIN"
cat > "$SD_BIN/report-result" <<STUB
#!/bin/bash
[ -n "\${REPORT_RESULT_URL:-}" ] || { echo "ERROR: REPORT_RESULT_URL not set" >&2; exit 1; }
printf '%s' "\$1" > "$SD_DECISION"
printf '%s' "\$REPORT_RESULT_URL" > "$SD_DECISION_URL"
STUB
chmod +x "$SD_BIN/report-result"
sed -e "s#/workspace/config.json#$SD_CONFIG#g" -e "s#/workspace/out#$SD_OUT#g" "$MOCK_AGENT" > "$SD_MOCK"

run_sd() {
  # $@ = scenario and its options. Leaves output in SD_RUN_OUT, exit status in SD_RUN_RC, and
  # the submitted decision (empty when none) in SD_RUN_DECISION.
  rm -rf "$SD_OUT" "$SD_DECISION" "$SD_DECISION_URL"
  set +e
  SD_RUN_OUT=$(env -u REPORT_RESULT_URL PATH="$SD_BIN:$PATH" WORKSPACE_IN="$SD_IN" \
    API_SERVER_URL="http://api.test" RUN_ID="run-1" NODE_EXECUTION_ID="exec-1" \
    bash "$SD_MOCK" "$@" 2>&1)
  SD_RUN_RC=$?
  set -e
  SD_RUN_DECISION=$(cat "$SD_DECISION" 2>/dev/null || true)
}

reset_sd_inputs() {
  rm -rf "$SD_IN"
  mkdir -p "$SD_IN"
  echo '{}' > "$SD_CONFIG"
}

STORY_SECTIONS='## Context
## Approach
## Architecture
## Technical Decisions
## Caveats
## Testing Strategy
## Manual Operations
## Tasks
## Revisions'

# --- story_spec_draft: a first design writes the full spec with an empty Revisions log ---
reset_sd_inputs
run_sd story_spec_draft
[ "$SD_RUN_RC" -eq 0 ] && ok "story_spec_draft exits 0 on a first design" \
  || fail "story_spec_draft exits 0 on a first design ($SD_RUN_OUT)"
[ "$(grep '^## ' "$SD_OUT/story_spec.md" 2>/dev/null)" = "$STORY_SECTIONS" ] \
  && ok "story_spec_draft writes the nine spec sections in order" \
  || fail "story_spec_draft writes the nine spec sections in order ($(grep '^## ' "$SD_OUT/story_spec.md" 2>/dev/null))"
[ "$(tail -n 1 "$SD_OUT/story_spec.md" 2>/dev/null)" = "No revisions yet." ] \
  && ok "a first design's Revisions section reads 'No revisions yet.'" \
  || fail "a first design's Revisions section reads 'No revisions yet.'"
[ -z "$SD_RUN_DECISION" ] && ok "story_spec_draft submits no decision (its edge is unconditional)" \
  || fail "story_spec_draft submits no decision (got '$SD_RUN_DECISION')"
cp "$SD_OUT/story_spec.md" "$TESTDIR/spec_v1.md" 2>/dev/null || true

# --- story_spec_draft: a revision edits the staged spec and appends one Revisions entry ---
reset_sd_inputs
mkdir -p "$SD_IN/run_input"
cp "$TESTDIR/spec_v1.md" "$SD_IN/run_input/story_spec.md" 2>/dev/null || true
run_sd story_spec_draft
TODAY=$(date -u +%F)
[ "$SD_RUN_RC" -eq 0 ] && ok "story_spec_draft exits 0 on a revision" \
  || fail "story_spec_draft exits 0 on a revision ($SD_RUN_OUT)"
grep -qx "### v2 — $TODAY" "$SD_OUT/story_spec.md" 2>/dev/null \
  && ok "the first revision adds a v2 entry dated today" \
  || fail "the first revision adds a v2 entry dated today ($(tail -n 5 "$SD_OUT/story_spec.md" 2>/dev/null))"
grep -qx "No revisions yet." "$SD_OUT/story_spec.md" 2>/dev/null \
  && fail "the first revision replaces 'No revisions yet.'" \
  || ok "the first revision replaces 'No revisions yet.'"
V1_KEPT=$(grep -vx 'No revisions yet.' "$TESTDIR/spec_v1.md" || true)
V1_KEPT_LINES=$(printf '%s\n' "$V1_KEPT" | wc -l | tr -d ' ')
[ -n "$V1_KEPT" ] && [ "$(head -n "$V1_KEPT_LINES" "$SD_OUT/story_spec.md" 2>/dev/null)" = "$V1_KEPT" ] \
  && ok "the first revision keeps every other line of the current spec, in place" \
  || fail "the first revision keeps every other line of the current spec, in place"
[ "$(grep '^## ' "$SD_OUT/story_spec.md" 2>/dev/null)" = "$STORY_SECTIONS" ] \
  && ok "a revision keeps the nine spec sections in order" \
  || fail "a revision keeps the nine spec sections in order"
cp "$SD_OUT/story_spec.md" "$TESTDIR/spec_v2.md" 2>/dev/null || true

cp "$TESTDIR/spec_v2.md" "$SD_IN/run_input/story_spec.md" 2>/dev/null || true
run_sd story_spec_draft
[ "$(grep '^### v[0-9]' "$SD_OUT/story_spec.md" 2>/dev/null)" = "### v2 — $TODAY
### v3 — $TODAY" ] \
  && ok "a second revision appends v3 after the v2 entry" \
  || fail "a second revision appends v3 after the v2 entry ($(grep '^### v' "$SD_OUT/story_spec.md" 2>/dev/null))"

# --- story_spec_review: approves the drafter's spec unchanged ---
reset_sd_inputs
mkdir -p "$SD_IN/draft_story_spec"
cp "$TESTDIR/spec_v1.md" "$SD_IN/draft_story_spec/story_spec.md" 2>/dev/null || true
run_sd story_spec_review
[ "$SD_RUN_RC" -eq 0 ] && ok "story_spec_review exits 0" \
  || fail "story_spec_review exits 0 ($SD_RUN_OUT)"
cmp -s "$SD_OUT/story_spec.md" "$TESTDIR/spec_v1.md" \
  && ok "story_spec_review writes the drafter's spec as the reviewed copy" \
  || fail "story_spec_review writes the drafter's spec as the reviewed copy"
grep -qx "## Roadmap proposal" "$SD_OUT/story_spec_review.md" 2>/dev/null \
  && ok "story_spec_review writes review notes with a Roadmap proposal section" \
  || fail "story_spec_review writes review notes with a Roadmap proposal section"
[ "$SD_RUN_DECISION" = "approved" ] && ok "story_spec_review submits approved" \
  || fail "story_spec_review submits approved (got '$SD_RUN_DECISION')"

reset_sd_inputs
run_sd story_spec_review
[ "$SD_RUN_RC" -ne 0 ] && [ -z "$SD_RUN_DECISION" ] \
  && ok "story_spec_review without a drafted spec fails before deciding" \
  || fail "story_spec_review without a drafted spec fails before deciding (rc=$SD_RUN_RC decision='$SD_RUN_DECISION')"

# --- analyze: the Task title's marker picks the decision and the files that go with it ---
run_analyze() {
  # $1 = iteration, $2 = Task title, $3 = prompt
  reset_sd_inputs
  mkdir -p "$SD_IN/run_input"
  cp "$TESTDIR/spec_v1.md" "$SD_IN/run_input/story_spec.md" 2>/dev/null || true
  jq -n --argjson it "$1" --arg title "$2" --arg prompt "$3" \
    '{iteration: $it, prompt: $prompt, task_context: {task_id: "task-1", task_title: $title}}' > "$SD_CONFIG"
  run_sd analyze
}

assert_analyze() {
  # $1 = case name, $2 = expected decision, $3 = expected extra file ("" for none)
  [ "$SD_RUN_RC" -eq 0 ] && ok "analyze $1: exits 0" || fail "analyze $1: exits 0 ($SD_RUN_OUT)"
  [ "$SD_RUN_DECISION" = "$2" ] && ok "analyze $1: submits $2" \
    || fail "analyze $1: submits $2 (got '$SD_RUN_DECISION')"
  [ "$(head -n 1 "$SD_OUT/plan.md" 2>/dev/null)" = "Realises: Mock component, Runtime Flow, Which path does the mock take" ] \
    && ok "analyze $1: plan.md opens with the spec's Realises headings" \
    || fail "analyze $1: plan.md opens with the spec's Realises headings ($(head -n 1 "$SD_OUT/plan.md" 2>/dev/null))"
  local f
  for f in task_spec.md story_conflict.md; do
    if [ "$f" = "$3" ]; then
      [ -s "$SD_OUT/$f" ] && ok "analyze $1: writes $f" || fail "analyze $1: writes $f"
    else
      [ ! -e "$SD_OUT/$f" ] && ok "analyze $1: writes no $f" || fail "analyze $1: writes no $f"
    fi
  done
}

run_analyze 1 "Plain Task" ""
assert_analyze "with no marker" planned ""
run_analyze 1 "Task [e2e-analyze:planned]" ""
assert_analyze "with the planned marker" planned ""
run_analyze 1 "Task [e2e-analyze:needs_spec]" ""
assert_analyze "with the needs_spec marker" needs_spec task_spec.md
run_analyze 1 "Task [e2e-analyze:story_conflict]" ""
assert_analyze "with the story_conflict marker" story_conflict story_conflict.md
run_analyze 1 "Plain Task" "## Task [e2e-analyze:needs_spec]"
assert_analyze "with the marker in the prompt" needs_spec task_spec.md
run_analyze 2 "Task [e2e-analyze:story_conflict]" ""
assert_analyze "on iteration 2" planned ""

run_analyze 1 "Task [e2e-analyze:replan]" ""
[ "$SD_RUN_RC" -ne 0 ] && [ -z "$SD_RUN_DECISION" ] \
  && ok "analyze with an unknown marker fails before deciding" \
  || fail "analyze with an unknown marker fails before deciding (rc=$SD_RUN_RC decision='$SD_RUN_DECISION')"

run_analyze 1 "Plain Task" ""
rm -f "$SD_IN/run_input/story_spec.md"
run_sd analyze
[ "$SD_RUN_RC" -ne 0 ] && [ -z "$SD_RUN_DECISION" ] \
  && ok "analyze without the story spec fails before deciding" \
  || fail "analyze without the story spec fails before deciding (rc=$SD_RUN_RC decision='$SD_RUN_DECISION')"

# --- task_spec_review: approves the plan and Task spec unchanged ---
reset_sd_inputs
mkdir -p "$SD_IN/plan"
printf 'Realises: Mock component\n\n## Task ordering\n' > "$SD_IN/plan/plan.md"
printf '## What this adds to the Story spec\n\nA mock gap.\n' > "$SD_IN/plan/task_spec.md"
run_sd task_spec_review
[ "$SD_RUN_RC" -eq 0 ] && ok "task_spec_review exits 0" \
  || fail "task_spec_review exits 0 ($SD_RUN_OUT)"
cmp -s "$SD_OUT/plan.md" "$SD_IN/plan/plan.md" && cmp -s "$SD_OUT/task_spec.md" "$SD_IN/plan/task_spec.md" \
  && ok "task_spec_review writes the plan and Task spec it reviewed" \
  || fail "task_spec_review writes the plan and Task spec it reviewed"
[ -s "$SD_OUT/task_spec_review.md" ] && ok "task_spec_review writes review notes" \
  || fail "task_spec_review writes review notes"
[ "$SD_RUN_DECISION" = "approved" ] && ok "task_spec_review submits approved" \
  || fail "task_spec_review submits approved (got '$SD_RUN_DECISION')"
# A script node's environment has no REPORT_RESULT_URL; the decision must still reach this
# execution's own endpoint.
[ "$(cat "$SD_DECISION_URL" 2>/dev/null)" = "http://api.test/internal/runs/run-1/node-executions/exec-1/decision" ] \
  && ok "a script node's decision goes to its own execution's decision endpoint" \
  || fail "a script node's decision goes to its own execution's decision endpoint (got '$(cat "$SD_DECISION_URL" 2>/dev/null)')"

rm "$SD_IN/plan/task_spec.md"
run_sd task_spec_review
[ "$SD_RUN_RC" -ne 0 ] && [ -z "$SD_RUN_DECISION" ] \
  && ok "task_spec_review without a Task spec fails before deciding" \
  || fail "task_spec_review without a Task spec fails before deciding (rc=$SD_RUN_RC decision='$SD_RUN_DECISION')"

# --- gate_approve --artifact: Code Review's mock writes the file Final Approval requires ---
reset_sd_inputs
run_sd gate_approve --artifact review.md
[ "$SD_RUN_DECISION" = "approved" ] && [ -s "$SD_OUT/review.md" ] && [ ! -e "$SD_OUT/result.txt" ] \
  && ok "gate_approve --artifact names the file it writes" \
  || fail "gate_approve --artifact names the file it writes (decision='$SD_RUN_DECISION' $(ls "$SD_OUT" 2>/dev/null))"
run_sd gate_approve
[ -s "$SD_OUT/result.txt" ] && ok "gate_approve still writes result.txt by default" \
  || fail "gate_approve still writes result.txt by default"

# --- Summary ---
echo ""
echo "Results: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
