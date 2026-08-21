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
# morning, so noon is both in the future and in bounds.
NOW_A=1787230680   # 2026-08-20T12:58:00Z
NOW_B=1787268600   # 2026-08-20T23:30:00Z
NOW_C=1787216400   # 2026-08-20T09:00:00Z
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

echo
echo "PASS: $PASS  FAIL: $FAIL"
[ "$FAIL" -eq 0 ]
