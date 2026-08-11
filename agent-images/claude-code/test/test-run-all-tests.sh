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

# --- Test 2: -Dtest.reports.dir is parsed out of the command and used, not the
# <name>-test-output.log fallback. This exercises reports_root_for's parsing branch —
# Test 1 only ever exercises its fallback branch, so a broken regex/cut there could
# still leave Test 1 green.
S2="$TESTDIR/s2"
REPORTS_DIR="$S2/workspace/out/reports/widget-reports"
mkdir -p "$S2/workspace/repo" "$S2/workspace/out" "$REPORTS_DIR"
cat > "$S2/workspace/config.json" <<EOF
{
  "repo_url": "https://example.invalid/acme/widget.git",
  "test_command": "true -Dtest.reports.dir=$REPORTS_DIR && for i in \$(seq 1 5); do echo line-\$i; done; exit 0"
}
EOF
COPY2=$(make_copy "$S2")
set +e
bash "$COPY2" > "$S2/stdout.txt" 2>&1
RC2=$?
set -e
[ "$RC2" -eq 0 ] && ok "reports.dir scenario: exits 0" || fail "reports.dir scenario: exits 0 (got $RC2)"

EXPECTED_LOG="$REPORTS_DIR/test-output.log"
[ -f "$EXPECTED_LOG" ] \
    && ok "output log lands inside the parsed -Dtest.reports.dir, not the name fallback" \
    || fail "output log lands inside the parsed -Dtest.reports.dir (expected $EXPECTED_LOG)"

FALLBACK_LOG=$(find "$S2/workspace/out" -maxdepth 1 -name '*test-output.log')
[ -z "$FALLBACK_LOG" ] \
    && ok "no <name>-test-output.log fallback written when reports.dir parses" \
    || fail "unexpected fallback log written: $FALLBACK_LOG"

if [ -f "$EXPECTED_LOG" ]; then
    grep -qx "line-5" "$EXPECTED_LOG" \
        && ok "parsed-path log contains the command's output" \
        || fail "parsed-path log contains the command's output"
fi

# --- Summary ---
echo ""
echo "Results: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
