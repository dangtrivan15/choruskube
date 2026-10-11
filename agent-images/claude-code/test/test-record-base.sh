#!/bin/bash
# Unit tests for record-base (no live container needed)
set -euo pipefail

PASS=0
FAIL=0
TESTDIR=$(mktemp -d)
trap 'rm -rf "$TESTDIR"' EXIT

ok() { echo "PASS: $1"; PASS=$((PASS+1)); }
fail() { echo "FAIL: $1"; FAIL=$((FAIL+1)); }

RECORD_BASE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/record-base"

# A fake curl first on PATH stands in for the API server: it keeps the arguments and
# the -d body it was given, and answers with $FAKE_CODE the way `-w "%{http_code}"` would.
FAKE_BIN="$TESTDIR/bin"
mkdir -p "$FAKE_BIN"
cat > "$FAKE_BIN/curl" <<STUB
#!/bin/bash
printf '%s\n' "\$@" > "$TESTDIR/curl.args"
while [ \$# -gt 0 ]; do
  if [ "\$1" = "-d" ]; then printf '%s' "\$2" > "$TESTDIR/curl.body"; shift; fi
  shift
done
[ -z "\${FAKE_CURL_EXIT:-}" ] || exit "\$FAKE_CURL_EXIT"
printf '%s' "\${FAKE_CODE:-204}"
STUB
chmod +x "$FAKE_BIN/curl"

write_base() {
  # $1 = base dir, $2 = repo name, $3 = git repo id
  mkdir -p "$1"
  jq -n --arg id "$3" \
    '{gitRepoId: $id, outcome: "clean", baseSha: "0123456789abcdef0123456789abcdef01234567", conflictedFiles: []}' \
    > "$1/$2.json"
}

run_record_base() {
  # $1 = REPO_BASE_DIR. Leaves stdout+stderr in RB_OUT and the exit status in RB_RC.
  rm -f "$TESTDIR/curl.args" "$TESTDIR/curl.body"
  set +e
  RB_OUT=$(PATH="$FAKE_BIN:$PATH" REPO_BASE_DIR="$1" JOB_SECRET=s RUN_ID=run-1 \
    NODE_EXECUTION_ID=exec-1 API_SERVER_URL="${RB_API_URL-http://api}" bash "$RECORD_BASE" 2>&1)
  RB_RC=$?
  set -e
}

# --- Test 1: no recorded bases — nothing to post ---
run_record_base "$TESTDIR/empty"
[ "$RB_RC" -eq 0 ] && ok "no base files: exits 0" || fail "no base files: exits 0 (rc=$RB_RC: $RB_OUT)"
[ ! -e "$TESTDIR/curl.args" ] && ok "no base files: curl not called" || fail "no base files: curl not called"

# --- Test 2: two recorded bases — one POST carrying both ---
write_base "$TESTDIR/two" repo-a 11111111-1111-1111-1111-111111111111
write_base "$TESTDIR/two" repo-b 22222222-2222-2222-2222-222222222222
run_record_base "$TESTDIR/two"
[ "$RB_RC" -eq 0 ] && ok "two base files: exits 0" || fail "two base files: exits 0 (rc=$RB_RC: $RB_OUT)"
grep -qxF "http://api/internal/runs/run-1/node-executions/exec-1/repo-bases" "$TESTDIR/curl.args" 2>/dev/null \
  && ok "posts to this node execution's repo-bases endpoint" \
  || fail "posts to this node execution's repo-bases endpoint ($(cat "$TESTDIR/curl.args" 2>/dev/null))"
grep -qxF "POST" "$TESTDIR/curl.args" 2>/dev/null \
  && ok "uses POST" || fail "uses POST"
grep -qxF "Authorization: Bearer s" "$TESTDIR/curl.args" 2>/dev/null \
  && ok "authenticates with JOB_SECRET" || fail "authenticates with JOB_SECRET"
[ "$(jq '.repos | length' "$TESTDIR/curl.body" 2>/dev/null)" = "2" ] \
  && ok "body wraps both repos as {repos: [...]}" \
  || fail "body wraps both repos as {repos: [...]} ($(cat "$TESTDIR/curl.body" 2>/dev/null))"
[ "$(jq -r '[.repos[].gitRepoId] | sort | join(",")' "$TESTDIR/curl.body" 2>/dev/null)" \
  = "11111111-1111-1111-1111-111111111111,22222222-2222-2222-2222-222222222222" ] \
  && ok "body carries each file's gitRepoId" || fail "body carries each file's gitRepoId"

# --- Test 3: the API refuses — logged, never fatal ---
FAKE_CODE=500 run_record_base "$TESTDIR/two"
[ "$RB_RC" -eq 0 ] && ok "HTTP 500: exits 0" || fail "HTTP 500: exits 0 (rc=$RB_RC)"
echo "$RB_OUT" | grep -q "re-check" \
  && ok "HTTP 500: warns that the gate will re-check" || fail "HTTP 500: warns that the gate will re-check ($RB_OUT)"

# --- Test 4: curl itself fails (no status at all) — logged, never fatal ---
FAKE_CURL_EXIT=7 run_record_base "$TESTDIR/two"
[ "$RB_RC" -eq 0 ] && ok "curl failure: exits 0" || fail "curl failure: exits 0 (rc=$RB_RC)"
echo "$RB_OUT" | grep -q "HTTP 000" \
  && ok "curl failure: reported as HTTP 000" || fail "curl failure: reported as HTTP 000 ($RB_OUT)"

# --- Test 5: API_SERVER_URL unset — nothing to post to ---
RB_API_URL="" run_record_base "$TESTDIR/two"
[ "$RB_RC" -eq 0 ] && ok "API_SERVER_URL unset: exits 0" || fail "API_SERVER_URL unset: exits 0 (rc=$RB_RC)"
[ ! -e "$TESTDIR/curl.args" ] && ok "API_SERVER_URL unset: curl not called" \
  || fail "API_SERVER_URL unset: curl not called"

# --- Test 6: an unreadable base file — logged, never fatal, nothing posted ---
mkdir -p "$TESTDIR/bad"
echo "not json" > "$TESTDIR/bad/repo-x.json"
run_record_base "$TESTDIR/bad"
[ "$RB_RC" -eq 0 ] && ok "malformed base file: exits 0" || fail "malformed base file: exits 0 (rc=$RB_RC)"
[ ! -e "$TESTDIR/curl.args" ] && ok "malformed base file: curl not called" \
  || fail "malformed base file: curl not called"

# --- Summary ---
echo ""
echo "Results: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
