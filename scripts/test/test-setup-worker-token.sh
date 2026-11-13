#!/bin/bash
# scripts/test/test-setup-worker-token.sh — Unit tests for the per-install Worker Fleet
# token: scripts/setup.sh's ensure_worker_token step, scripts/down.sh's teardown
# placeholder, scripts/up.sh's pre-build refusal, and the required-variable wiring in
# docker-compose.yaml.
#
# Each case builds a fresh mktemp root laid out like the repository — copies of
# scripts/setup.sh, scripts/lib/common.sh, scripts/down.sh, scripts/up.sh,
# docker-compose.yaml, and a stub scripts/build-agent-images.sh that only records that
# it was called — and runs the copied scripts with WORKER_REGISTRATION_TOKEN unset in
# their environment. No real credential, image build, or container is ever involved.
#
# Capturing a command whose non-zero exit is the expected, asserted-on outcome (a
# refused compose start, a bad argument, ...) always goes through `set +e` / `set -e`
# around it, same as agent-images/claude-code/test/test-check-prs.sh: a bare failing
# assignment under this file's own `set -e` would abort the whole suite instead of
# reporting one failed case.
set -euo pipefail

PASS=0
FAIL=0

ok()   { echo "PASS: $1"; PASS=$((PASS+1)); }
fail() { echo "FAIL: $1"; FAIL=$((FAIL+1)); }

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SETUP_SRC="$HERE/../setup.sh"
COMMON_SRC="$HERE/../lib/common.sh"
DOWN_SRC="$HERE/../down.sh"
UP_SRC="$HERE/../up.sh"
COMPOSE_SRC="$HERE/../../docker-compose.yaml"

BASE_TMP="$(mktemp -d)"
# Captures $? before anything else runs: `rm` succeeding or not must never change this
# file's own exit status (the PASS/FAIL tally below), which an un-captured `trap ... EXIT`
# would otherwise silently overwrite.
finish() {
  local ec=$?
  rm -rf "$BASE_TMP"
  exit "$ec"
}
trap finish EXIT

# A file's mode as three octal digits, GNU or BSD stat.
file_mode() { stat -c '%a' "$1" 2>/dev/null || stat -f '%Lp' "$1"; }

# Runs "$@" with cwd=$1 and WORKER_REGISTRATION_TOKEN unset, regardless of this test
# script's own environment.
in_root() {
  local root="$1"
  shift
  ( cd "$root" && unset WORKER_REGISTRATION_TOKEN && "$@" )
}

# A fresh mktemp root holding copies of every script this file exercises, plus a stub
# build-agent-images.sh that only records invocation (never a real image build).
make_root() {
  local root
  root="$(mktemp -d "$BASE_TMP/root.XXXXXX")"
  mkdir -p "$root/scripts/lib"
  cp "$SETUP_SRC" "$root/scripts/setup.sh"
  cp "$COMMON_SRC" "$root/scripts/lib/common.sh"
  cp "$DOWN_SRC" "$root/scripts/down.sh"
  cp "$UP_SRC" "$root/scripts/up.sh"
  cp "$COMPOSE_SRC" "$root/docker-compose.yaml"
  cat > "$root/scripts/build-agent-images.sh" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
echo called >> "$(dirname "${BASH_SOURCE[0]}")/../build-agent-images.called"
STUB
  chmod +x "$root/scripts/setup.sh" "$root/scripts/down.sh" "$root/scripts/up.sh" "$root/scripts/build-agent-images.sh"
  printf '%s' "$root"
}

# The value of the (last) WORKER_REGISTRATION_TOKEN= line in $1, or empty if there is none.
token_of() {
  set +e
  grep -E '^[[:space:]]*(export[[:space:]]+)?WORKER_REGISTRATION_TOKEN=' "$1" | tail -1 | cut -d= -f2-
  set -e
}

# --- Case 1: fresh installs differ ----------------------------------------------------------
test_fresh_installs_differ() {
  local r1 r2 out1 out2 rc1 rc2 n1 n2 val1 val2
  r1="$(make_root)"
  r2="$(make_root)"

  set +e
  out1="$(in_root "$r1" bash scripts/setup.sh --worker-token-only </dev/null 2>&1)"; rc1=$?
  out2="$(in_root "$r2" bash scripts/setup.sh --worker-token-only </dev/null 2>&1)"; rc2=$?
  set -e

  [[ "$rc1" -eq 0 ]] && ok "case1: fresh install 1 setup.sh --worker-token-only exits 0" \
    || { fail "case1: fresh install 1 exited $rc1: $out1"; return; }
  [[ "$rc2" -eq 0 ]] && ok "case1: fresh install 2 setup.sh --worker-token-only exits 0" \
    || { fail "case1: fresh install 2 exited $rc2: $out2"; return; }

  set +e
  n1="$(grep -cE '^[[:space:]]*(export[[:space:]]+)?WORKER_REGISTRATION_TOKEN=' "$r1/.env")"
  n2="$(grep -cE '^[[:space:]]*(export[[:space:]]+)?WORKER_REGISTRATION_TOKEN=' "$r2/.env")"
  set -e
  [[ "$n1" == 1 ]] && ok "case1: install 1's .env holds exactly one token line" \
    || fail "case1: install 1's .env holds $n1 token lines, want 1"
  [[ "$n2" == 1 ]] && ok "case1: install 2's .env holds exactly one token line" \
    || fail "case1: install 2's .env holds $n2 token lines, want 1"

  val1="$(token_of "$r1/.env")"
  val2="$(token_of "$r2/.env")"
  [[ -n "$val1" && -n "$val2" ]] && ok "case1: both tokens are non-empty" \
    || fail "case1: a generated token was empty"
  [[ "$val1" != "$val2" ]] && ok "case1: the two installs' tokens differ" \
    || fail "case1: two fresh installs generated the same token"

  [[ "$(file_mode "$r1/.env")" == 600 ]] && ok "case1: install 1's .env is mode 600" \
    || fail "case1: install 1's .env is mode $(file_mode "$r1/.env"), want 600"
  [[ "$(file_mode "$r2/.env")" == 600 ]] && ok "case1: install 2's .env is mode 600" \
    || fail "case1: install 2's .env is mode $(file_mode "$r2/.env"), want 600"
}

# --- Case 2: re-run keeps --------------------------------------------------------------------
test_rerun_keeps() {
  local root before out rc after
  root="$(make_root)"
  in_root "$root" bash scripts/setup.sh --worker-token-only </dev/null >/dev/null 2>&1
  before="$(token_of "$root/.env")"

  set +e
  out="$(in_root "$root" bash scripts/setup.sh --worker-token-only </dev/null 2>&1)"; rc=$?
  set -e
  [[ "$rc" -eq 0 ]] && ok "case2: a second run exits 0" || { fail "case2: second run exited $rc: $out"; return; }

  after="$(token_of "$root/.env")"
  [[ "$before" == "$after" ]] && ok "case2: a second run keeps the token unchanged" \
    || fail "case2: token changed on a plain re-run"
  grep -q '^Worker token: kept ' <<< "$out" && ok "case2: output says kept" \
    || fail "case2: output did not say kept: $out"
}

# --- Case 3: rotate ---------------------------------------------------------------------------
test_rotate() {
  local root before out rc after
  root="$(make_root)"
  in_root "$root" bash scripts/setup.sh --worker-token-only </dev/null >/dev/null 2>&1
  before="$(token_of "$root/.env")"

  set +e
  out="$(in_root "$root" bash scripts/setup.sh --worker-token-only --rotate-worker-token </dev/null 2>&1)"; rc=$?
  set -e
  [[ "$rc" -eq 0 ]] && ok "case3: rotate run exits 0" || { fail "case3: rotate run exited $rc: $out"; return; }

  after="$(token_of "$root/.env")"
  [[ "$before" != "$after" ]] && ok "case3: --rotate-worker-token changes the value" \
    || fail "case3: token did not change on rotation"
  grep -q '^Worker token: rotated ' <<< "$out" && ok "case3: output says rotated" \
    || fail "case3: output did not say rotated: $out"
}

# --- Case 4: other lines untouched ------------------------------------------------------------
test_other_lines_untouched() {
  local root fixture out rc
  root="$(make_root)"
  # Unrelated lines, a comment, no WORKER_REGISTRATION_TOKEN line, and no final newline.
  fixture="$(printf '# a comment\nFOO=bar\nCHORUSKUBE_TELEMETRY=off')"
  printf '%s' "$fixture" > "$root/.env"
  chmod 640 "$root/.env"

  set +e
  out="$(in_root "$root" bash scripts/setup.sh --worker-token-only </dev/null 2>&1)"; rc=$?
  set -e
  [[ "$rc" -eq 0 ]] && ok "case4: generation run exits 0" || { fail "case4: generation run exited $rc: $out"; return; }

  if head -n3 "$root/.env" | diff -q - <(printf '%s\n' "$fixture") >/dev/null 2>&1; then
    ok "case4: after generation, the three original lines are byte-identical and in order"
  else
    fail "case4: after generation, the original lines changed"
  fi
  sed -n '3p' "$root/.env" | grep -qx 'CHORUSKUBE_TELEMETRY=off' \
    && ok "case4: after generation, CHORUSKUBE_TELEMETRY=off is still its own line" \
    || fail "case4: after generation, CHORUSKUBE_TELEMETRY=off line is malformed"
  [[ "$(file_mode "$root/.env")" == 640 ]] && ok "case4: after generation, mode is still 640" \
    || fail "case4: after generation, mode is $(file_mode "$root/.env"), want 640"

  set +e
  out="$(in_root "$root" bash scripts/setup.sh --worker-token-only --rotate-worker-token </dev/null 2>&1)"; rc=$?
  set -e
  [[ "$rc" -eq 0 ]] && ok "case4: rotation run exits 0" || { fail "case4: rotation run exited $rc: $out"; return; }

  if head -n3 "$root/.env" | diff -q - <(printf '%s\n' "$fixture") >/dev/null 2>&1; then
    ok "case4: after rotation, the three original lines are still byte-identical and in order"
  else
    fail "case4: after rotation, the original lines changed"
  fi
  sed -n '3p' "$root/.env" | grep -qx 'CHORUSKUBE_TELEMETRY=off' \
    && ok "case4: after rotation, CHORUSKUBE_TELEMETRY=off is still its own line" \
    || fail "case4: after rotation, CHORUSKUBE_TELEMETRY=off line is malformed"
  [[ "$(file_mode "$root/.env")" == 640 ]] && ok "case4: after rotation, mode is still 640" \
    || fail "case4: after rotation, mode is $(file_mode "$root/.env"), want 640"
}

# --- Case 5: no value is missing --------------------------------------------------------------
test_no_value_is_missing() {
  local variants descs i root out rc val
  variants=("" "\"\"" "''")
  descs=("empty" "double-quoted-empty" "single-quoted-empty")
  for i in "${!variants[@]}"; do
    root="$(make_root)"
    printf 'WORKER_REGISTRATION_TOKEN=%s\nOTHER=1\n' "${variants[$i]}" > "$root/.env"

    set +e
    out="$(in_root "$root" bash scripts/setup.sh --worker-token-only </dev/null 2>&1)"; rc=$?
    set -e
    if [[ "$rc" -ne 0 ]]; then
      fail "case5 (${descs[$i]}): run exited $rc: $out"
      continue
    fi
    val="$(token_of "$root/.env")"
    [[ -n "$val" ]] && ok "case5 (${descs[$i]}): a value of '${variants[$i]}' gets a generated token" \
      || fail "case5 (${descs[$i]}): still no value after the run"
    grep -q '^Worker token: generated ' <<< "$out" && ok "case5 (${descs[$i]}): output says generated" \
      || fail "case5 (${descs[$i]}): output did not say generated: $out"
  done
}

# --- Case 6: keeping credentials still generates ------------------------------------------------
test_keeping_credentials_still_generates() {
  local root out rc cred_before cred_after
  root="$(make_root)"
  mkdir -p "$root/.secrets/credentials"
  echo dummy-claude-token > "$root/.secrets/credentials/claude-oauth-token"
  cred_before="$(cat "$root/.secrets/credentials/claude-oauth-token")"

  set +e
  out="$(in_root "$root" bash -c 'printf "n\n" | bash scripts/setup.sh' 2>&1)"; rc=$?
  set -e
  [[ "$rc" -eq 0 ]] && ok "case6: interactive setup.sh fed n exits 0" \
    || { fail "case6: interactive setup.sh fed n exited $rc: $out"; return; }

  cred_after="$(cat "$root/.secrets/credentials/claude-oauth-token" 2>/dev/null || echo MISSING)"
  [[ "$cred_before" == "$cred_after" ]] && ok "case6: existing credential files are unchanged" \
    || fail "case6: credential file changed (was '$cred_before', now '$cred_after')"

  if [[ -s "$root/.env" ]] && grep -qE '^[[:space:]]*(export[[:space:]]+)?WORKER_REGISTRATION_TOKEN=.+' "$root/.env"; then
    ok "case6: .env now holds a non-empty token"
  else
    fail "case6: .env has no token after declining to overwrite credentials"
  fi
}

# --- Case 7: no value printed -------------------------------------------------------------------
test_no_value_printed() {
  local root out1 out2 token
  root="$(make_root)"
  out1="$(in_root "$root" bash scripts/setup.sh --worker-token-only </dev/null 2>&1)"
  token="$(token_of "$root/.env")"
  out2="$(in_root "$root" bash scripts/setup.sh --worker-token-only --rotate-worker-token </dev/null 2>&1)"

  if [[ -n "$token" ]] && ! grep -qF "$token" <<< "$out1" && ! grep -qF "$token" <<< "$out2"; then
    ok "case7: neither run's output contains the generated token value"
  else
    fail "case7: a run's output contained the token value"
  fi
}

# --- Case 8: bad argument -----------------------------------------------------------------------
test_bad_argument() {
  local root out rc
  root="$(make_root)"
  set +e
  out="$(in_root "$root" bash scripts/setup.sh --bogus </dev/null 2>&1)"; rc=$?
  set -e
  [[ "$rc" -eq 2 ]] && ok "case8: setup.sh --bogus exits 2" \
    || fail "case8: setup.sh --bogus exited $rc, want 2"
  [[ ! -e "$root/.env" ]] && ok "case8: setup.sh --bogus writes no .env" \
    || fail "case8: setup.sh --bogus created .env"
}

# --- Case 9: refusal ----------------------------------------------------------------------------
test_refusal() {
  local root out rc
  root="$(make_root)"
  set +e
  # 2>&1 1>/dev/null: duplicate stderr onto the capture, then discard stdout — stderr only.
  out="$(in_root "$root" docker compose -f "$root/docker-compose.yaml" config --quiet 2>&1 1>/dev/null)"; rc=$?
  set -e
  [[ "$rc" -ne 0 ]] && ok "case9: compose config with no token exits non-zero" \
    || fail "case9: compose config with no token exited 0"
  grep -qF './scripts/setup.sh' <<< "$out" && ok "case9: the refusal names ./scripts/setup.sh" \
    || fail "case9: the refusal did not name ./scripts/setup.sh: $out"
}

# --- Case 10: wiring -----------------------------------------------------------------------------
test_wiring() {
  local root token rendered rc api_val worker_val
  root="$(make_root)"
  in_root "$root" bash scripts/setup.sh --worker-token-only </dev/null >/dev/null 2>&1
  token="$(token_of "$root/.env")"

  set +e
  rendered="$(in_root "$root" env "WORKER_REGISTRATION_TOKEN=$token" docker compose -f "$root/docker-compose.yaml" config 2>&1)"
  rc=$?
  set -e
  [[ "$rc" -eq 0 ]] && ok "case10: compose config succeeds once the token is set" \
    || { fail "case10: compose config failed with a token set: $rendered"; return; }

  set +e
  api_val="$(grep -E '^[[:space:]]*WORKER_REGISTRATION_TOKEN:' <<< "$rendered" | head -1 | awk '{print $2}')"
  worker_val="$(grep -E '^[[:space:]]*FLEET_TOKEN:' <<< "$rendered" | head -1 | awk '{print $2}')"
  set -e

  [[ "$api_val" == "$token" ]] && ok "case10: api-server's WORKER_REGISTRATION_TOKEN equals the generated value" \
    || fail "case10: api-server's WORKER_REGISTRATION_TOKEN is '$api_val', want '$token'"
  [[ "$worker_val" == "$token" ]] && ok "case10: worker's FLEET_TOKEN equals the generated value" \
    || fail "case10: worker's FLEET_TOKEN is '$worker_val', want '$token'"
}

# --- Case 11: teardown without a token ------------------------------------------------------------
test_teardown_without_token() {
  local root rc
  root="$(make_root)"
  set +e
  # A unique project name: the compose file's own "name: choruskube" would otherwise
  # point this "down -v" at the operator's real stack.
  in_root "$root" env "COMPOSE_PROJECT_NAME=choruskube-test-down-$$-$RANDOM" bash scripts/down.sh >/dev/null 2>&1
  rc=$?
  set -e
  [[ "$rc" -eq 0 ]] && ok "case11: down.sh exits 0 with no .env and no token set" \
    || fail "case11: down.sh exited $rc with no .env and no token set"
}

# --- Case 12: start script refuses before building ---------------------------------------------
test_up_refuses_before_building() {
  local root out rc
  root="$(make_root)"
  set +e
  out="$(in_root "$root" env "COMPOSE_PROJECT_NAME=choruskube-test-up-$$-$RANDOM" bash scripts/up.sh 2>&1 1>/dev/null)"
  rc=$?
  set -e
  [[ "$rc" -ne 0 ]] && ok "case12: up.sh exits non-zero with no token" \
    || fail "case12: up.sh exited 0 with no token"
  grep -qF './scripts/setup.sh' <<< "$out" && ok "case12: up.sh's stderr names ./scripts/setup.sh" \
    || fail "case12: up.sh's stderr did not name ./scripts/setup.sh: $out"
  [[ ! -e "$root/build-agent-images.called" ]] && ok "case12: build-agent-images.sh was never called" \
    || fail "case12: build-agent-images.sh was called before the refusal"
}

test_fresh_installs_differ
test_rerun_keeps
test_rotate
test_other_lines_untouched
test_no_value_is_missing
test_keeping_credentials_still_generates
test_no_value_printed
test_bad_argument
test_refusal
test_wiring
test_teardown_without_token
test_up_refuses_before_building

echo
echo "== $PASS passed, $FAIL failed =="
[[ "$FAIL" -eq 0 ]]
