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
# A bare substring match on "playwright" also matches the report's Archived-reports
# manifest, which names archives after their source directory (this fixture's empty
# playwright/ dir archives to `playwright.tar.gz`). Match the harvester's own heading
# text so this only detects an actual Failing-tests/Playwright section.
grep -qF "#### playwright" "$S5/workspace/out/test_report.md" \
    && fail "missing results.json must add no Playwright section" \
    || ok "missing results.json: no Playwright section"

# --- Test 6: both harvesters empty, reports root never created — no heading at all ---
# Guards the FAILING_SECTION collapse in run-all-tests directly: a regression that
# leaves FAILING_SECTION non-empty whitespace (heading prints, body empty) must fail
# this assertion, not just an absence-of-substring check.
S6="$TESTDIR/s6"
mkdir -p "$S6/workspace/repo" "$S6/workspace/out"
cat > "$S6/workspace/config.json" <<EOF
{
  "repo_url": "https://example.invalid/acme/widget.git",
  "test_command": "echo hi -Dtest.reports.dir=$S6/workspace/out/reports/widget ; exit 0"
}
EOF
COPY6=$(make_copy "$S6")
set +e
bash "$COPY6" > "$S6/stdout.txt" 2>&1
RC6=$?
set -e
[ "$RC6" -eq 0 ] && ok "reports root never created: still exits 0" \
    || fail "reports root never created: still exits 0 (got $RC6)"
REPORT6="$S6/workspace/out/test_report.md"
grep -qF "### Failing tests" "$REPORT6" \
    && fail "reports root never created: no Failing tests heading" \
    || ok "reports root never created: no Failing tests heading"

# --- Test 7: both harvesters empty, reports root exists but is empty — no heading ---
S7="$TESTDIR/s7"
mkdir -p "$S7/workspace/repo" "$S7/workspace/out/reports/widget"
cat > "$S7/workspace/config.json" <<EOF
{
  "repo_url": "https://example.invalid/acme/widget.git",
  "test_command": "echo hi -Dtest.reports.dir=$S7/workspace/out/reports/widget ; exit 0"
}
EOF
COPY7=$(make_copy "$S7")
set +e
bash "$COPY7" > "$S7/stdout.txt" 2>&1
RC7=$?
set -e
[ "$RC7" -eq 0 ] && ok "reports root exists but empty: still exits 0" \
    || fail "reports root exists but empty: still exits 0 (got $RC7)"
REPORT7="$S7/workspace/out/test_report.md"
grep -qF "### Failing tests" "$REPORT7" \
    && fail "reports root exists but empty: no Failing tests heading" \
    || ok "reports root exists but empty: no Failing tests heading"

# --- Test 8: components are archived and originals removed ---
S8="$TESTDIR/s8"
mkdir -p "$S8/workspace/repo" "$S8/workspace/out"
mkdir -p "$S8/workspace/out/reports/widget/api-server/jacoco/html"
mkdir -p "$S8/workspace/out/reports/widget/web-ui"
for i in 1 2 3; do echo "<html>$i</html>" > "$S8/workspace/out/reports/widget/api-server/jacoco/html/c$i.html"; done
echo "coverage" > "$S8/workspace/out/reports/widget/web-ui/index.html"
echo "task	1" > "$S8/workspace/out/reports/widget/timings.tsv"
cat > "$S8/workspace/config.json" <<EOF
{
  "repo_url": "https://example.invalid/acme/widget.git",
  "test_command": "echo arch -Dtest.reports.dir=$S8/workspace/out/reports/widget ; exit 0",
  "output_path": "runs/r-arch/out/"
}
EOF
COPY8=$(make_copy "$S8")
set +e
bash "$COPY8" > "$S8/stdout.txt" 2>&1
set -e
R8="$S8/workspace/out/reports/widget"
[ -f "$R8/api-server.tar.gz" ] && ok "api-server archived" || fail "api-server archived"
[ -f "$R8/web-ui.tar.gz" ] && ok "web-ui archived" || fail "web-ui archived"
[ ! -d "$R8/api-server" ] && ok "api-server originals removed" || fail "api-server originals removed"
[ ! -d "$R8/web-ui" ] && ok "web-ui originals removed" || fail "web-ui originals removed"
[ -f "$R8/timings.tsv" ] && ok "loose files left unarchived" || fail "loose files left unarchived"
[ -f "$R8/test-output.log" ] && ok "output log left unarchived" || fail "output log left unarchived"
tar -tzf "$R8/api-server.tar.gz" | grep -q "jacoco/html/c1.html" \
    && ok "archive preserves the nested report tree" || fail "archive preserves nested tree"
FILECOUNT=$(find "$S8/workspace/out" -type f | wc -l | tr -d ' ')
[ "$FILECOUNT" -le 8 ] && ok "artifact count collapsed (got $FILECOUNT)" \
    || fail "artifact count collapsed (got $FILECOUNT)"
grep -qF "api-server.tar.gz" "$S8/workspace/out/test_report.md" \
    && ok "index manifests the archives" || fail "index manifests the archives"
grep -qF "runs/r-arch/out/reports/widget/<name>.tar.gz" "$S8/workspace/out/test_report.md" \
    && ok "index prints a copy-pasteable artifact get with output_path" \
    || fail "index prints a copy-pasteable artifact get with output_path"

# --- Test 9: output_path absent degrades to a relative path, not a literal "null" ---
S9="$TESTDIR/s9"
mkdir -p "$S9/workspace/repo" "$S9/workspace/out/reports/widget/api-server"
echo "<html>1</html>" > "$S9/workspace/out/reports/widget/api-server/c1.html"
cat > "$S9/workspace/config.json" <<EOF
{
  "repo_url": "https://example.invalid/acme/widget.git",
  "test_command": "echo noop -Dtest.reports.dir=$S9/workspace/out/reports/widget ; exit 0"
}
EOF
COPY9=$(make_copy "$S9")
set +e
bash "$COPY9" > "$S9/stdout.txt" 2>&1
set -e
REPORT9="$S9/workspace/out/test_report.md"
grep -qF "artifact get reports/widget/<name>.tar.gz" "$REPORT9" \
    && ok "missing output_path: drill-in degrades to a bare relative path" \
    || fail "missing output_path: drill-in degrades to a bare relative path"
grep -qF "null" "$REPORT9" \
    && fail "missing output_path: no literal null in the drill-in" \
    || ok "missing output_path: no literal null in the drill-in"

# --- Test 10: a tar failure is reported, not fatal, and leaves originals in place ---
# A fake `tar` on PATH that always fails stands in for a real packaging failure
# (disk full, corrupt tree, etc.) without depending on file-permission behavior, which
# varies by execution privilege. Prepended to PATH rather than touching S9/S8's fixtures
# so the earlier scenarios keep exercising the real `tar`.
S10="$TESTDIR/s10"
FAKEBIN="$TESTDIR/fakebin10"
mkdir -p "$S10/workspace/repo" "$S10/workspace/out/reports/widget/api-server" "$FAKEBIN"
echo "<html>1</html>" > "$S10/workspace/out/reports/widget/api-server/c1.html"
cat > "$FAKEBIN/tar" <<'STUB'
#!/usr/bin/env bash
echo "tar: simulated failure for test" >&2
exit 2
STUB
chmod +x "$FAKEBIN/tar"
cat > "$S10/workspace/config.json" <<EOF
{
  "repo_url": "https://example.invalid/acme/widget.git",
  "test_command": "echo x -Dtest.reports.dir=$S10/workspace/out/reports/widget ; exit 0"
}
EOF
COPY10=$(make_copy "$S10")
set +e
PATH="$FAKEBIN:$PATH" bash "$COPY10" > "$S10/stdout.txt" 2>&1
RC10=$?
set -e
[ "$RC10" -eq 0 ] && ok "tar failure: node still exits 0" || fail "tar failure: node still exits 0 (got $RC10)"
R10="$S10/workspace/out/reports/widget"
[ -d "$R10/api-server" ] && ok "tar failure: originals left in place" || fail "tar failure: originals left in place"
[ ! -f "$R10/api-server.tar.gz" ] && ok "tar failure: no partial archive left behind" \
    || fail "tar failure: no partial archive left behind"
grep -qF "Archiving notes" "$S10/workspace/out/test_report.md" \
    && ok "tar failure: index records an archiving note" || fail "tar failure: index records an archiving note"
grep -qF "api-server" "$S10/workspace/out/test_report.md" \
    && ok "tar failure: note names the affected component" || fail "tar failure: note names the affected component"

# --- Test 11: ARCHIVE_NOTES does not bleed from one repo into the next repo's section ---
# True multi-repo mode (config.json's `repos[]`, not the single-repo top-level test_command
# every earlier scenario uses): repo alpha has one component whose archiving fails, repo beta
# archives cleanly. Only alpha's report section should carry the failure note.
#
# The fake `tar` only intercepts the one target path ending in broken.tar.gz and delegates
# everything else to the real tar (resolved once, up front) — a global PATH shadow would break
# beta's own (unrelated) archiving too, which would defeat the point of this scenario.
S11="$TESTDIR/s11"
FAKEBIN11="$TESTDIR/fakebin11"
REAL_TAR="$(command -v tar)"
mkdir -p "$S11/workspace/repo/alpha" "$S11/workspace/repo/beta" "$FAKEBIN11"
mkdir -p "$S11/workspace/out/reports/alpha/broken" "$S11/workspace/out/reports/beta/clean"
echo "<html>1</html>" > "$S11/workspace/out/reports/alpha/broken/f.html"
echo "<html>1</html>" > "$S11/workspace/out/reports/beta/clean/f.html"
cat > "$FAKEBIN11/tar" <<STUB
#!/usr/bin/env bash
for arg in "\$@"; do
  case "\$arg" in
    *broken.tar.gz) echo "tar: simulated failure for test" >&2; exit 2 ;;
  esac
done
exec "$REAL_TAR" "\$@"
STUB
chmod +x "$FAKEBIN11/tar"
cat > "$S11/workspace/config.json" <<EOF
{
  "repos": [
    {
      "name": "alpha",
      "local_path": "$S11/workspace/repo/alpha",
      "test_command": "echo a -Dtest.reports.dir=$S11/workspace/out/reports/alpha ; exit 0"
    },
    {
      "name": "beta",
      "local_path": "$S11/workspace/repo/beta",
      "test_command": "echo b -Dtest.reports.dir=$S11/workspace/out/reports/beta ; exit 0"
    }
  ]
}
EOF
COPY11=$(make_copy "$S11")
set +e
PATH="$FAKEBIN11:$PATH" bash "$COPY11" > "$S11/stdout.txt" 2>&1
RC11=$?
set -e
[ "$RC11" -eq 0 ] && ok "multi-repo bleed: node still exits 0" || fail "multi-repo bleed: node still exits 0 (got $RC11)"
REPORT11="$S11/workspace/out/test_report.md"
ALPHA_SECTION=$(sed -n '/^## alpha/,/^## beta/p' "$REPORT11")
BETA_SECTION=$(sed -n '/^## beta/,$p' "$REPORT11")
printf '%s' "$ALPHA_SECTION" | grep -qF "Archiving notes" \
    && ok "multi-repo bleed: alpha's own section records its archiving note" \
    || fail "multi-repo bleed: alpha's own section records its archiving note"
printf '%s' "$BETA_SECTION" | grep -qF "clean.tar.gz" \
    && ok "multi-repo bleed: beta's own archiving still succeeds" \
    || fail "multi-repo bleed: beta's own archiving still succeeds"
printf '%s' "$BETA_SECTION" | grep -qF "broken" \
    && fail "multi-repo bleed: beta's section must NOT carry alpha's note" \
    || ok "multi-repo bleed: beta's section must NOT carry alpha's note"

# --- Summary ---
echo ""
echo "Results: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
