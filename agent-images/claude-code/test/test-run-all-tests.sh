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

# --- Test 3: JUnit XML failures are harvested into the index ---
# (Numbered 3 / S3 to avoid colliding with Test 2's S2 above, which stays live for
# the remainder of the script's execution.)
S3="$TESTDIR/s3"
mkdir -p "$S3/workspace/repo" "$S3/workspace/out"
XMLDIR="$S3/workspace/out/reports/widget/api-server/test-results/test"
mkdir -p "$XMLDIR"
cat > "$XMLDIR/TEST-com.acme.WidgetTest.xml" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="com.acme.WidgetTest" tests="2" failures="1">
  <testcase name="spinsFreely" classname="com.acme.WidgetTest" time="0.01"/>
  <testcase name="holdsTorque" classname="com.acme.WidgetTest" time="0.02">
    <failure message="expected:&lt;7&gt; but was:&lt;3&gt;" type="AssertionError">
com.acme.WidgetTest.holdsTorque(WidgetTest.java:42)
        at org.junit.Assert.fail(Assert.java:88)
    </failure>
  </testcase>
</testsuite>
EOF
# The report root is named in the command, exactly as the real seeders write it, so
# reports_root_for parses it back out and finds the fixture tree above.
cat > "$S3/workspace/config.json" <<EOF
{
  "repo_url": "https://example.invalid/acme/widget.git",
  "test_command": "echo running -Dtest.reports.dir=$S3/workspace/out/reports/widget ; exit 1"
}
EOF
COPY3=$(make_copy "$S3")
set +e
bash "$COPY3" > "$S3/stdout.txt" 2>&1
RC3=$?
set -e
[ "$RC3" -eq 1 ] && ok "failing command: exits 1" || fail "failing command: exits 1 (got $RC3)"

REPORT3="$S3/workspace/out/test_report.md"
grep -qF "holdsTorque" "$REPORT3" \
    && ok "index names the failing test" || fail "index names the failing test"
grep -qF "expected:<7> but was:<3>" "$REPORT3" \
    && ok "index decodes the XML-escaped failure message" \
    || fail "index decodes the failure message"
grep -qF "spinsFreely" "$REPORT3" \
    && fail "index must NOT list the passing test" || ok "index omits passing tests"
grep -qF "api-server" "$REPORT3" \
    && ok "index attributes the failure to its component" \
    || fail "index attributes the failure to its component"

# --- Test 4: Playwright JSON failures are harvested ---
# (Numbered 4 / S4 to avoid colliding with Test 3's S3/COPY3/RC3 above, which stay live
# for the remainder of the script's execution.)
S4="$TESTDIR/s4"
mkdir -p "$S4/workspace/repo" "$S4/workspace/out/reports/widget/playwright"
cat > "$S4/workspace/out/reports/widget/playwright/results.json" <<'EOF'
{
  "suites": [
    {
      "title": "login.spec.ts",
      "specs": [
        {
          "title": "signs a user in",
          "ok": false,
          "tests": [{"results": [{"status": "failed",
            "error": {"message": "locator.click: Timeout 5000ms exceeded"}}]}]
        },
        {
          "title": "shows the dashboard",
          "ok": true,
          "tests": [{"results": [{"status": "passed"}]}]
        }
      ]
    }
  ]
}
EOF
cat > "$S4/workspace/config.json" <<EOF
{
  "repo_url": "https://example.invalid/acme/widget.git",
  "test_command": "echo pw -Dtest.reports.dir=$S4/workspace/out/reports/widget ; exit 1"
}
EOF
COPY4=$(make_copy "$S4")
set +e
bash "$COPY4" > "$S4/stdout.txt" 2>&1
set -e
REPORT4="$S4/workspace/out/test_report.md"
grep -qF "signs a user in" "$REPORT4" \
    && ok "index names the failing spec" || fail "index names the failing spec"
grep -qF "Timeout 5000ms exceeded" "$REPORT4" \
    && ok "index carries the spec's error message" || fail "index carries the spec error"
grep -qF "shows the dashboard" "$REPORT4" \
    && fail "index must NOT list the passing spec" || ok "index omits passing specs"

# --- Test 5: absent results.json is silent, not an error ---
S5="$TESTDIR/s5"
mkdir -p "$S5/workspace/repo" "$S5/workspace/out/reports/widget/playwright"
cat > "$S5/workspace/config.json" <<EOF
{
  "repo_url": "https://example.invalid/acme/widget.git",
  "test_command": "echo none -Dtest.reports.dir=$S5/workspace/out/reports/widget ; exit 0"
}
EOF
COPY5=$(make_copy "$S5")
set +e
bash "$COPY5" > "$S5/stdout.txt" 2>&1
RC5=$?
set -e
[ "$RC5" -eq 0 ] && ok "missing results.json: still exits 0" \
    || fail "missing results.json: still exits 0 (got $RC5)"
grep -qiF "playwright" "$S5/workspace/out/test_report.md" \
    && fail "missing results.json must add no Playwright section" \
    || ok "missing results.json: no Playwright section"

# --- Summary ---
echo ""
echo "Results: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
