#!/bin/bash
# Unit tests for build-preload.sh (no live docker daemon needed).
set -euo pipefail

PASS=0
FAIL=0
TESTDIR=$(mktemp -d)
trap 'rm -rf "$TESTDIR"' EXIT

ok() { echo "PASS: $1"; PASS=$((PASS+1)); }
fail() { echo "FAIL: $1"; FAIL=$((FAIL+1)); }

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT="$DIR/build-preload.sh"

# stub docker: `pull` logs the ref it was asked to pull (skipping flags like
# `-q`), `save` writes an empty file at the path following `-o`.
make_docker_stub() {
  local bin="$1" pulled_log="$2"
  mkdir -p "$bin"
  cat > "$bin/docker" <<EOF
#!/usr/bin/env bash
case "\$1" in
  pull)
    shift
    ref=""
    for a in "\$@"; do case "\$a" in -*) ;; *) ref="\$a" ;; esac; done
    echo "\$ref" >> "$pulled_log"
    exit 0
    ;;
  save) shift; while [ "\${1:-}" != "-o" ] && [ \$# -gt 0 ]; do shift; done; [ "\${1:-}" = "-o" ] && : > "\$2"; exit 0 ;;
  *) exit 0 ;;
esac
EOF
  chmod +x "$bin/docker"
}

# --- Test 1: archive is produced at OUT ---
BIN1="$TESTDIR/bin1"; PULLED1="$TESTDIR/pulled1.log"
make_docker_stub "$BIN1" "$PULLED1"
printf 'busybox:latest\npostgres:17-alpine\n' > "$TESTDIR/list1.txt"
PATH="$BIN1:$PATH" STACK_LIST="$TESTDIR/list1.txt" OUT="$TESTDIR/stack1.tar" bash "$SCRIPT" >/dev/null
[ -f "$TESTDIR/stack1.tar" ] \
  && ok "archive produced at OUT" || fail "archive produced at OUT"

# --- Test 2: every listed image is pulled ---
{ grep -q "^busybox:latest\$" "$PULLED1" && grep -q "^postgres:17-alpine\$" "$PULLED1"; } \
  && ok "every listed image is pulled" || fail "every listed image is pulled"

# --- Test 3: blank lines and comments in the list are skipped, not pulled ---
BIN3="$TESTDIR/bin3"; PULLED3="$TESTDIR/pulled3.log"
make_docker_stub "$BIN3" "$PULLED3"
printf '# a comment\n\nbusybox:latest\n' > "$TESTDIR/list3.txt"
PATH="$BIN3:$PATH" STACK_LIST="$TESTDIR/list3.txt" OUT="$TESTDIR/stack3.tar" bash "$SCRIPT" >/dev/null
[ "$(wc -l < "$PULLED3")" -eq 1 ] \
  && ok "comments and blank lines skipped" || fail "comments and blank lines skipped (got: $(cat "$PULLED3"))"

# --- Test 4: STACK_LIST defaults to preload-stack.txt next to the script ---
BIN4="$TESTDIR/bin4"; PULLED4="$TESTDIR/pulled4.log"
make_docker_stub "$BIN4" "$PULLED4"
(cd "$TESTDIR" && PATH="$BIN4:$PATH" OUT="$TESTDIR/stack4.tar" bash "$SCRIPT" >/dev/null)
[ -f "$TESTDIR/stack4.tar" ] \
  && ok "STACK_LIST defaults to preload-stack.txt next to the script" \
  || fail "STACK_LIST defaults to preload-stack.txt next to the script"

# --- Test 5: summary line reports the image count ---
BIN5="$TESTDIR/bin5"; PULLED5="$TESTDIR/pulled5.log"
make_docker_stub "$BIN5" "$PULLED5"
printf 'busybox:latest\npostgres:17-alpine\n' > "$TESTDIR/list5.txt"
SUMMARY=$(PATH="$BIN5:$PATH" STACK_LIST="$TESTDIR/list5.txt" OUT="$TESTDIR/stack5.tar" bash "$SCRIPT")
echo "$SUMMARY" | grep -q "saved 2 images" \
  && ok "summary line reports the image count" || fail "summary line reports the image count (got: $SUMMARY)"

# --- Summary ---
echo ""
echo "Results: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
