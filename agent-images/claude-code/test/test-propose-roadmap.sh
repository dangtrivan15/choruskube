#!/bin/bash
# Unit tests for propose-roadmap (no live container needed).
#
# Same technique as test-report-result.sh: run the real, unmodified script with a
# stub `curl` prepended onto PATH, controlled via CURL_STUB_* env vars, recording
# the argv it was called with so assertions can check the HTTP method, URL and
# headers actually used. Since this script also writes a real file under
# /workspace/out (the literal path it hardcodes — there is no WORKSPACE_OUT env
# var), its source is run through the sed substitution test-mock-agent.sh uses for
# the same reason, redirecting that write to a temp dir for the duration of the test.
set -euo pipefail

PASS=0
FAIL=0
TESTDIR=$(mktemp -d)
trap 'rm -rf "$TESTDIR"' EXIT

ok() { echo "PASS: $1"; PASS=$((PASS+1)); }
fail() { echo "FAIL: $1"; FAIL=$((FAIL+1)); }

SRC="$(dirname "${BASH_SOURCE[0]}")/../propose-roadmap"

RL_OUT="$TESTDIR/rl_out"
RL_SCRIPT="$TESTDIR/propose-roadmap"
sed -e "s#/workspace/out#$RL_OUT#g" "$SRC" > "$RL_SCRIPT"
chmod +x "$RL_SCRIPT"

FAKE_BIN="$TESTDIR/bin"
mkdir -p "$FAKE_BIN"
ARGV_LOG="$TESTDIR/curl_argv"

# Mirrors propose-roadmap's own `curl -s -w "\n%{http_code}" ...` output shape:
# body, newline, status code. CURL_STUB_BODY / CURL_STUB_HTTP_CODE control the
# response; absent the env vars a stub call is still recorded so "no network call"
# assertions can tell a skipped call from an unset default.
cat >"$FAKE_BIN/curl" <<STUB
#!/bin/bash
printf '%s\n' "\$*" >>"$ARGV_LOG"
printf '%s\n%s' "\${CURL_STUB_BODY:-{\}}" "\${CURL_STUB_HTTP_CODE:-200}"
exit "\${CURL_STUB_EXIT:-0}"
STUB
chmod +x "$FAKE_BIN/curl"

run_propose_roadmap() {
    : >"$ARGV_LOG"
    set +e
    OUT=$(PATH="$FAKE_BIN:$PATH" \
        API_SERVER_URL="http://api.example" \
        RUN_ID="run-1" \
        NODE_EXECUTION_ID="node-1" \
        JOB_SECRET="test-secret" \
        bash "$RL_SCRIPT" "$@" 2>&1)
    RC=$?
    set -e
}

# --- Test 1: --help exits 0 and prints Usage ---
run_propose_roadmap --help
[ "$RC" -eq 0 ] && ok "--help: exits 0" || fail "--help: exits 0 (got $RC: $OUT)"
echo "$OUT" | grep -qF "Usage:" && ok "--help: prints Usage" || fail "--help: prints Usage (got: $OUT)"
[ ! -s "$ARGV_LOG" ] && ok "--help: makes no HTTP call" || fail "--help: makes no HTTP call"

# --- Test 2: missing --file exits 1 ---
run_propose_roadmap
[ "$RC" -eq 1 ] && ok "missing --file: exits 1" || fail "missing --file: exits 1 (got $RC)"
echo "$OUT" | grep -qF -- "--file" && ok "missing --file: mentions --file" || fail "missing --file: mentions --file (got: $OUT)"
[ ! -s "$ARGV_LOG" ] && ok "missing --file: makes no HTTP call" || fail "missing --file: makes no HTTP call"

# --- Test 2b: --file with no value exits 1 with a clear diagnostic, not a raw bash error ---
run_propose_roadmap --file
[ "$RC" -eq 1 ] && ok "--file with no value: exits 1" || fail "--file with no value: exits 1 (got $RC)"
echo "$OUT" | grep -qF "requires a value" \
    && ok "--file with no value: clear diagnostic" || fail "--file with no value: clear diagnostic (got: $OUT)"
[ ! -s "$ARGV_LOG" ] && ok "--file with no value: makes no HTTP call" || fail "--file with no value: makes no HTTP call"

# --- Test 2c: missing JOB_SECRET exits 1 before any HTTP call ---
NO_SECRET_JSON="$TESTDIR/no-secret.json"
printf '{}' > "$NO_SECRET_JSON"
: >"$ARGV_LOG"
set +e
OUT=$(env -u JOB_SECRET PATH="$FAKE_BIN:$PATH" \
    API_SERVER_URL="http://api.example" \
    RUN_ID="run-1" \
    NODE_EXECUTION_ID="node-1" \
    bash "$RL_SCRIPT" --file "$NO_SECRET_JSON" 2>&1)
RC=$?
set -e
[ "$RC" -eq 1 ] && ok "missing JOB_SECRET: exits 1" || fail "missing JOB_SECRET: exits 1 (got $RC: $OUT)"
echo "$OUT" | grep -qF "JOB_SECRET not set" \
    && ok "missing JOB_SECRET: clear diagnostic" || fail "missing JOB_SECRET: clear diagnostic (got: $OUT)"
[ ! -s "$ARGV_LOG" ] && ok "missing JOB_SECRET: makes no HTTP call" || fail "missing JOB_SECRET: makes no HTTP call"

# --- Test 3: invalid JSON exits 1 with no stub call recorded ---
BAD_JSON="$TESTDIR/bad.json"
echo "not json" > "$BAD_JSON"
run_propose_roadmap --file "$BAD_JSON"
[ "$RC" -eq 1 ] && ok "invalid JSON: exits 1" || fail "invalid JSON: exits 1 (got $RC)"
echo "$OUT" | grep -qF "is not valid JSON" && ok "invalid JSON: clear diagnostic" || fail "invalid JSON: clear diagnostic (got: $OUT)"
[ ! -s "$ARGV_LOG" ] && ok "invalid JSON: makes no HTTP call" || fail "invalid JSON: makes no HTTP call"

# --- Test 4: 200 installs the file, byte-identical, POST to the exact path, Bearer header present ---
GOOD_JSON="$TESTDIR/good.json"
printf '{"epics":[{"existingId":"11111111-1111-1111-1111-111111111111"}]}' > "$GOOD_JSON"
rm -rf "$RL_OUT"
CURL_STUB_BODY='{"mode":"ROADMAP_EXTENSION","gateLabel":"final_approval","newEpics":0,"newStories":0,"newTasks":0,"existingItems":1,"dependencies":0}' \
    run_propose_roadmap --file "$GOOD_JSON"
[ "$RC" -eq 0 ] && ok "200: exits 0" || fail "200: exits 0 (got $RC: $OUT)"
[ -f "$RL_OUT/roadmap_candidates.json" ] && ok "200: installs the file" || fail "200: installs the file"
diff -q "$GOOD_JSON" "$RL_OUT/roadmap_candidates.json" >/dev/null 2>&1 \
    && ok "200: installed file is byte-identical" || fail "200: installed file is byte-identical"
grep -qF -- "-X POST" "$ARGV_LOG" && ok "200: uses POST" || fail "200: uses POST (argv: $(cat "$ARGV_LOG"))"
grep -qF "http://api.example/internal/runs/run-1/node-executions/node-1/roadmap-proposal/validate" "$ARGV_LOG" \
    && ok "200: posts to the exact path" || fail "200: posts to the exact path (argv: $(cat "$ARGV_LOG"))"
grep -qF "Authorization: Bearer test-secret" "$ARGV_LOG" \
    && ok "200: Bearer header present" || fail "200: Bearer header present (argv: $(cat "$ARGV_LOG"))"
echo "$OUT" | grep -qF "final_approval" && ok "200: names the gate" || fail "200: names the gate (got: $OUT)"

# --- Test 5: 200 with --check installs nothing ---
rm -rf "$RL_OUT"
CURL_STUB_BODY='{"mode":"ROADMAP_EXTENSION","gateLabel":"final_approval","newEpics":0,"newStories":0,"newTasks":0,"existingItems":1,"dependencies":0}' \
    run_propose_roadmap --check --file "$GOOD_JSON"
[ "$RC" -eq 0 ] && ok "200 --check: exits 0" || fail "200 --check: exits 0 (got $RC: $OUT)"
[ ! -e "$RL_OUT/roadmap_candidates.json" ] && ok "200 --check: installs nothing" || fail "200 --check: installs nothing"
echo "$OUT" | grep -qF -- "--check" && ok "200 --check: notes it was not installed" || fail "200 --check: notes it was not installed (got: $OUT)"

# --- Test 6: 400 exits 1, prints both errors, leaves an existing out file untouched ---
mkdir -p "$RL_OUT"
echo '{"previously":"installed"}' > "$RL_OUT/roadmap_candidates.json"
CURL_STUB_HTTP_CODE=400 CURL_STUB_BODY='{"valid":false,"errors":["a","b"]}' \
    run_propose_roadmap --file "$GOOD_JSON"
[ "$RC" -eq 1 ] && ok "400: exits 1" || fail "400: exits 1 (got $RC)"
echo "$OUT" | grep -qF "ERROR: roadmap proposal rejected:" && ok "400: prints rejection header" || fail "400: prints rejection header (got: $OUT)"
echo "$OUT" | grep -qF "  - a" && ok "400: prints first error" || fail "400: prints first error (got: $OUT)"
echo "$OUT" | grep -qF "  - b" && ok "400: prints second error" || fail "400: prints second error (got: $OUT)"
grep -qF "previously" "$RL_OUT/roadmap_candidates.json" \
    && ok "400: leaves an existing installed file untouched" || fail "400: leaves an existing installed file untouched"

# --- Test 6b: a 400 without an errors[] array (e.g. a framework ProblemDetail) still prints the body ---
CURL_STUB_HTTP_CODE=400 CURL_STUB_BODY='{"title":"Bad Request","status":400,"detail":"Failed to read request"}' \
    run_propose_roadmap --file "$GOOD_JSON"
[ "$RC" -eq 1 ] && ok "400 ProblemDetail: exits 1" || fail "400 ProblemDetail: exits 1 (got $RC: $OUT)"
echo "$OUT" | grep -qF "Failed to read request" \
    && ok "400 ProblemDetail: prints the body" || fail "400 ProblemDetail: prints the body (got: $OUT)"

# --- Test 6c: a non-JSON 400 body still exits 1 and prints the body ---
CURL_STUB_HTTP_CODE=400 CURL_STUB_BODY='Bad Request' \
    run_propose_roadmap --file "$GOOD_JSON"
[ "$RC" -eq 1 ] && ok "400 plain body: exits 1" || fail "400 plain body: exits 1 (got $RC: $OUT)"
echo "$OUT" | grep -qF "  Bad Request" && ok "400 plain body: prints the body" || fail "400 plain body: prints the body (got: $OUT)"

# --- Test 7: 409 exits 1 with the message including HTTP 409 ---
CURL_STUB_HTTP_CODE=409 CURL_STUB_BODY='This workflow has no roadmap review gate' \
    run_propose_roadmap --file "$GOOD_JSON"
[ "$RC" -eq 1 ] && ok "409: exits 1" || fail "409: exits 1 (got $RC)"
echo "$OUT" | grep -qF "HTTP 409" && ok "409: message includes HTTP 409" || fail "409: message includes HTTP 409 (got: $OUT)"

# --- Test 8: stdin '-' input works ---
CURL_STUB_BODY='{"mode":"ROADMAP_EXTENSION","gateLabel":"final_approval","newEpics":0,"newStories":0,"newTasks":0,"existingItems":1,"dependencies":0}'
: >"$ARGV_LOG"
rm -rf "$RL_OUT"
set +e
OUT=$(cat "$GOOD_JSON" | PATH="$FAKE_BIN:$PATH" \
    API_SERVER_URL="http://api.example" \
    RUN_ID="run-1" \
    NODE_EXECUTION_ID="node-1" \
    JOB_SECRET="test-secret" \
    CURL_STUB_BODY="$CURL_STUB_BODY" \
    bash "$RL_SCRIPT" --file - 2>&1)
RC=$?
set -e
[ "$RC" -eq 0 ] && ok "stdin '-': exits 0" || fail "stdin '-': exits 0 (got $RC: $OUT)"
[ -f "$RL_OUT/roadmap_candidates.json" ] && ok "stdin '-': installs the file" || fail "stdin '-': installs the file"
diff -q "$GOOD_JSON" "$RL_OUT/roadmap_candidates.json" >/dev/null 2>&1 \
    && ok "stdin '-': installed file is byte-identical" || fail "stdin '-': installed file is byte-identical"

# --- Test 9: a missing file exits 1 with no HTTP call ---
run_propose_roadmap --file "$TESTDIR/does-not-exist.json"
[ "$RC" -eq 1 ] && ok "missing file: exits 1" || fail "missing file: exits 1 (got $RC)"
echo "$OUT" | grep -qF "does not exist" && ok "missing file: clear diagnostic" || fail "missing file: clear diagnostic (got: $OUT)"
[ ! -s "$ARGV_LOG" ] && ok "missing file: makes no HTTP call" || fail "missing file: makes no HTTP call"

# --- Test 10: an unreachable API server exits 1 with a diagnostic, installing nothing ---
rm -rf "$RL_OUT"
CURL_STUB_EXIT=7 CURL_STUB_BODY='' CURL_STUB_HTTP_CODE=000 run_propose_roadmap --file "$GOOD_JSON"
[ "$RC" -eq 1 ] && ok "curl failure: exits 1" || fail "curl failure: exits 1 (got $RC: $OUT)"
echo "$OUT" | grep -qF "Could not reach the API server" \
    && ok "curl failure: clear diagnostic" || fail "curl failure: clear diagnostic (got: $OUT)"
[ ! -e "$RL_OUT/roadmap_candidates.json" ] && ok "curl failure: installs nothing" || fail "curl failure: installs nothing"

# --- Summary ---
echo ""
echo "Results: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
