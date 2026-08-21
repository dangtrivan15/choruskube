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
NONEXISTENT="$TESTDIR/does_not_exist.jsonl"
BADOUTPUT="$TESTDIR/bad_output.jsonl"
if redact_transcript "$NONEXISTENT" "$BADOUTPUT" >/dev/null 2>&1; then
  fail "failure path returns non-zero"
else
  ok "failure path returns non-zero"
fi
# Check that no sed script temp file was left in /tmp
if grep -r "EXAMPLEEXAMPLEEXAMPLE" /tmp 2>/dev/null | grep -q "sk-ant"; then
  fail "no secret-bearing script left in /tmp"
else
  ok "no secret-bearing script left in /tmp"
fi
# Check that no partial output file was left
if [ -f "$BADOUTPUT" ]; then
  fail "no partial output file left"
else
  ok "no partial output file left"
fi
unset CLAUDE_CODE_OAUTH_TOKEN
echo
echo "PASS: $PASS  FAIL: $FAIL"
[ "$FAIL" -eq 0 ]
