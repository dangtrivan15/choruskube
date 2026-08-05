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

# --- Summary ---
echo ""
echo "Results: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
