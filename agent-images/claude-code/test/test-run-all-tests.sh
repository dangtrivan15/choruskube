#!/bin/bash
# Unit tests for run-all-tests (no live container needed).
#
# run-all-tests is the Test node's entire implementation (executor_type=script,
# command=run-all-tests). It runs each repo's test_command, harvests the failing tests
# out of the report tree, archives each component's reports, and writes the
# /workspace/out/test_report.md index that every downstream node reads via run_log.md.
#
# The script hardcodes /workspace paths, so each test runs it through a copy whose
# /workspace prefix is rewritten to a mktemp sandbox — the same technique
# test-check-prs.sh uses for CONFIG_FILE.
set -euo pipefail

PASS=0
FAIL=0
TESTDIR=$(mktemp -d)
trap 'rm -rf "$TESTDIR"' EXIT

ok() { echo "PASS: $1"; PASS=$((PASS+1)); }
fail() { echo "FAIL: $1"; FAIL=$((FAIL+1)); }

SRC_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# A copy of run-all-tests with /workspace rewritten to a per-test sandbox root.
# Echoes the copy's path. $1 = sandbox root.
make_copy() {
    local sandbox="$1"
    local copy="$sandbox/run-all-tests"
    mkdir -p "$sandbox"
    sed "s#/workspace#$sandbox/workspace#g" "$SRC_DIR/run-all-tests" > "$copy"
    chmod +x "$copy"
    # harvest-junit.js is resolved next to the script; keep the copy's sibling layout.
    if [ -f "$SRC_DIR/harvest-junit.js" ]; then
        cp "$SRC_DIR/harvest-junit.js" "$sandbox/harvest-junit.js"
    fi
    printf '%s' "$copy"
}

# --- Test 1: full output is captured, not truncated to 100 lines ---
S1="$TESTDIR/s1"
mkdir -p "$S1/workspace/repo" "$S1/workspace/out"
cat > "$S1/workspace/config.json" <<EOF
{
  "repo_url": "https://example.invalid/acme/widget.git",
  "test_command": "for i in \$(seq 1 250); do echo line-\$i; done; exit 0"
}
EOF
COPY=$(make_copy "$S1")
set +e
bash "$COPY" > "$S1/stdout.txt" 2>&1
RC=$?
set -e
[ "$RC" -eq 0 ] && ok "passing command: exits 0" || fail "passing command: exits 0 (got $RC)"

# The command sets no -Dtest.reports.dir, so the report tree does not exist and the log
# falls back to /workspace/out/<name>-test-output.log — hence the leading glob.
LOG=$(find "$S1/workspace/out" -name '*test-output.log' | head -1)
[ -n "$LOG" ] && ok "full output log written" || fail "full output log written"
if [ -n "$LOG" ]; then
    grep -qx "line-1" "$LOG" \
        && ok "log keeps the FIRST line (tail -100 would have dropped it)" \
        || fail "log keeps the first line"
    grep -qx "line-250" "$LOG" && ok "log keeps the last line" || fail "log keeps the last line"
fi

# --- Summary ---
echo ""
echo "Results: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
