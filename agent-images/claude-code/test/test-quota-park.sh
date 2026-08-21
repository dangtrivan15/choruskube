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

echo
echo "PASS: $PASS  FAIL: $FAIL"
[ "$FAIL" -eq 0 ]
