#!/bin/bash
# Unit tests for entrypoint.sh's warm-Docker-cache preload step (no live dind needed).
#
# run_preload() is defined and hooked behind a --preload-only flag precisely so this
# file can `source` the real, unmodified entrypoint.sh and exercise just that step
# through a stubbed `docker` on PATH, without it launching a real dockerd first.
set -euo pipefail

PASS=0
FAIL=0
TESTDIR=$(mktemp -d)
trap 'rm -rf "$TESTDIR"' EXIT

ok() { echo "PASS: $1"; PASS=$((PASS+1)); }
fail() { echo "FAIL: $1"; FAIL=$((FAIL+1)); }

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENTRYPOINT="$DIR/../entrypoint.sh"

# stub docker: branches on $1 to simulate daemon reachability and load outcome.
make_docker_stub() {
  local bin="$1" info_rc="$2" load_rc="$3"
  mkdir -p "$bin"
  cat > "$bin/docker" <<EOF
#!/usr/bin/env bash
case "\$1" in
  info) exit $info_rc ;;
  load) [ "$load_rc" -eq 0 ] && echo "Loaded image: stub"; exit $load_rc ;;
  *) exit 0 ;;
esac
EOF
  chmod +x "$bin/docker"
}

# --- Test 1: archive present, daemon reachable, load succeeds -> loaded + marker ---
BIN1="$TESTDIR/bin1"
make_docker_stub "$BIN1" 0 0
mkdir -p "$TESTDIR/preload1"; : > "$TESTDIR/preload1/stack.tar"
MARKER1="$TESTDIR/preload1/ready"
OUT1=$(PATH="$BIN1:$PATH" PRELOAD_DIR="$TESTDIR/preload1" READY_MARKER="$MARKER1" \
  bash -c 'source "'"$ENTRYPOINT"'" --preload-only 2>&1')
echo "$OUT1" | grep -q "preload: loaded" \
  && ok "archive present + daemon reachable + load succeeds: loads" \
  || fail "archive present + daemon reachable + load succeeds: loads (got: $OUT1)"
[ -f "$MARKER1" ] \
  && ok "archive present + load succeeds: readiness marker created" \
  || fail "archive present + load succeeds: readiness marker created"

# --- Test 2: no archives in the dir -> no-op, no failure ---
MARKER2="$TESTDIR/preload1/ready2"
mkdir -p "$TESTDIR/preload2"  # empty: contains no *.tar
OUT2=$(PATH="$BIN1:$PATH" PRELOAD_DIR="$TESTDIR/preload2" READY_MARKER="$MARKER2" \
  bash -c 'source "'"$ENTRYPOINT"'" --preload-only 2>&1')
echo "$OUT2" | grep -q "preload: none" \
  && ok "archive absent: no-op" \
  || fail "archive absent: no-op (got: $OUT2)"

# --- Test 3: archive present, daemon reachable, docker load fails -> fail loud ---
# The one case that must never degrade to a silent skip: a warm image whose daemon
# rejects the archive has to abort the sidecar, not ship a cold cache with no signal.
BIN3="$TESTDIR/bin3"
make_docker_stub "$BIN3" 0 1
mkdir -p "$TESTDIR/preload3"; : > "$TESTDIR/preload3/stack.tar"
set +e
OUT3=$(PATH="$BIN3:$PATH" PRELOAD_DIR="$TESTDIR/preload3" READY_MARKER="$TESTDIR/preload3/ready" \
  bash -c 'source "'"$ENTRYPOINT"'" --preload-only 2>&1')
RC3=$?
set -e
[ "$RC3" -ne 0 ] && ok "docker load fails: entrypoint exits non-zero" \
  || fail "docker load fails: entrypoint exits non-zero (got rc=$RC3)"
echo "$OUT3" | grep -q "preload: FATAL" \
  && ok "docker load fails: FATAL message on stderr" \
  || fail "docker load fails: FATAL message on stderr (got: $OUT3)"
[ ! -f "$TESTDIR/preload3/ready" ] \
  && ok "docker load fails: no readiness marker" \
  || fail "docker load fails: no readiness marker"

# --- Test 4: archive present, daemon never comes up -> fail loud ---
# wait_for_docker retries for up to 60 * 2s in production; a stubbed `sleep` (same
# PATH-stubbing technique as `docker` above, no test-only branch in entrypoint.sh
# itself) collapses that to an instant loop here.
BIN4="$TESTDIR/bin4"
make_docker_stub "$BIN4" 1 0
cat > "$BIN4/sleep" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
chmod +x "$BIN4/sleep"
mkdir -p "$TESTDIR/preload4"; : > "$TESTDIR/preload4/stack.tar"
set +e
OUT4=$(PATH="$BIN4:$PATH" PRELOAD_DIR="$TESTDIR/preload4" READY_MARKER="$TESTDIR/preload4/ready" \
  bash -c 'source "'"$ENTRYPOINT"'" --preload-only 2>&1')
RC4=$?
set -e
[ "$RC4" -ne 0 ] && ok "daemon never reachable: entrypoint exits non-zero" \
  || fail "daemon never reachable: entrypoint exits non-zero (got rc=$RC4)"
echo "$OUT4" | grep -q "preload: FATAL" \
  && ok "daemon never reachable: FATAL message on stderr" \
  || fail "daemon never reachable: FATAL message on stderr (got: $OUT4)"

# --- Test 5: multiple archives in the dir -> each loads (base + overlay additivity) ---
# The additive-preload contract: a base image's stack.tar and an image-built-FROM-it's
# own archive both sit in the dir, and each loads — so an overlay bakes only its delta
# instead of re-baking (and shadowing) the base's archive.
BIN5="$TESTDIR/bin5"
make_docker_stub "$BIN5" 0 0
mkdir -p "$TESTDIR/preload5"; : > "$TESTDIR/preload5/stack.tar"; : > "$TESTDIR/preload5/cloud-stack.tar"
OUT5=$(PATH="$BIN5:$PATH" PRELOAD_DIR="$TESTDIR/preload5" READY_MARKER="$TESTDIR/preload5/ready" \
  bash -c 'source "'"$ENTRYPOINT"'" --preload-only 2>&1')
[ "$(echo "$OUT5" | grep -c "preload: loaded")" -eq 2 ] \
  && ok "multiple archives: every *.tar loads" \
  || fail "multiple archives: every *.tar loads (got: $OUT5)"

# --- Summary ---
echo ""
echo "Results: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
