#!/bin/bash
# Behavioural tests for the `artifact` CLI, run against a real Node HTTP stub
# standing in for the api-server's presign route and the object store, plus a
# PATH shim for `sleep` so the backoff schedule is asserted without waiting.
#
# Each scenario starts its own stub on a fresh ephemeral port with a small
# queued-response script (JSON), runs the real, unmodified `artifact` binary
# against it, and inspects the stub's request log and the recorded sleep
# delays. A queued-response list that runs down to its last entry keeps
# returning that entry, so one entry models a persistent failure and two
# model "fails once, then succeeds".
set -euo pipefail

PASS=0
FAIL=0
TESTDIR=$(mktemp -d)
STUB_PIDS=()
cleanup() {
  for pid in "${STUB_PIDS[@]:-}"; do
    kill "$pid" >/dev/null 2>&1 || true
    wait "$pid" 2>/dev/null || true
  done
  rm -rf "$TESTDIR"
}
trap cleanup EXIT

ok() { echo "PASS: $1"; PASS=$((PASS + 1)); }
fail() { echo "FAIL: $1"; FAIL=$((FAIL + 1)); }

ARTIFACT_BIN="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/artifact"
SENTINEL="SIGNATURESENTINEL998877"

# --- The sleep shim: records every requested delay and returns at once. If
# SLEEP_SIGNAL_FILE is set, it also touches that file on every call, so a test
# can gate the stub's real `listen()` behind the artifact script's own backoff. ---
SHIM_DIR="$TESTDIR/bin"
mkdir -p "$SHIM_DIR"
cat > "$SHIM_DIR/sleep" <<'SHIM'
#!/bin/bash
echo "$1" >> "${SLEEP_LOG:?SLEEP_LOG not set}"
if [ -n "${SLEEP_SIGNAL_FILE:-}" ]; then
  touch "$SLEEP_SIGNAL_FILE"
fi
exit 0
SHIM
chmod +x "$SHIM_DIR/sleep"

# --- The Node HTTP stub: serves both the presign route (any path starting
# with /internal/runs/) and the transfer route (everything else), each off its
# own queue of canned responses in $SCRIPT_FILE, and logs every request (method,
# path, headers) as one JSON line per request to $LOG_FILE. ---
STUB_JS="$TESTDIR/stub.js"
cat > "$STUB_JS" <<'STUBJS'
const http = require('http');
const fs = require('fs');

const PORT = parseInt(process.env.STUB_PORT, 10);
const SCRIPT = JSON.parse(fs.readFileSync(process.env.SCRIPT_FILE, 'utf8'));
const LOG_FILE = process.env.LOG_FILE;
const UPLOAD_LOG = process.env.UPLOAD_LOG || '';
const DELAY_LISTEN_FILE = process.env.DELAY_LISTEN_FILE || '';

function popOrRepeat(queue) {
  if (!queue || queue.length === 0) return { status: 500 };
  if (queue.length > 1) return queue.shift();
  return queue[0];
}

function logReq(type, req) {
  const entry = { type, method: req.method, path: req.url, headers: req.headers };
  fs.appendFileSync(LOG_FILE, JSON.stringify(entry) + '\n');
}

const server = http.createServer((req, res) => {
  const isPresign = req.url.startsWith('/internal/runs/');
  const chunks = [];
  req.on('data', (c) => chunks.push(c));
  req.on('end', () => {
    const bodyBuf = Buffer.concat(chunks);

    if (isPresign) {
      logReq('presign', req);
      const r = popOrRepeat(SCRIPT.presign);
      const status = r.status || 200;
      if (status === 200) {
        let bodyStr;
        if (r.rawBody !== undefined) {
          bodyStr = r.rawBody;
        } else {
          const url = r.absoluteUrl
            ? r.absoluteUrl
            : `http://${req.headers.host}${r.urlPath || '/objects/default'}?X-Amz-Signature=${process.env.SENTINEL}`;
          bodyStr = JSON.stringify({ url });
        }
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(bodyStr);
      } else {
        res.writeHead(status, { 'Content-Type': 'text/plain' });
        res.end(r.body !== undefined ? r.body : `error ${status}`);
      }
      return;
    }

    logReq('transfer', req);
    if (UPLOAD_LOG && req.method === 'PUT') {
      fs.appendFileSync(UPLOAD_LOG, bodyBuf);
    }
    const r = popOrRepeat(SCRIPT.transfer);
    const status = r.status || 200;
    res.writeHead(status, { 'Content-Type': 'application/octet-stream' });
    res.end(r.body !== undefined ? r.body : '');
  });
});

function startListening() {
  server.listen(PORT, '127.0.0.1', () => console.log('LISTENING'));
}

if (DELAY_LISTEN_FILE) {
  const iv = setInterval(() => {
    if (fs.existsSync(DELAY_LISTEN_FILE)) {
      clearInterval(iv);
      startListening();
    }
  }, 5);
} else {
  startListening();
}
STUBJS

# A free TCP port, picked by binding to port 0 and releasing it immediately.
# The small window before the stub rebinds it is the standard trade-off for a
# self-contained test with no system-wide port registry.
pick_port() {
  node -e "const s=require('net').createServer();s.listen(0,'127.0.0.1',()=>{console.log(s.address().port);s.close();});"
}

# Polls (not via the shimmed `sleep` — that would recurse into the shim, since
# PATH is modified for the whole test, not just the artifact invocation) until
# $1:$2 accepts a TCP connection, or $3 seconds pass.
wait_for_port() {
  local host="$1" port="$2" timeout_s="${3:-5}"
  local tries=$((timeout_s * 50))
  for _ in $(seq 1 "$tries"); do
    if (exec 3<>"/dev/tcp/$host/$port") 2>/dev/null; then
      exec 3>&- 3<&- 2>/dev/null || true
      return 0
    fi
    command -p sleep 0.02
  done
  return 1
}

# Starts the stub on $1 (port) with the queued-response script at $2, logging
# requests to $3, PUT bodies to $4 (optional), gated behind $5 (optional
# DELAY_LISTEN_FILE). Appends the child's pid to STUB_PIDS and, with no gate,
# waits for it to actually accept connections before returning.
start_stub() {
  local port="$1" script_file="$2" log_file="$3" upload_log="${4:-}" delay_file="${5:-}"
  : > "$log_file"
  [ -n "$upload_log" ] && : > "$upload_log"
  STUB_PORT="$port" SCRIPT_FILE="$script_file" LOG_FILE="$log_file" \
    UPLOAD_LOG="$upload_log" DELAY_LISTEN_FILE="$delay_file" SENTINEL="$SENTINEL" \
    node "$STUB_JS" >"$TESTDIR/stub_${port}.out" 2>&1 &
  STUB_PIDS+=("$!")
  if [ -z "$delay_file" ]; then
    wait_for_port 127.0.0.1 "$port" 5 || {
      echo "stub on $port never came up: $(cat "$TESTDIR/stub_${port}.out" 2>/dev/null)" >&2
      return 1
    }
  fi
}

# Runs the real artifact binary. $1=action $2 $3=args $4=api base url
# $5=sleep log file $6=optional SLEEP_SIGNAL_FILE. Sets ARTIFACT_OUT/ARTIFACT_RC.
run_artifact() {
  local action="$1" a2="$2" a3="$3" api_url="$4" sleep_log="$5" signal_file="${6:-}"
  : > "$sleep_log"
  set +e
  ARTIFACT_OUT=$(
    PATH="$SHIM_DIR:$PATH" \
      API_SERVER_URL="$api_url" RUN_ID="run-1" NODE_EXECUTION_ID="exec-1" \
      JOB_SECRET="test-job-secret-xyz" SLEEP_LOG="$sleep_log" \
      SLEEP_SIGNAL_FILE="$signal_file" \
      bash "$ARTIFACT_BIN" "$action" "$a2" "$a3" 2>&1
  )
  ARTIFACT_RC=$?
  set -e
}

# Neither the sentinel signature value nor the literal header name it rides in
# may ever reach the artifact script's own stdout/stderr, whatever the stub's
# response body said.
assert_no_signed_url() {
  local label="$1" combined="$2"
  if grep -qF "$SENTINEL" <<< "$combined" || grep -qF "X-Amz-Signature" <<< "$combined"; then
    fail "$label: no signed URL or signature leaks into output (got: $combined)"
  else
    ok "$label: no signed URL or signature leaks into output"
  fi
}

presign_count() { jq -s '[.[] | select(.type=="presign")] | length' "$1"; }
transfer_count() { jq -s '[.[] | select(.type=="transfer")] | length' "$1"; }

# --- 1: GET 200 -> exit 0, content matches, no .part left behind ---
P1=$(pick_port); LOG1="$TESTDIR/log1"; SLEEP1="$TESTDIR/sleep1"
SCRIPT1="$TESTDIR/script1.json"
cat > "$SCRIPT1" <<EOF
{"presign":[{"status":200,"urlPath":"/objects/get-ok"}],"transfer":[{"status":200,"body":"hello from the store"}]}
EOF
start_stub "$P1" "$SCRIPT1" "$LOG1"
DEST1="$TESTDIR/get_out/result.txt"
run_artifact get "runs/r/e/out/result.txt" "$DEST1" "http://127.0.0.1:$P1" "$SLEEP1"
[ "$ARTIFACT_RC" -eq 0 ] && ok "GET 200: exits 0" || fail "GET 200: exits 0 (got $ARTIFACT_RC: $ARTIFACT_OUT)"
[ "$(cat "$DEST1" 2>/dev/null)" = "hello from the store" ] \
  && ok "GET 200: downloaded content matches" || fail "GET 200: downloaded content matches (got: $(cat "$DEST1" 2>/dev/null))"
[ ! -e "${DEST1}.part" ] && ok "GET 200: no .part file left behind" || fail "GET 200: no .part file left behind"
assert_no_signed_url "GET 200" "$ARTIFACT_OUT"
PRESIGN_HDRS1=$(jq -s '[.[] | select(.type=="presign")][0].headers.authorization' "$LOG1")
[ "$PRESIGN_HDRS1" = '"Bearer test-job-secret-xyz"' ] \
  && ok "presign request carries Authorization: Bearer ..." \
  || fail "presign request carries Authorization: Bearer ... (got $PRESIGN_HDRS1)"
TRANSFER_HDRS1=$(jq -s '[.[] | select(.type=="transfer")][0].headers' "$LOG1")
echo "$TRANSFER_HDRS1" | jq -e 'has("authorization") | not' >/dev/null \
  && ok "GET transfer carries no Authorization header" || fail "GET transfer carries no Authorization header (got $TRANSFER_HDRS1)"
echo "$TRANSFER_HDRS1" | jq -e '[keys[] | ascii_downcase | startswith("x-amz-")] | any | not' >/dev/null \
  && ok "GET transfer carries no x-amz- header" || fail "GET transfer carries no x-amz- header (got $TRANSFER_HDRS1)"

# --- 2: PUT 200 -> exit 0, stub received the body byte-for-byte ---
P2=$(pick_port); LOG2="$TESTDIR/log2"; SLEEP2="$TESTDIR/sleep2"; UP2="$TESTDIR/upload2.bin"
SCRIPT2="$TESTDIR/script2.json"
cat > "$SCRIPT2" <<EOF
{"presign":[{"status":200,"urlPath":"/objects/put-ok"}],"transfer":[{"status":200}]}
EOF
start_stub "$P2" "$SCRIPT2" "$LOG2" "$UP2"
SRC2="$TESTDIR/put_in.bin"
printf 'upload payload \x00\x01\xff bytes' > "$SRC2"
run_artifact put "$SRC2" "runs/r/e/out/put.bin" "http://127.0.0.1:$P2" "$SLEEP2"
[ "$ARTIFACT_RC" -eq 0 ] && ok "PUT 200: exits 0" || fail "PUT 200: exits 0 (got $ARTIFACT_RC: $ARTIFACT_OUT)"
cmp -s "$SRC2" "$UP2" && ok "PUT 200: stub received the body byte-for-byte" \
  || fail "PUT 200: stub received the body byte-for-byte"
assert_no_signed_url "PUT 200" "$ARTIFACT_OUT"
PUT_TRANSFER_HDRS=$(jq -s '[.[] | select(.type=="transfer")][0].headers' "$LOG2")
echo "$PUT_TRANSFER_HDRS" | jq -e 'has("authorization") | not' >/dev/null \
  && ok "PUT transfer carries no Authorization header" || fail "PUT transfer carries no Authorization header (got $PUT_TRANSFER_HDRS)"

# --- 3 & 4: transient transfer failure then success, one recorded backoff ---
run_transient_transfer_case() {
  local status="$1" label="$2"
  local port=$(pick_port) log="$TESTDIR/log_t_$status" sleeplog="$TESTDIR/sleep_t_$status"
  local script="$TESTDIR/script_t_$status.json"
  cat > "$script" <<EOF
{"presign":[{"status":200,"urlPath":"/objects/t-$status"}],"transfer":[{"status":$status},{"status":200,"body":"recovered"}]}
EOF
  start_stub "$port" "$script" "$log"
  local dest="$TESTDIR/t_${status}_out.txt"
  run_artifact get "runs/r/e/out/t.txt" "$dest" "http://127.0.0.1:$port" "$sleeplog"
  [ "$ARTIFACT_RC" -eq 0 ] && ok "$label then 200: exits 0" || fail "$label then 200: exits 0 (got $ARTIFACT_RC: $ARTIFACT_OUT)"
  [ "$(cat "$dest" 2>/dev/null)" = "recovered" ] && ok "$label then 200: content is from the successful attempt" \
    || fail "$label then 200: content is from the successful attempt"
  [ "$(cat "$sleeplog")" = "2" ] && ok "$label then 200: exactly one backoff of 2s recorded" \
    || fail "$label then 200: exactly one backoff of 2s recorded (got: $(cat "$sleeplog" | tr '\n' ' '))"
  assert_no_signed_url "$label then 200" "$ARTIFACT_OUT"
}
run_transient_transfer_case 503 "transfer 503"
run_transient_transfer_case 429 "transfer 429"

# --- 5: presign 503 then 200 -> exit 0 ---
P5=$(pick_port); LOG5="$TESTDIR/log5"; SLEEP5="$TESTDIR/sleep5"
SCRIPT5="$TESTDIR/script5.json"
cat > "$SCRIPT5" <<EOF
{"presign":[{"status":503},{"status":200,"urlPath":"/objects/p5"}],"transfer":[{"status":200,"body":"ok5"}]}
EOF
start_stub "$P5" "$SCRIPT5" "$LOG5"
DEST5="$TESTDIR/p5_out.txt"
run_artifact get "runs/r/e/out/p5.txt" "$DEST5" "http://127.0.0.1:$P5" "$SLEEP5"
[ "$ARTIFACT_RC" -eq 0 ] && ok "presign 503 then 200: exits 0" || fail "presign 503 then 200: exits 0 (got $ARTIFACT_RC: $ARTIFACT_OUT)"
[ "$(presign_count "$LOG5")" -eq 2 ] && ok "presign 503 then 200: presign retried exactly once" \
  || fail "presign 503 then 200: presign retried exactly once (got $(presign_count "$LOG5"))"
assert_no_signed_url "presign 503 then 200" "$ARTIFACT_OUT"

# --- 6: presign connection refused, then the stub comes up -> exit 0 ---
# The stub process starts immediately but defers its own listen() until
# DELAY_LISTEN_FILE appears, so the first attempt gets a real ECONNREFUSED.
# The sleep shim (invoked for the backoff after that failure) touches the
# file and the test's own wait_for_port confirms the port opens before the
# artifact script's next attempt — exercising a real curl-level failure and
# recovery, not a simulated one.
P6=$(pick_port); LOG6="$TESTDIR/log6"; SLEEP6="$TESTDIR/sleep6"
SIGNAL6="$TESTDIR/signal6"
SCRIPT6="$TESTDIR/script6.json"
cat > "$SCRIPT6" <<EOF
{"presign":[{"status":200,"urlPath":"/objects/p6"}],"transfer":[{"status":200,"body":"ok6"}]}
EOF
start_stub "$P6" "$SCRIPT6" "$LOG6" "" "$SIGNAL6"
DEST6="$TESTDIR/p6_out.txt"
(
  run_artifact get "runs/r/e/out/p6.txt" "$DEST6" "http://127.0.0.1:$P6" "$SLEEP6" "$SIGNAL6"
  echo "$ARTIFACT_RC" > "$TESTDIR/p6_rc"
  printf '%s' "$ARTIFACT_OUT" > "$TESTDIR/p6_out_captured"
) &
P6_RUNNER=$!
wait_for_port 127.0.0.1 "$P6" 10 || true
wait "$P6_RUNNER"
ARTIFACT_RC=$(cat "$TESTDIR/p6_rc")
ARTIFACT_OUT=$(cat "$TESTDIR/p6_out_captured")
[ "$ARTIFACT_RC" -eq 0 ] && ok "presign connection refused then up: exits 0" \
  || fail "presign connection refused then up: exits 0 (got $ARTIFACT_RC: $ARTIFACT_OUT)"
[ "$(cat "$DEST6" 2>/dev/null)" = "ok6" ] && ok "presign connection refused then up: content downloaded" \
  || fail "presign connection refused then up: content downloaded"
assert_no_signed_url "presign connection refused then up" "$ARTIFACT_OUT"

# --- 7: transfer GET 404 -> exit 4, local file absent, no .part ---
P7=$(pick_port); LOG7="$TESTDIR/log7"; SLEEP7="$TESTDIR/sleep7"
SCRIPT7="$TESTDIR/script7.json"
cat > "$SCRIPT7" <<EOF
{"presign":[{"status":200,"urlPath":"/objects/missing"}],"transfer":[{"status":404}]}
EOF
start_stub "$P7" "$SCRIPT7" "$LOG7"
DEST7="$TESTDIR/missing_dir/missing.txt"
run_artifact get "runs/r/e/out/missing.txt" "$DEST7" "http://127.0.0.1:$P7" "$SLEEP7"
[ "$ARTIFACT_RC" -eq 4 ] && ok "GET 404: exits 4" || fail "GET 404: exits 4 (got $ARTIFACT_RC: $ARTIFACT_OUT)"
[ ! -e "$DEST7" ] && ok "GET 404: local file absent" || fail "GET 404: local file absent"
[ ! -e "${DEST7}.part" ] && ok "GET 404: no .part file left behind" || fail "GET 404: no .part file left behind"
[ "$(transfer_count "$LOG7")" -eq 1 ] && ok "GET 404: no retry (one request)" \
  || fail "GET 404: no retry (one request) (got $(transfer_count "$LOG7"))"
assert_no_signed_url "GET 404" "$ARTIFACT_OUT"

# --- 8: transfer PUT 404 -> exit 1 ---
P8=$(pick_port); LOG8="$TESTDIR/log8"; SLEEP8="$TESTDIR/sleep8"
SCRIPT8="$TESTDIR/script8.json"
cat > "$SCRIPT8" <<EOF
{"presign":[{"status":200,"urlPath":"/objects/put-missing"}],"transfer":[{"status":404}]}
EOF
start_stub "$P8" "$SCRIPT8" "$LOG8"
SRC8="$TESTDIR/put8.bin"; printf 'x' > "$SRC8"
run_artifact put "$SRC8" "runs/r/e/out/put8.bin" "http://127.0.0.1:$P8" "$SLEEP8"
[ "$ARTIFACT_RC" -eq 1 ] && ok "PUT 404: exits 1 (not 4)" || fail "PUT 404: exits 1 (not 4) (got $ARTIFACT_RC: $ARTIFACT_OUT)"
assert_no_signed_url "PUT 404" "$ARTIFACT_OUT"

# --- 9: transfer 403 -> exit 1, no retry, sentinel in the error body never leaks ---
P9=$(pick_port); LOG9="$TESTDIR/log9"; SLEEP9="$TESTDIR/sleep9"
SCRIPT9="$TESTDIR/script9.json"
cat > "$SCRIPT9" <<EOF
{"presign":[{"status":200,"urlPath":"/objects/refused"}],"transfer":[{"status":403,"body":"<Error><Code>AccessDenied</Code><Signature>$SENTINEL</Signature></Error>"}]}
EOF
start_stub "$P9" "$SCRIPT9" "$LOG9"
DEST9="$TESTDIR/refused.txt"
run_artifact get "runs/r/e/out/refused.txt" "$DEST9" "http://127.0.0.1:$P9" "$SLEEP9"
[ "$ARTIFACT_RC" -eq 1 ] && ok "transfer 403: exits 1" || fail "transfer 403: exits 1 (got $ARTIFACT_RC: $ARTIFACT_OUT)"
[ "$(transfer_count "$LOG9")" -eq 1 ] && ok "transfer 403: no retry (one request)" \
  || fail "transfer 403: no retry (one request) (got $(transfer_count "$LOG9"))"
assert_no_signed_url "transfer 403 (sentinel echoed in the refused body)" "$ARTIFACT_OUT"

# --- 10: presign 403 -> exit 1, no retry ---
P10=$(pick_port); LOG10="$TESTDIR/log10"; SLEEP10="$TESTDIR/sleep10"
SCRIPT10="$TESTDIR/script10.json"
cat > "$SCRIPT10" <<EOF
{"presign":[{"status":403,"body":"forbidden"}],"transfer":[]}
EOF
start_stub "$P10" "$SCRIPT10" "$LOG10"
DEST10="$TESTDIR/p10.txt"
run_artifact get "runs/r/e/out/p10.txt" "$DEST10" "http://127.0.0.1:$P10" "$SLEEP10"
[ "$ARTIFACT_RC" -eq 1 ] && ok "presign 403: exits 1" || fail "presign 403: exits 1 (got $ARTIFACT_RC: $ARTIFACT_OUT)"
[ "$(presign_count "$LOG10")" -eq 1 ] && ok "presign 403: no retry (one request)" \
  || fail "presign 403: no retry (one request) (got $(presign_count "$LOG10"))"

# --- 11: presign 404 -> exit 1, not 4 ---
P11=$(pick_port); LOG11="$TESTDIR/log11"; SLEEP11="$TESTDIR/sleep11"
SCRIPT11="$TESTDIR/script11.json"
cat > "$SCRIPT11" <<EOF
{"presign":[{"status":404,"body":"no such run"}],"transfer":[]}
EOF
start_stub "$P11" "$SCRIPT11" "$LOG11"
DEST11="$TESTDIR/p11.txt"
run_artifact get "runs/r/e/out/p11.txt" "$DEST11" "http://127.0.0.1:$P11" "$SLEEP11"
[ "$ARTIFACT_RC" -eq 1 ] && ok "presign 404: exits 1, not 4" || fail "presign 404: exits 1, not 4 (got $ARTIFACT_RC: $ARTIFACT_OUT)"

# --- 12: persistent 500 -> exit 1 after exactly 5 attempts, sleeps 2/4/8/16 ---
P12=$(pick_port); LOG12="$TESTDIR/log12"; SLEEP12="$TESTDIR/sleep12"
SCRIPT12="$TESTDIR/script12.json"
cat > "$SCRIPT12" <<EOF
{"presign":[{"status":200,"urlPath":"/objects/p12"}],"transfer":[{"status":500}]}
EOF
start_stub "$P12" "$SCRIPT12" "$LOG12"
DEST12="$TESTDIR/p12.txt"
run_artifact get "runs/r/e/out/p12.txt" "$DEST12" "http://127.0.0.1:$P12" "$SLEEP12"
[ "$ARTIFACT_RC" -eq 1 ] && ok "persistent 500: exits 1" || fail "persistent 500: exits 1 (got $ARTIFACT_RC: $ARTIFACT_OUT)"
[ "$(transfer_count "$LOG12")" -eq 5 ] && ok "persistent 500: exactly 5 attempts" \
  || fail "persistent 500: exactly 5 attempts (got $(transfer_count "$LOG12"))"
SLEEPS12=$(tr '\n' ' ' < "$SLEEP12" | sed 's/ $//')
[ "$SLEEPS12" = "2 4 8 16" ] && ok "persistent 500: backoff delays are 2, 4, 8, 16" \
  || fail "persistent 500: backoff delays are 2, 4, 8, 16 (got: $SLEEPS12)"
SUM12=$(awk '{s+=$1}END{print s}' "$SLEEP12")
[ "$SUM12" -le 120 ] && ok "persistent 500: total backoff is within the 120s window" \
  || fail "persistent 500: total backoff is within the 120s window (got ${SUM12}s)"

# --- 13: unresolvable host -> exit 1 ---
P13=$(pick_port); LOG13="$TESTDIR/log13"; SLEEP13="$TESTDIR/sleep13"
SCRIPT13="$TESTDIR/script13.json"
cat > "$SCRIPT13" <<EOF
{"presign":[{"status":200,"absoluteUrl":"https://artifact-test-host-does-not-exist.invalid/objects/x?X-Amz-Signature=$SENTINEL"}],"transfer":[]}
EOF
start_stub "$P13" "$SCRIPT13" "$LOG13"
DEST13="$TESTDIR/p13.txt"
run_artifact get "runs/r/e/out/p13.txt" "$DEST13" "http://127.0.0.1:$P13" "$SLEEP13"
[ "$ARTIFACT_RC" -eq 1 ] && ok "unresolvable host: exits 1" || fail "unresolvable host: exits 1 (got $ARTIFACT_RC: $ARTIFACT_OUT)"
assert_no_signed_url "unresolvable host" "$ARTIFACT_OUT"

# --- 14: an inner-command failure (malformed presign JSON) exits 1, never 4 ---
P14=$(pick_port); LOG14="$TESTDIR/log14"; SLEEP14="$TESTDIR/sleep14"
SCRIPT14="$TESTDIR/script14.json"
cat > "$SCRIPT14" <<EOF
{"presign":[{"status":200,"rawBody":"this is not json"}],"transfer":[]}
EOF
start_stub "$P14" "$SCRIPT14" "$LOG14"
DEST14="$TESTDIR/p14.txt"
run_artifact get "runs/r/e/out/p14.txt" "$DEST14" "http://127.0.0.1:$P14" "$SLEEP14"
[ "$ARTIFACT_RC" -eq 1 ] && ok "malformed presign JSON body: exits 1, never 4" \
  || fail "malformed presign JSON body: exits 1, never 4 (got $ARTIFACT_RC: $ARTIFACT_OUT)"

# --- 15: --help lists exit statuses 0, 1 and 4 ---
HELP_OUT=$(bash "$ARTIFACT_BIN" --help)
grep -qF "0  Operation completed successfully" <<< "$HELP_OUT" \
  && ok "--help documents exit status 0" || fail "--help documents exit status 0 (got: $HELP_OUT)"
grep -qF "4  The object store answered 404" <<< "$HELP_OUT" \
  && ok "--help documents exit status 4" || fail "--help documents exit status 4 (got: $HELP_OUT)"
grep -qF "1  Everything else" <<< "$HELP_OUT" \
  && ok "--help documents exit status 1" || fail "--help documents exit status 1 (got: $HELP_OUT)"

# --- Summary ---
echo ""
echo "Results: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
