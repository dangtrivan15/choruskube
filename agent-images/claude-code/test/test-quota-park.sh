#!/bin/bash
# Unit tests for quota-lib.sh and the entrypoint's quota handling.
set -euo pipefail

PASS=0
FAIL=0
TESTDIR=$(mktemp -d)
trap 'rm -rf "$TESTDIR"' EXIT

ok() { echo "PASS: $1"; PASS=$((PASS+1)); }
fail() { echo "FAIL: $1"; FAIL=$((FAIL+1)); }

LIB="$(dirname "${BASH_SOURCE[0]}")/../quota-lib.sh"
# shellcheck source=/dev/null
source "$LIB"

# Fixtures. NOW_A is the reference incident time. NOW_B is late evening, which is
# the only window where a midnight rollover can still land inside the 6h bound --
# from any earlier hour, "tomorrow" is by definition more than 6h away. NOW_C is
# morning, so noon is both in the future and in bounds. NOW_D is noon on a day
# where 6pm and 6:01pm land at exactly +21600 and +21660 seconds respectively.
NOW_A=1787230680   # 2026-08-20T12:58:00Z
NOW_B=1787268600   # 2026-08-20T23:30:00Z
NOW_C=1787216400   # 2026-08-20T09:00:00Z
NOW_D=1787313600   # 2026-08-21T12:00:00Z
LIMIT_MSG="You've hit your session limit · resets 3:40pm (UTC)"

# --- Test 1: the observed message parses to the right instant (15:40Z, +2h42m) ---
GOT=$(quota_reset_at "$LIMIT_MSG" "$NOW_A") \
  && [ "$GOT" = "$((NOW_A + 9720))" ] \
  && ok "parses 3:40pm (UTC)" || fail "parses 3:40pm (UTC)"

# --- Test 2: whole-hour form with no minutes (14:00Z, +1h02m) ---
GOT=$(quota_reset_at "You've hit your session limit · resets 2pm (UTC)" "$NOW_A") \
  && [ "$GOT" = "$((NOW_A + 3720))" ] \
  && ok "parses the whole-hour form" || fail "parses the whole-hour form"

# --- Test 3: rollover past midnight, and 12am means 00:00 not 12:00 ---
GOT=$(quota_reset_at "You've hit your session limit · resets 12:15am (UTC)" "$NOW_B") \
  && [ "$GOT" = "$((NOW_B + 2700))" ] \
  && ok "rolls over past midnight, 12am is 00:00" || fail "rolls over past midnight, 12am is 00:00"

# --- Test 4: an ordinary hour after rollover ---
GOT=$(quota_reset_at "You've hit your session limit · resets 1:00am (UTC)" "$NOW_B") \
  && [ "$GOT" = "$((NOW_B + 5400))" ] \
  && ok "parses 1:00am after rollover" || fail "parses 1:00am after rollover"

# --- Test 5: 12pm means noon, not midnight ---
GOT=$(quota_reset_at "You've hit your session limit · resets 12pm (UTC)" "$NOW_C") \
  && [ "$GOT" = "$((NOW_C + 10800))" ] \
  && ok "12pm is noon" || fail "12pm is noon"

# --- Test 6: a non-UTC label must not park ---
# Parsing a local-time label as UTC would silently shift the wake time.
quota_reset_at "You've hit your session limit · resets 3:40pm (PDT)" "$NOW_A" >/dev/null 2>&1 \
  && fail "refuses a non-UTC timezone label" || ok "refuses a non-UTC timezone label"

# --- Test 7: beyond the 6h bound must not park (20:00Z, +7h02m) ---
quota_reset_at "You've hit your session limit · resets 8pm (UTC)" "$NOW_A" >/dev/null 2>&1 \
  && fail "refuses a reset beyond 6 hours" || ok "refuses a reset beyond 6 hours"

# --- Test 8: a rollover that lands beyond the bound must not park ---
# From 12:58, "11am" can only mean tomorrow, 22h out.
quota_reset_at "You've hit your session limit · resets 11:00am (UTC)" "$NOW_A" >/dev/null 2>&1 \
  && fail "refuses a rollover beyond the bound" || ok "refuses a rollover beyond the bound"

# --- Test 9: unrelated errors must not park ---
quota_reset_at "Error: connection reset by peer" "$NOW_A" >/dev/null 2>&1 \
  && fail "ignores an unrelated error" || ok "ignores an unrelated error"

# --- Test 10: the signature without a parseable time must not park ---
quota_reset_at "You've hit your session limit" "$NOW_A" >/dev/null 2>&1 \
  && fail "refuses the signature with no time" || ok "refuses the signature with no time"

# --- Test 11: a reset landing at exactly +21600s (the boundary) must park ---
# NOW_D is 12:00pm on 2026-08-21. 6:00pm is 6 hours later = 21600s exactly.
# Expected: $((NOW_D + 21600)) = $((1787313600 + 21600)) = 1787335200
GOT=$(quota_reset_at "You've hit your session limit · resets 6:00pm (UTC)" "$NOW_D") \
  && [ "$GOT" = "$((NOW_D + 21600))" ] \
  && ok "parks a reset at exactly +21600s" || fail "parks a reset at exactly +21600s"

# --- Test 12: a reset landing at +21660s (one minute over) must refuse ---
# NOW_D is 12:00pm on 2026-08-21. 6:01pm is 6 hours 1 minute later = 21660s.
# This is 60 seconds beyond the 21600s bound.
quota_reset_at "You've hit your session limit · resets 6:01pm (UTC)" "$NOW_D" >/dev/null 2>&1 \
  && fail "refuses a reset at +21660s" || ok "refuses a reset at +21660s"

# --- Test 13: exact token values are substituted out ---
export CLAUDE_CODE_OAUTH_TOKEN="sk-ant-oat01-EXAMPLEEXAMPLEEXAMPLE"
export GITHUB_TOKEN_FOR_REDACTION="ghs_EXAMPLE0000000000000000000000000000"
SRC="$TESTDIR/transcript.jsonl"
DST="$TESTDIR/redacted.jsonl"
cat > "$SRC" <<JSONL
{"type":"user","message":{"content":"token is $CLAUDE_CODE_OAUTH_TOKEN ok"}}
{"type":"assistant","message":{"content":"gh token $GITHUB_TOKEN_FOR_REDACTION here"}}
{"type":"assistant","message":{"content":"unrelated ghp_OTHER0000000000000000000000000000 value"}}
{"type":"result","result":"done"}
JSONL
redact_transcript "$SRC" "$DST"

grep -qF "$CLAUDE_CODE_OAUTH_TOKEN" "$DST" \
  && fail "oauth token removed" || ok "oauth token removed"
grep -qF "$GITHUB_TOKEN_FOR_REDACTION" "$DST" \
  && fail "github token removed" || ok "github token removed"
grep -q "ghp_OTHER" "$DST" \
  && fail "unknown github token shape scrubbed" || ok "unknown github token shape scrubbed"

# --- Test 14: redaction preserves valid JSONL, line for line ---
[ "$(wc -l < "$SRC")" = "$(wc -l < "$DST")" ] \
  && ok "line count preserved" || fail "line count preserved"
if jq -e . "$DST" >/dev/null 2>&1; then ok "every line still parses as JSON"; else fail "every line still parses as JSON"; fi
[ "$(jq -r 'select(.type=="result") | .result' "$DST")" = "done" ] \
  && ok "untouched content survives" || fail "untouched content survives"

# --- Test 15: no secret value reaches a process argument list ---
# A secret passed as a sed expression would be visible in `ps` to anything
# sharing the pod's PID namespace.
grep -qE 's\|\$(CLAUDE_CODE_OAUTH_TOKEN|GITHUB_TOKEN_FOR_REDACTION)' "$LIB" \
  && fail "secrets kept out of argv" || ok "secrets kept out of argv"
grep -q -- '-f "\$script"' "$LIB" \
  && ok "redaction uses a sed script file" || fail "redaction uses a sed script file"
unset CLAUDE_CODE_OAUTH_TOKEN GITHUB_TOKEN_FOR_REDACTION


# --- Test 16: failure path cleans up both script and partial output ---
export CLAUDE_CODE_OAUTH_TOKEN="sk-ant-oat01-EXAMPLEEXAMPLEEXAMPLE"
SCRATCH="$TESTDIR/failpath"
mkdir -p "$SCRATCH"
SRC_FAIL="$SCRATCH/missing.jsonl"
DST_FAIL="$SCRATCH/output.jsonl"

# Save and set TMPDIR to control where mktemp creates the script file
SAVED_TMPDIR="${TMPDIR:-}"
export TMPDIR="$SCRATCH"

# Call redact_transcript with missing source file (will fail)
if redact_transcript "$SRC_FAIL" "$DST_FAIL" >/dev/null 2>&1; then
  fail "failure path returns non-zero"
else
  ok "failure path returns non-zero"
fi

# Restore TMPDIR
if [ -n "$SAVED_TMPDIR" ]; then
  export TMPDIR="$SAVED_TMPDIR"
else
  unset TMPDIR
fi

# Positive control: verify the emptiness check actually detects leftover files.
# Plant a dummy file and assert it is detected, then clean up.
touch "$SCRATCH/dummy_test_file"
if [ -z "$(find "$SCRATCH" -type f 2>/dev/null)" ]; then
  fail "positive control: emptiness check detects files"
else
  ok "positive control: emptiness check detects files"
fi
rm -f "$SCRATCH/dummy_test_file"

# Assert: the scratch directory is now empty (no leaked sed script, no partial output file)
if [ -z "$(find "$SCRATCH" -type f 2>/dev/null)" ]; then
  ok "scratch directory is empty after failure"
else
  fail "scratch directory is empty after failure"
fi

# Assert: the output file specifically does not exist
if [ -f "$DST_FAIL" ]; then
  fail "output file does not exist after failure"
else
  ok "output file does not exist after failure"
fi

unset CLAUDE_CODE_OAUTH_TOKEN

ENTRYPOINT="$(dirname "${BASH_SOURCE[0]}")/../entrypoint.sh"

# --- Test 17: the entrypoint sources the library ---
grep -q 'source .*quota-lib.sh' "$ENTRYPOINT" \
  && ok "entrypoint sources quota-lib.sh" || fail "entrypoint sources quota-lib.sh"

# --- Test 18: a quota hit is detected off the result text ---
grep -q 'QUOTA_RESET_AT=$(quota_reset_at' "$ENTRYPOINT" \
  && ok "entrypoint detects a quota hit" || fail "entrypoint detects a quota hit"

# --- Test 19: every post-main retry loop is guarded on QUOTA_RESET_AT ---
# The reference failure spent all three attempts in 28 seconds because the
# artifact loop re-entered run_claude after the limit was already known.
GUARDED=$(grep -c 'z "\$QUOTA_RESET_AT" \] && \[ \$ATTEMPT -lt \$MAX_RETRIES' "$ENTRYPOINT" || true)
[ "$GUARDED" -eq 4 ] \
  && ok "all four retry loops guarded" || fail "all four retry loops guarded (found $GUARDED of 4)"

# --- Test 20: the quota reason is not overwritten by a later branch ---
grep -q 'ERROR_MESSAGE="Claude quota exhausted' "$ENTRYPOINT" \
  && ok "quota reason is set" || fail "quota reason is set"
grep -q 'if \[ -z "\$QUOTA_RESET_AT" \] && \[ -n "\$MISSING_FILES" \]' "$ENTRYPOINT" \
  && ok "artifact branch cannot overwrite the quota reason" \
  || fail "artifact branch cannot overwrite the quota reason"

# --- Test 21: the library ships in the image ---
grep -q 'COPY quota-lib.sh' "$(dirname "${BASH_SOURCE[0]}")/../Dockerfile" \
  && ok "quota-lib.sh is copied into the image" || fail "quota-lib.sh is copied into the image"

# --- Test 22: every post-detection failure-message `if` is guarded on QUOTA_RESET_AT ---
# Test 19 counts the four `while` loops; this counts a different set of lines --
# the four `if` blocks that assign RESULT_STATUS/ERROR_MESSAGE after them, at
# entrypoint.sh:850 (artifact), :875 (decision), :909 (escalation), and :953 (PR).
# Test 20 only asserts the artifact one by name; losing the guard on any of the
# other three would silently reopen the exact bug this task fixes -- a later
# phase's diagnostic overwriting the quota reason -- with the rest of the suite
# still green.
MSG_GUARDED=$(grep -c 'if \[ -z "\$QUOTA_RESET_AT" \] && ' "$ENTRYPOINT" || true)
[ "$MSG_GUARDED" -eq 4 ] \
  && ok "all four failure-message ifs guarded" \
  || fail "all four failure-message ifs guarded (found $MSG_GUARDED of 4)"

# --- Test 23: the errexit fallback pattern is itself errexit-safe (positive control) ---
# entrypoint.sh:796 assigns from quota_reset_at's refuse path under the entrypoint's
# own `set -euo pipefail`, tolerated only by the trailing `|| QUOTA_RESET_AT=""`.
# Reproduce that exact pattern in a fresh subshell with the same shell options and
# assert the subshell survives and the variable ends up empty.
#
# The subshell below is run as a bare statement, never as the direct condition of
# `if`/`&&`/`||` -- bash documents that when a compound command's return status is
# being tested that way, -e is ignored for every command inside it, EVEN ONE THAT
# re-enables -e internally. Wrapping it in `if (...); then` here would silently
# defeat the very thing this test is trying to exercise, so the exit status is
# captured with `set +e` / `set -e` around a standalone statement instead, and
# tested afterward with a plain `[ ... ]` that runs no risky command itself.
set +e
(
  set -euo pipefail
  source "$LIB"
  QUOTA_RESET_AT=$(quota_reset_at "an unrelated, non-quota error") || QUOTA_RESET_AT=""
  [ -z "$QUOTA_RESET_AT" ]
)
RC_POSITIVE=$?
set -e
if [ "$RC_POSITIVE" -eq 0 ]; then
  ok "errexit fallback pattern survives set -e and leaves the var empty"
else
  fail "errexit fallback pattern survives set -e and leaves the var empty"
fi

# --- Test 24: without the fallback, the same assignment kills the shell (negative control) ---
# Without this control, Test 23 passing would prove nothing about why the `||`
# fallback matters. This shows the fallback is load-bearing: dropping it aborts
# the whole script on every ordinary is_error result, not just quota ones --
# a regression worse than the bug this task fixes. Same bare-statement technique
# as Test 23, for the same reason: run inside `if (...); then` and bash's
# conditional-context exemption would swallow the very errexit this test proves.
set +e
(
  set -euo pipefail
  source "$LIB"
  QUOTA_RESET_AT=$(quota_reset_at "an unrelated, non-quota error")
  echo "unreachable: errexit did not fire"
)
RC_NEGATIVE=$?
set -e
if [ "$RC_NEGATIVE" -ne 0 ]; then
  ok "assignment without the fallback aborts under set -e"
else
  fail "assignment without the fallback aborts under set -e"
fi

# --- Test 25: the call site still carries the errexit fallback ---
# Structural counterpart to Tests 23/24: Test 18 only greps the
# `QUOTA_RESET_AT=$(quota_reset_at` prefix, so it would still pass even if the
# trailing `|| QUOTA_RESET_AT=""` at entrypoint.sh:796 were deleted.
grep -qF 'QUOTA_RESET_AT=$(quota_reset_at "${CLAUDE_RESULT:-}") || QUOTA_RESET_AT=""' "$ENTRYPOINT" \
  && ok "quota_reset_at call site keeps its errexit fallback" \
  || fail "quota_reset_at call site keeps its errexit fallback"

# --- Test 26: the transcript directory is discovered, not reconstructed ---
grep -q 'CLAUDE_PROJECT_DIR=\$(ls -d "\$HOME"/.claude/projects/\*' "$ENTRYPOINT" \
  && ok "transcript directory is discovered" || fail "transcript directory is discovered"

# --- Test 27: the uploaded copy is the redacted one, never the original ---
grep -q 'redact_transcript "\$TRANSCRIPT_SRC" "\$TRANSCRIPT_REDACTED"' "$ENTRYPOINT" \
  && ok "transcript is redacted before upload" || fail "transcript is redacted before upload"
grep -q 'artifact put "\$TRANSCRIPT_REDACTED" "\$SESSION_ARTIFACT_PATH"' "$ENTRYPOINT" \
  && ok "the redacted copy is what is uploaded" || fail "the redacted copy is what is uploaded"
grep -q 'artifact put "\$TRANSCRIPT_SRC"' "$ENTRYPOINT" \
  && fail "the raw transcript is never uploaded" || ok "the raw transcript is never uploaded"

# --- Test 28: the callback carries the park fields ---
for f in resume_at session_id session_artifact_path; do
  grep -q "$f:" "$ENTRYPOINT" \
    && ok "callback carries $f" || fail "callback carries $f"
done
grep -q 'RESULT_STATUS="rate_limited"' "$ENTRYPOINT" \
  && ok "callback status is rate_limited" || fail "callback status is rate_limited"

# --- Test 29: an upload failure still parks, just without a session ---
# Losing the session costs re-derivation; it must never cost the node.
grep -q 'SESSION_ARTIFACT_PATH=""  # upload failed' "$ENTRYPOINT" \
  && ok "upload failure degrades to a sessionless park" \
  || fail "upload failure degrades to a sessionless park"

# --- Test 30: the callback's null-vs-empty serialisation, exercised for real ---
# Tests 26-29 above are static greps over entrypoint.sh's source text; none of
# them ever run the code. This test extracts the actual `jq -n ...` filter
# from entrypoint.sh's Step 6 by marker (not hand-copied, so it cannot drift
# from what ships) and runs it for real, once with the park fields empty and
# once with them populated, asserting on the resulting JSON with `jq -e`
# rather than string-matching the serialised text -- a filter that always
# printed the literal word "null" would fool a text match but not this.
CALLBACK_CAPTURE="$TESTDIR/callback_body.json"

# entrypoint.sh's callback deliberately uses GNU `date -u -d "@epoch"` (the
# agent image is Linux); a BSD/macOS dev shell's plain `date` lacks -d. Prefer
# the system date if it already understands -d (true in CI/the agent image),
# else shim in Homebrew's coreutils `gdate` -- the identical GNU
# implementation used in the container, just under a different name -- so the
# positive case below exercises real GNU date semantics instead of being
# quietly skipped.
GNU_DATE_DIR=""
if date -u -d "@0" '+%Y' >/dev/null 2>&1; then
  :
elif command -v gdate >/dev/null 2>&1 && gdate -u -d "@0" '+%Y' >/dev/null 2>&1; then
  GNU_DATE_DIR="$TESTDIR/gnu-date-bin"
  mkdir -p "$GNU_DATE_DIR"
  ln -sf "$(command -v gdate)" "$GNU_DATE_DIR/date"
fi

build_callback_harness() {
  # $1 = QUOTA_RESET_AT, $2 = SESSION_ARTIFACT_PATH, $3 = CLAUDE_SESSION_ID.
  # send-callback is stubbed to capture its argument instead of POSTing it.
  {
    echo 'set -euo pipefail'
    printf 'send-callback() { printf %%s "$1" > %q; }\n' "$CALLBACK_CAPTURE"
    echo 'NODE_EXECUTION_ID=exec-1'
    echo 'RUN_ID=run-1'
    echo 'RESULT_STATUS=failed'
    echo 'RESULT=""'
    echo 'ARTIFACT_REFS="{}"'
    echo 'ERROR_MESSAGE="boom"'
    printf 'QUOTA_RESET_AT=%q\n' "$1"
    printf 'SESSION_ARTIFACT_PATH=%q\n' "$2"
    printf 'CLAUDE_SESSION_ID=%q\n' "$3"
    awk '/^# --- Step 6: POST callback to orchestrator ---/{f=1} f{print} /^send-callback "\$CALLBACK_BODY"/{f=0}' "$ENTRYPOINT"
  }
}

run_callback_harness() {
  if [ -n "$GNU_DATE_DIR" ]; then
    PATH="$GNU_DATE_DIR:$PATH" bash "$1" 2>"$TESTDIR/callback_run.err"
  else
    bash "$1" 2>"$TESTDIR/callback_run.err"
  fi
}

# 30a: negative case -- both park fields absent must serialise as JSON null,
# not the empty string the shell variables actually hold. A filter that just
# passed the empty string through (dropping the `if $x == "" then null` guard)
# would pass a text-matching test looking for the field name, but fails this.
build_callback_harness "" "" "" > "$TESTDIR/callback_neg.sh"
if run_callback_harness "$TESTDIR/callback_neg.sh" && jq -e \
    '(.resume_at == null) and (.session_id == null) and (.session_artifact_path == null)' \
    "$CALLBACK_CAPTURE" >/dev/null 2>&1; then
  ok "callback serialises absent park fields as JSON null"
else
  fail "callback serialises absent park fields as JSON null ($(cat "$CALLBACK_CAPTURE" 2>/dev/null) $(cat "$TESTDIR/callback_run.err" 2>/dev/null))"
fi

# 30b: positive case -- populated fields must be non-null AND carry the exact
# expected values. The negative case alone would pass against a filter that
# always emits null; this half alone would pass against a filter that always
# emits some fixed placeholder. Together they pin the real behaviour down.
QUOTA_EPOCH=1787230680
EXPECTED_RESUME_AT=$(date -u -d "@$QUOTA_EPOCH" '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null \
  || gdate -u -d "@$QUOTA_EPOCH" '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null || true)
SESSION_PATH="acme/runs/run-1/exec-1/session/sess-abc123.jsonl"
build_callback_harness "$QUOTA_EPOCH" "$SESSION_PATH" "sess-abc123" > "$TESTDIR/callback_pos.sh"
if [ -n "$EXPECTED_RESUME_AT" ] \
    && run_callback_harness "$TESTDIR/callback_pos.sh" \
    && jq -e \
      --arg ts "$EXPECTED_RESUME_AT" --arg sid "sess-abc123" --arg sp "$SESSION_PATH" \
      '(.resume_at == $ts) and (.session_id == $sid) and (.session_artifact_path == $sp)' \
      "$CALLBACK_CAPTURE" >/dev/null 2>&1; then
  ok "callback serialises populated park fields as their exact non-null values"
else
  fail "callback serialises populated park fields as their exact non-null values ($(cat "$CALLBACK_CAPTURE" 2>/dev/null) $(cat "$TESTDIR/callback_run.err" 2>/dev/null))"
fi

# --- Test 31: the park block survives every named failure mode, for real ---
# The property with no assertion at all until now: an unguarded failing
# command inside the quota-park block would abort the pod under
# `set -euo pipefail` *before the callback is ever sent* -- a worse outcome
# than the quota-hit bug this feature exists to fix. Extracts the real
# `# --- Quota park:` block by marker (the same technique
# test-config-parsing.sh already uses for its safety-net fragment, and the
# same boundary that fragment now stops at) and runs it with
# fetch-github-token, redact_transcript, and artifact stubbed as shell
# functions, once per failure mode, asserting the harness both survives
# (reaches a sentinel after the block) and leaves SESSION_ARTIFACT_PATH in the
# state that failure mode should produce.
build_park_harness() {
  # $1 = scenario tag, selecting which single stub fails and whether a
  # transcript file exists at the discovered path.
  local scenario="$1"
  local home_dir="$TESTDIR/park_home_$scenario"
  local proj_dir="$home_dir/.claude/projects/proj1"
  mkdir -p "$proj_dir"
  local sess="sess-$scenario"
  if [ "$scenario" != "no_transcript" ]; then
    echo '{"type":"result","result":"partial"}' > "$proj_dir/$sess.jsonl"
  fi

  {
    echo 'set -euo pipefail'
    printf 'export HOME=%q\n' "$home_dir"
    printf 'QUOTA_RESET_AT=%q\n' "1787230680"
    printf 'CLAUDE_SESSION_ID=%q\n' "$sess"
    printf 'OUTPUT_PATH=%q\n' "acme/runs/run-1/exec-1/out/"
    echo 'RESULT_STATUS=failed'

    if [ "$scenario" = "token_fetch_fail" ]; then
      echo 'fetch-github-token() { echo "token endpoint unreachable" >&2; return 1; }'
    else
      echo 'fetch-github-token() { echo "gh-token"; return 0; }'
    fi

    if [ "$scenario" = "redact_fail" ]; then
      echo 'redact_transcript() { echo "redaction failed" >&2; return 1; }'
    else
      echo 'redact_transcript() { cp "$1" "$2"; }'
    fi

    if [ "$scenario" = "upload_fail" ]; then
      echo 'artifact() { [ "$1" = "put" ] && { echo "presign failed" >&2; return 1; } || return 0; }'
    else
      echo 'artifact() { return 0; }'
    fi

    awk '/^# --- Quota park:/{f=1} /^# --- Step 5:/{f=0} f' "$ENTRYPOINT"
    echo 'echo "PARK_BLOCK_SENTINEL"'
    echo 'echo "SESSION_ARTIFACT_PATH_RESULT:${SESSION_ARTIFACT_PATH}"'
  }
}

run_park_scenario() {
  local scenario="$1" expect_session="$2"
  build_park_harness "$scenario" > "$TESTDIR/park_$scenario.sh"
  local out rc
  set +e
  out=$(bash "$TESTDIR/park_$scenario.sh" 2>&1)
  rc=$?
  set -e

  if [ "$rc" -eq 0 ] && printf '%s' "$out" | grep -q '^PARK_BLOCK_SENTINEL$'; then
    ok "park block survives: $scenario"
  else
    fail "park block survives: $scenario (exit=$rc, output: $out)"
  fi

  local got_path
  got_path=$(printf '%s\n' "$out" | sed -n 's/^SESSION_ARTIFACT_PATH_RESULT://p')
  if [ "$expect_session" = "empty" ]; then
    [ -z "$got_path" ] \
      && ok "SESSION_ARTIFACT_PATH stays empty: $scenario" \
      || fail "SESSION_ARTIFACT_PATH stays empty: $scenario (got '$got_path')"
  else
    [ -n "$got_path" ] \
      && ok "SESSION_ARTIFACT_PATH still carries a session: $scenario" \
      || fail "SESSION_ARTIFACT_PATH still carries a session: $scenario (got empty)"
  fi
}

# No transcript at the discovered path, redaction failing, and upload failing
# each cut the chain before a session reference is produced -- the node still
# parks (see Test 31's survival assertion), just without one.
run_park_scenario no_transcript empty
run_park_scenario redact_fail empty
run_park_scenario upload_fail empty
# fetch-github-token failing is deliberately NOT fatal to parking: the block
# continues with GITHUB_TOKEN_FOR_REDACTION empty (redact_transcript simply
# skips the exact-value GitHub-token substitution), and quota-lib.sh's
# shape-based scrub (Test 13) still catches common GitHub token shapes
# regardless of whether the exact value was ever fetched. The correct outcome
# here is therefore a *sessioned* park, not a sessionless one -- confirmed
# against a mutation that made a token-fetch failure wrongly skip parking
# entirely (see the fix-round-1 report for the mutation and its result).
run_park_scenario token_fetch_fail nonempty

# --- Test 32: the session reference is read from config.json ---
grep -q "RESUME_SESSION_ID=\$(jq -r '.session_id // empty'" "$ENTRYPOINT" \
  && ok "session_id read from config" || fail "session_id read from config"
grep -q "RESUME_SESSION_PATH=\$(jq -r '.session_artifact_path // empty'" "$ENTRYPOINT" \
  && ok "session_artifact_path read from config" || fail "session_artifact_path read from config"

# --- Test 33: the config reads actually parse a fixture, run for real (behavioral) ---
# Test 32 greps a fixed prefix, which would still pass if the jq filter after that
# prefix were subtly wrong (e.g. keyed on the wrong field, or missing "// empty").
# Extract the real two-line assignment by marker and execute it against fixture
# config.json files so a broken filter fails here even when the grep above still
# matches.
build_config_read_harness() {
  {
    echo 'set -euo pipefail'
    printf 'CONFIG_FILE=%q\n' "$1"
    awk '/^RESUME_SESSION_ID=\$\(jq/,/^RESUME_SESSION_PATH=\$\(jq/' "$ENTRYPOINT"
    echo 'echo "ID:$RESUME_SESSION_ID"'
    echo 'echo "PATH:$RESUME_SESSION_PATH"'
  }
}

RESUME_CONFIG="$TESTDIR/resume_config.json"
cat > "$RESUME_CONFIG" <<'EOF'
{"run_id":"r1","node_execution_id":"e1","prompt":"x","session_id":"sess-789","session_artifact_path":"acme/runs/r1/e1/session/sess-789.jsonl"}
EOF
build_config_read_harness "$RESUME_CONFIG" > "$TESTDIR/config_read.sh"
set +e
CONFIG_READ_OUT=$(bash "$TESTDIR/config_read.sh" 2>&1)
set -e
if echo "$CONFIG_READ_OUT" | grep -qF "ID:sess-789" && echo "$CONFIG_READ_OUT" | grep -qF "PATH:acme/runs/r1/e1/session/sess-789.jsonl"; then
  ok "session_id and session_artifact_path parse from config (behavioral)"
else
  fail "session_id and session_artifact_path parse from config (behavioral) (got: $CONFIG_READ_OUT)"
fi

RESUME_CONFIG_ABSENT="$TESTDIR/resume_config_absent.json"
cat > "$RESUME_CONFIG_ABSENT" <<'EOF'
{"run_id":"r1","node_execution_id":"e1","prompt":"x"}
EOF
build_config_read_harness "$RESUME_CONFIG_ABSENT" > "$TESTDIR/config_read_absent.sh"
set +e
CONFIG_READ_ABSENT_OUT=$(bash "$TESTDIR/config_read_absent.sh" 2>&1)
set -e
if echo "$CONFIG_READ_ABSENT_OUT" | grep -qx "ID:" && echo "$CONFIG_READ_ABSENT_OUT" | grep -qx "PATH:"; then
  ok "absent session fields read blank, not null (behavioral)"
else
  fail "absent session fields read blank, not null (behavioral) (got: $CONFIG_READ_ABSENT_OUT)"
fi

# --- Test 34: a restored session resumes instead of starting fresh ---
grep -q 'run_claude "\$RESUME_PROMPT" "--resume \$RESUME_SESSION_ID"' "$ENTRYPOINT" \
  && ok "restored session is resumed" || fail "restored session is resumed"

# --- Test 35: the parked object is consumed exactly once ---
# Overwrite, not delete: the presign endpoint allows only GET and PUT.
grep -q 'artifact put /tmp/empty_session "\$RESUME_SESSION_PATH"' "$ENTRYPOINT" \
  && ok "parked object is cleared after restore" || fail "parked object is cleared after restore"

# --- Test 36: a failed restore falls back to a fresh run ---
grep -q 'RESUME_SESSION_ID=""  # restore failed' "$ENTRYPOINT" \
  && ok "failed restore falls back to a fresh run" || fail "failed restore falls back to a fresh run"

# --- Test 37: Attempt 1 actually branches on RESUME_SESSION_ID, exercised for real (behavioral) ---
# Tests 34 and the fresh-path invocation (entrypoint.sh:773 historically) are both
# source-text greps; neither runs the branch. Extract the real "Attempt 1" block by
# marker and run it twice with run_claude stubbed to capture its arguments: once
# with a resume session set, once without. This also answers the self-review
# question of whether the fresh path still gets FULL_PROMPT/SYSTEM_PROMPT correctly
# now that the branch exists.
build_attempt1_harness() {
  # $1 = RESUME_SESSION_ID, $2 = FULL_PROMPT, $3 = SYSTEM_PROMPT, $4 = call-log path
  {
    echo 'set -euo pipefail'
    printf 'RESUME_SESSION_ID=%q\n' "$1"
    printf 'FULL_PROMPT=%q\n' "$2"
    printf 'SYSTEM_PROMPT=%q\n' "$3"
    printf 'CALL_LOG=%q\n' "$4"
    echo 'MAX_RETRIES=3'
    echo 'ATTEMPT=1'
    echo 'CLAUDE_SUBTYPE=""; CLAUDE_TURNS=""; CLAUDE_RESULT=""'
    echo 'run_claude() { { printf "ARG1:%s\n" "$1"; printf "ARG2:%s\n" "${2:-}"; printf "ARG3:%s\n" "${3:-}"; } >> "$CALL_LOG"; echo ""; }'
    echo 'parse_claude_output() { :; }'
    awk '/^  # Attempt 1:/{f=1} /^  # Retry loop:/{f=0} f' "$ENTRYPOINT"
  }
}

ATTEMPT1_LOG_RESUME="$TESTDIR/attempt1_resume.log"
build_attempt1_harness "sess-resume-1" "Run ID: r1

do the thing" "sys prompt text" "$ATTEMPT1_LOG_RESUME" > "$TESTDIR/attempt1_resume.sh"
set +e
bash "$TESTDIR/attempt1_resume.sh" >/dev/null 2>"$TESTDIR/attempt1_resume.err"
set -e
if [ -f "$ATTEMPT1_LOG_RESUME" ] \
    && grep -q '^ARG2:--resume sess-resume-1$' "$ATTEMPT1_LOG_RESUME" \
    && grep -q '^ARG3:$' "$ATTEMPT1_LOG_RESUME"; then
  ok "resume branch calls run_claude with --resume and no system prompt (behavioral)"
else
  fail "resume branch calls run_claude with --resume and no system prompt (behavioral) (log: $(cat "$ATTEMPT1_LOG_RESUME" 2>/dev/null))"
fi

ATTEMPT1_LOG_FRESH="$TESTDIR/attempt1_fresh.log"
build_attempt1_harness "" "Run ID: r1

do the thing" "sys prompt text" "$ATTEMPT1_LOG_FRESH" > "$TESTDIR/attempt1_fresh.sh"
set +e
bash "$TESTDIR/attempt1_fresh.sh" >/dev/null 2>"$TESTDIR/attempt1_fresh.err"
set -e
if [ -f "$ATTEMPT1_LOG_FRESH" ] \
    && grep -qF 'ARG1:Run ID: r1' "$ATTEMPT1_LOG_FRESH" \
    && grep -q '^ARG2:$' "$ATTEMPT1_LOG_FRESH" \
    && grep -qF 'ARG3:sys prompt text' "$ATTEMPT1_LOG_FRESH"; then
  ok "fresh path still calls run_claude with FULL_PROMPT and SYSTEM_PROMPT (behavioral)"
else
  fail "fresh path still calls run_claude with FULL_PROMPT and SYSTEM_PROMPT (behavioral) (log: $(cat "$ATTEMPT1_LOG_FRESH" 2>/dev/null))"
fi

# --- Test 38: the quota-resume restore block, exercised for real (behavioral) ---
# Mirrors the quota-park block harness (Test 31 above): extract the real
# "# --- Quota resume:" block by marker and run it with `artifact` stubbed as a
# shell function, once per scenario. Verifies four properties that no grep above
# can: the restore destination path is correct, the consume-once overwrite ships
# a genuinely empty file, a failed restore clears RESUME_SESSION_ID so Attempt 1
# takes the fresh path, and a failed clear does not undo a successful restore.
build_resume_harness() {
  # $1 = scenario, $2 = HOME dir, $3 = call-log path
  local scenario="$1" home_dir="$2" call_log="$3"
  {
    echo 'set -euo pipefail'
    printf 'export HOME=%q\n' "$home_dir"
    printf 'CALL_LOG=%q\n' "$call_log"

    if [ "$scenario" = "not_configured" ]; then
      printf 'RESUME_SESSION_ID=%q\n' ""
      printf 'RESUME_SESSION_PATH=%q\n' ""
    else
      printf 'RESUME_SESSION_ID=%q\n' "sess-resume-2"
      printf 'RESUME_SESSION_PATH=%q\n' "acme/runs/r1/e1/session/sess-resume-2.jsonl"
    fi

    if [ "$scenario" = "get_fails" ]; then
      echo 'artifact() { echo "artifact $*" >> "$CALL_LOG"; [ "$1" = "get" ] && return 1; return 0; }'
    elif [ "$scenario" = "put_fails" ]; then
      echo 'artifact() { echo "artifact $*" >> "$CALL_LOG"; if [ "$1" = "get" ]; then printf stub > "$3"; return 0; fi; [ "$1" = "put" ] && { echo "SIZE:$(wc -c < "$2" | tr -d " ")" >> "$CALL_LOG"; return 1; }; return 0; }'
    else
      echo 'artifact() { echo "artifact $*" >> "$CALL_LOG"; if [ "$1" = "get" ]; then printf stub > "$3"; return 0; fi; if [ "$1" = "put" ]; then echo "SIZE:$(wc -c < "$2" | tr -d " ")" >> "$CALL_LOG"; return 0; fi; return 0; }'
    fi

    awk '/^  # --- Quota resume:/{f=1} /^  # Attempt 1:/{f=0} f' "$ENTRYPOINT"
    echo 'echo "RESUME_BLOCK_SENTINEL"'
    echo 'echo "RESUME_SESSION_ID_RESULT:${RESUME_SESSION_ID}"'
  }
}

run_resume_scenario() {
  local scenario="$1"
  local home_dir="$TESTDIR/resume_home_$scenario"
  mkdir -p "$home_dir"
  local call_log="$TESTDIR/resume_calls_$scenario.log"
  : > "$call_log"
  build_resume_harness "$scenario" "$home_dir" "$call_log" > "$TESTDIR/resume_$scenario.sh"
  local out rc
  set +e
  out=$(bash "$TESTDIR/resume_$scenario.sh" 2>&1)
  rc=$?
  set -e
  # Newline-separated on purpose (not a single echo's space-joined args): the
  # script's own output can end mid-word, and joining it to $rc / the call log
  # with a bare space would silently glue two unrelated tokens onto one line.
  printf '%s\n---RC:%s---\n%s\n' "$out" "$rc" "$(cat "$call_log" 2>/dev/null)"
}

# 38a: no session parked for this run -- the block must be a complete no-op.
OUT_NOT_CONFIGURED=$(run_resume_scenario not_configured)
if echo "$OUT_NOT_CONFIGURED" | grep -q "RESUME_BLOCK_SENTINEL" \
    && ! echo "$OUT_NOT_CONFIGURED" | grep -q "^artifact "; then
  ok "resume block: no-op when nothing is parked"
else
  fail "resume block: no-op when nothing is parked (got: $OUT_NOT_CONFIGURED)"
fi

# 38b: successful restore -- correct destination path, consume-once ships zero bytes,
# and RESUME_SESSION_ID survives so Attempt 1 will take the resume branch.
OUT_SUCCESS=$(run_resume_scenario restore_success)
EXPECT_DEST="resume_home_restore_success/.claude/projects/-workspace-repo/sess-resume-2.jsonl"
if echo "$OUT_SUCCESS" | grep -q "RESUME_BLOCK_SENTINEL" \
    && echo "$OUT_SUCCESS" | grep -qF "artifact get acme/runs/r1/e1/session/sess-resume-2.jsonl" \
    && echo "$OUT_SUCCESS" | grep -qF "$EXPECT_DEST" \
    && echo "$OUT_SUCCESS" | grep -qF "artifact put /tmp/empty_session acme/runs/r1/e1/session/sess-resume-2.jsonl" \
    && echo "$OUT_SUCCESS" | grep -qF "SIZE:0" \
    && echo "$OUT_SUCCESS" | grep -q '^RESUME_SESSION_ID_RESULT:sess-resume-2$'; then
  ok "resume block: successful restore lands at the right path and clears the object with zero bytes"
else
  fail "resume block: successful restore lands at the right path and clears the object with zero bytes (got: $OUT_SUCCESS)"
fi

# 38c: restore fails -- RESUME_SESSION_ID must be cleared (fresh-path fallback), the
# object must NOT be touched (nothing to clear -- restore never happened), and the
# block must survive under set -euo pipefail rather than aborting the pod.
OUT_GET_FAILS=$(run_resume_scenario get_fails)
if echo "$OUT_GET_FAILS" | grep -q "RESUME_BLOCK_SENTINEL" \
    && echo "$OUT_GET_FAILS" | grep -q '^RESUME_SESSION_ID_RESULT:$' \
    && ! echo "$OUT_GET_FAILS" | grep -q "^artifact put"; then
  ok "resume block: failed restore falls back and never attempts to clear the object"
else
  fail "resume block: failed restore falls back and never attempts to clear the object (got: $OUT_GET_FAILS)"
fi

# 38d: restore succeeds but the consume-once clear fails -- the node must still
# resume (losing the clear costs a lingering parked object, not the node), and the
# block must survive.
OUT_PUT_FAILS=$(run_resume_scenario put_fails)
if echo "$OUT_PUT_FAILS" | grep -q "RESUME_BLOCK_SENTINEL" \
    && echo "$OUT_PUT_FAILS" | grep -q '^RESUME_SESSION_ID_RESULT:sess-resume-2$' \
    && echo "$OUT_PUT_FAILS" | grep -qF "SIZE:0"; then
  ok "resume block: a failed consume-once clear still lets the node resume"
else
  fail "resume block: a failed consume-once clear still lets the node resume (got: $OUT_PUT_FAILS)"
fi

echo
echo "PASS: $PASS  FAIL: $FAIL"
[ "$FAIL" -eq 0 ]
