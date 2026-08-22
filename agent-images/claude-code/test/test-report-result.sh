#!/bin/bash
# Unit tests for report-result (no live container needed).
#
# report-result is the only way an agent writes a routing decision, and since the
# decision is written the moment it is called — while the node keeps running — it is
# also the only way an agent takes one back. Both paths are pure branching over an
# HTTP call, which nothing else in the suite exercises: mock-agent.sh calls the real
# endpoint against a live API server (so it can't run here) and only on the happy
# path.
#
# Same technique as test-check-prs.sh: run the real, unmodified script with a stub
# `curl` prepended onto PATH. The stub records the argv it was called with, so a test
# can assert on the HTTP method actually used rather than on the script's own output.
set -euo pipefail

PASS=0
FAIL=0
TESTDIR=$(mktemp -d)
trap 'rm -rf "$TESTDIR"' EXIT

ok() { echo "PASS: $1"; PASS=$((PASS+1)); }
fail() { echo "FAIL: $1"; FAIL=$((FAIL+1)); }

SCRIPT="$(dirname "${BASH_SOURCE[0]}")/../report-result"

FAKE_BIN="$TESTDIR/bin"
mkdir -p "$FAKE_BIN"
ARGV_LOG="$TESTDIR/curl_argv"

# Mirrors report-result's own `curl -s -w "\n%{http_code}" ...` output shape: body,
# newline, status code. CURL_STUB_BODY / CURL_STUB_HTTP_CODE control the response.
cat >"$FAKE_BIN/curl" <<STUB
#!/bin/bash
printf '%s\n' "\$*" >>"$ARGV_LOG"
printf '%s\n%s' "\${CURL_STUB_BODY:-{\}}" "\${CURL_STUB_HTTP_CODE:-200}"
STUB
chmod +x "$FAKE_BIN/curl"

run_report_result() {
    : >"$ARGV_LOG"
    set +e
    OUT=$(PATH="$FAKE_BIN:$PATH" \
        REPORT_RESULT_URL="http://api.example/internal/runs/r/node-executions/n/decision" \
        JOB_SECRET="test-secret" \
        bash "$SCRIPT" "$@" 2>&1)
    RC=$?
    set -e
}

# --- Test 1: submitting a decision still PUTs it ---
CURL_STUB_BODY='{"decision":"approved"}' run_report_result approved
[ "$RC" -eq 0 ] && ok "submit: exits 0" || fail "submit: exits 0 (got $RC: $OUT)"
grep -qF -- "-X PUT" "$ARGV_LOG" && ok "submit: uses PUT" || fail "submit: uses PUT (argv: $(cat "$ARGV_LOG"))"
echo "$OUT" | grep -qF "approved" && ok "submit: names the decision" || fail "submit: names the decision (got: $OUT)"

# --- Test 2: --withdraw retracts via DELETE and names what was withdrawn ---
CURL_STUB_BODY='{"decision":"escalate"}' run_report_result --withdraw
[ "$RC" -eq 0 ] && ok "withdraw: exits 0" || fail "withdraw: exits 0 (got $RC: $OUT)"
grep -qF -- "-X DELETE" "$ARGV_LOG" && ok "withdraw: uses DELETE" || fail "withdraw: uses DELETE (argv: $(cat "$ARGV_LOG"))"
echo "$OUT" | grep -qF "escalate" && ok "withdraw: names the withdrawn decision" || fail "withdraw: names the withdrawn decision (got: $OUT)"

# --- Test 3: --withdraw with nothing outstanding is a success, not an error. The
# agent is told to withdraw when it resolves a blocker itself; making it check first
# whether it ever submitted would be a second round trip it can get wrong. ---
CURL_STUB_BODY='{}' run_report_result --withdraw
[ "$RC" -eq 0 ] && ok "withdraw with nothing outstanding: exits 0" || fail "withdraw with nothing outstanding: exits 0 (got $RC: $OUT)"

# --- Test 4: a non-200 from the API server fails loudly ---
CURL_STUB_HTTP_CODE=400 CURL_STUB_BODY='{"detail":"Invalid decision"}' run_report_result bogus
[ "$RC" -eq 1 ] && ok "API rejects the decision: exits 1" || fail "API rejects the decision: exits 1 (got $RC)"
echo "$OUT" | grep -qF "Invalid decision" && ok "API rejects the decision: surfaces the body" || fail "API rejects the decision: surfaces the body (got: $OUT)"

# --- Test 5: a non-200 on withdrawal fails loudly too ---
CURL_STUB_HTTP_CODE=404 CURL_STUB_BODY='{"detail":"Node execution not found"}' run_report_result --withdraw
[ "$RC" -eq 1 ] && ok "withdraw rejected: exits 1" || fail "withdraw rejected: exits 1 (got $RC)"

# --- Test 6: no argument at all — usage, exit 1, no HTTP call ---
run_report_result
[ "$RC" -eq 1 ] && ok "no argument: exits 1" || fail "no argument: exits 1 (got $RC)"
[ ! -s "$ARGV_LOG" ] && ok "no argument: makes no HTTP call" || fail "no argument: makes no HTTP call"
echo "$OUT" | grep -qF "Usage:" && ok "no argument: prints usage" || fail "no argument: prints usage (got: $OUT)"

# --- Test 7: REPORT_RESULT_URL unset — fails loudly before any HTTP call, on both paths ---
for ARG in approved --withdraw; do
    : >"$ARGV_LOG"
    set +e
    OUT=$(PATH="$FAKE_BIN:$PATH" JOB_SECRET="s" env -u REPORT_RESULT_URL bash "$SCRIPT" "$ARG" 2>&1)
    RC=$?
    set -e
    [ "$RC" -eq 1 ] && ok "missing REPORT_RESULT_URL ($ARG): exits 1" || fail "missing REPORT_RESULT_URL ($ARG): exits 1 (got $RC)"
    echo "$OUT" | grep -qF "REPORT_RESULT_URL not set" \
        && ok "missing REPORT_RESULT_URL ($ARG): clear diagnostic" || fail "missing REPORT_RESULT_URL ($ARG): clear diagnostic (got: $OUT)"
done

# --- Summary ---
echo ""
echo "Results: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
