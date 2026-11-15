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
# M4 regression: this fixture's failing section comes ONLY from harvest_playwright (no
# JUnit XML in the tree at all), which is exactly the case that lost its blank line — $()
# strips the harvester's own trailing blank, so "Full output:" rendered as a lazy
# continuation of the last "  - message" bullet instead of its own paragraph.
BLANK_BEFORE_FULL_OUTPUT4=$(awk '/^Full output:/{print prev; exit} {prev=$0}' "$REPORT4")
[ -z "$BLANK_BEFORE_FULL_OUTPUT4" ] \
    && ok "blank line separates the failing section from Full output:" \
    || fail "blank line separates the failing section from Full output: (got [$BLANK_BEFORE_FULL_OUTPUT4])"

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
grep -q "jacoco/html/c1.html" <<< "$(tar -tzf "$R8/api-server.tar.gz")" \
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
grep -qF "Archiving notes" <<< "$ALPHA_SECTION" \
    && ok "multi-repo bleed: alpha's own section records its archiving note" \
    || fail "multi-repo bleed: alpha's own section records its archiving note"
grep -qF "clean.tar.gz" <<< "$BETA_SECTION" \
    && ok "multi-repo bleed: beta's own archiving still succeeds" \
    || fail "multi-repo bleed: beta's own archiving still succeeds"
grep -qF "broken" <<< "$BETA_SECTION" \
    && fail "multi-repo bleed: beta's section must NOT carry alpha's note" \
    || ok "multi-repo bleed: beta's section must NOT carry alpha's note"

# --- Test 12: reports_root_for's parsed value stops at ;, &, | with no space needed ---
# Test 2 above already covers the parsing branch, but its fixture inserts a space before
# the `;` to sidestep this exact bug. This scenario is the no-space form: a trailing `;`
# glued directly onto the path, the way a user-authored test_command can write it.
S12="$TESTDIR/s12"
REPORTS_DIR12="$S12/workspace/out/reports/widget-reports"
mkdir -p "$S12/workspace/repo" "$S12/workspace/out" "$REPORTS_DIR12"
cat > "$S12/workspace/config.json" <<EOF
{
  "repo_url": "https://example.invalid/acme/widget.git",
  "test_command": "true -Dtest.reports.dir=$REPORTS_DIR12;exit 0"
}
EOF
COPY12=$(make_copy "$S12")
set +e
bash "$COPY12" > "$S12/stdout.txt" 2>&1
RC12=$?
set -e
[ "$RC12" -eq 0 ] && ok "no-space punctuation: exits 0" || fail "no-space punctuation: exits 0 (got $RC12)"
EXPECTED_LOG12="$REPORTS_DIR12/test-output.log"
[ -f "$EXPECTED_LOG12" ] \
    && ok "no-space punctuation: log lands in the parsed dir, trailing ; not swallowed into it" \
    || fail "no-space punctuation: log lands in the parsed dir (expected $EXPECTED_LOG12)"
FALLBACK_LOG12=$(find "$S12/workspace/out" -maxdepth 1 -name '*test-output.log')
[ -z "$FALLBACK_LOG12" ] \
    && ok "no-space punctuation: no fallback log written (the bogus path never matched a dir)" \
    || fail "no-space punctuation: unexpected fallback log written: $FALLBACK_LOG12"

# --- Test 13: a <failure> inside a CDATA-wrapped <system-out> is not a real failure ---
# harvest-junit.js matches on raw text, so a <system-out> that literally captured the
# string "<failure ...>" (e.g. a debug line echoing a request/response payload) would be
# read as a real failure before the CDATA pre-pass. The suite's own failures="0" and the
# absence of a real <failure> element outside the CDATA are what prove this one is fake.
S13="$TESTDIR/s13"
mkdir -p "$S13/workspace/repo" "$S13/workspace/out"
XMLDIR13="$S13/workspace/out/reports/widget/api-server/test-results/test"
mkdir -p "$XMLDIR13"
cat > "$XMLDIR13/TEST-com.acme.CdataTest.xml" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="com.acme.CdataTest" tests="1" failures="0">
  <testcase name="logsSuspiciousOutput" classname="com.acme.CdataTest" time="0.01">
    <system-out><![CDATA[
some debug line
<failure message="phantom failure from captured stdout" type="AssertionError">fake stack</failure>
more debug output
]]></system-out>
  </testcase>
</testsuite>
EOF
cat > "$S13/workspace/config.json" <<EOF
{
  "repo_url": "https://example.invalid/acme/widget.git",
  "test_command": "echo cdata -Dtest.reports.dir=$S13/workspace/out/reports/widget ; exit 0"
}
EOF
COPY13=$(make_copy "$S13")
set +e
bash "$COPY13" > "$S13/stdout.txt" 2>&1
RC13=$?
set -e
[ "$RC13" -eq 0 ] && ok "CDATA fixture: exits 0" || fail "CDATA fixture: exits 0 (got $RC13)"
REPORT13="$S13/workspace/out/test_report.md"
grep -qF "phantom failure from captured stdout" "$REPORT13" \
    && fail "CDATA-embedded <failure> must NOT be reported as a real failure" \
    || ok "CDATA-embedded <failure> is not reported as a real failure"
grep -qF "logsSuspiciousOutput" "$REPORT13" \
    && fail "a passing test must not be listed as failing due to CDATA-embedded text" \
    || ok "passing test with CDATA-embedded fake failure is not listed as failing"

# --- Test 14: a malformed (truncated mid-write) JUnit XML file is noted, not silently
# treated as "no failures" ---
# Simulates a test JVM killed mid-write: the file has no closing tags at all. This must
# not read the same as an absent report tree, which is the one case that stays silent.
S14="$TESTDIR/s14"
mkdir -p "$S14/workspace/repo" "$S14/workspace/out"
XMLDIR14="$S14/workspace/out/reports/widget/api-server/test-results/test"
mkdir -p "$XMLDIR14"
printf '%s' '<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="com.acme.BrokenTest" tests="1" failures="1">
  <testcase name="crashedMidWrite" classname="com.acme.BrokenTest" time="0.03">
    <failure message="disk full during write" type="IOException">
      com.acme.BrokenTest.crashedMidWrite(BrokenTest.java:1' > "$XMLDIR14/TEST-com.acme.BrokenTest.xml"
cat > "$S14/workspace/config.json" <<EOF
{
  "repo_url": "https://example.invalid/acme/widget.git",
  "test_command": "echo broken -Dtest.reports.dir=$S14/workspace/out/reports/widget ; exit 0"
}
EOF
COPY14=$(make_copy "$S14")
set +e
bash "$COPY14" > "$S14/stdout.txt" 2>&1
RC14=$?
set -e
[ "$RC14" -eq 0 ] && ok "malformed XML: node still exits 0" || fail "malformed XML: node still exits 0 (got $RC14)"
REPORT14="$S14/workspace/out/test_report.md"
grep -qiF "could not be" "$REPORT14" \
    && ok "malformed XML: index notes the unparseable report file" \
    || fail "malformed XML: index notes the unparseable report file"
grep -qF "### Failing tests" "$REPORT14" \
    && ok "malformed XML: the note surfaces under the index's Failing-tests section" \
    || fail "malformed XML: the note surfaces in the index"

# --- Affected-only scope: config.json test_affected_only ---
# Each scenario clones one bare origin per repo into <sandbox>/workspace/repo/<name>, the
# layout entrypoint.sh's clone leaves, so run-all-tests' git checks see a real origin/HEAD.
# Every test_command touches <sandbox>/ran/<name>: the files there are the repos that ran.
SKIP_LINE='**Status:** SKIPPED (unaffected — no change in this repo or any repo it depends on)'

# The developer's own git config must not reach the fixtures: a global signing key or hook
# would fail or slow every commit.
fixture_git() {
    git -c user.name=fixture -c user.email=fixture@example.invalid \
        -c commit.gpgsign=false -c core.hooksPath=/dev/null "$@"
}

# $1 sandbox, $2 repo name: a bare origin with `main` pushed and origin/HEAD set, cloned to
# $1/workspace/repo/$2, with `ignored.txt` gitignored.
make_repo() {
    local sandbox="$1" name="$2"
    local origin="$sandbox/origins/$name.git" dir="$sandbox/workspace/repo/$name"
    mkdir -p "$sandbox/origins" "$sandbox/workspace/repo" "$sandbox/workspace/out"
    fixture_git init -q --bare "$origin"
    fixture_git clone -q "$origin" "$dir" 2> /dev/null
    fixture_git -C "$dir" symbolic-ref HEAD refs/heads/main
    printf 'ignored.txt\n' > "$dir/.gitignore"
    echo base > "$dir/README"
    fixture_git -C "$dir" add -A
    fixture_git -C "$dir" commit -q -m base
    fixture_git -C "$dir" push -q origin main 2> /dev/null
    fixture_git -C "$dir" remote set-head origin main > /dev/null
}

# An unpushed commit on top of origin/main in repo $2.
commit_in() {
    local dir="$1/workspace/repo/$2"
    echo change >> "$dir/README"
    fixture_git -C "$dir" commit -q -am change
}

# $1 sandbox, $2 test_affected_only ("true", or "" to leave the key out), then one
# "name:dep,dep" per repo in repos[] order; a "name!" gives that repo no test_command.
write_config() {
    local sandbox="$1" flag="$2" repos="[]" spec name deps cmd
    shift 2
    for spec in "$@"; do
        name="${spec%%:*}"
        deps="${spec#*:}"
        cmd=""
        case "$name" in
            *!) name="${name%!}" ;;
            *) cmd="mkdir -p '$sandbox/ran' && touch '$sandbox/ran/$name'" ;;
        esac
        repos=$(jq -c --arg n "$name" --arg p "$sandbox/workspace/repo/$name" --arg c "$cmd" --arg d "$deps" '
            . + [ {name: $n, local_path: $p}
                  + (if $c == "" then {} else {test_command: $c} end)
                  + (if $d == "" then {} else {depends_on: ($d | split(","))} end) ]' <<< "$repos")
    done
    jq -n --argjson repos "$repos" --arg flag "$flag" \
        '{repos: $repos} + (if $flag == "" then {} else {test_affected_only: ($flag == "true")} end)' \
        > "$sandbox/workspace/config.json"
}

# Runs the sandbox's copy and sets RC. A watchdog kills it after 60s, so a scope computation
# that never terminates fails its case instead of hanging the whole suite.
run_scoped() {
    local sandbox="$1" copy pid watchdog
    copy=$(make_copy "$sandbox")
    set +e
    bash "$copy" > "$sandbox/stdout.txt" 2>&1 &
    pid=$!
    # Kills its own sleep when stopped, or every case would leave one behind for 60s.
    ( trap 'kill "$s" 2> /dev/null; exit 0' TERM; sleep 60 & s=$!; wait "$s"; kill -9 "$pid" ) \
        > /dev/null 2>&1 &
    watchdog=$!
    wait "$pid"
    RC=$?
    kill "$watchdog" 2> /dev/null
    wait "$watchdog" 2> /dev/null
    set -e
}

# The repos whose test_command ran, sorted and space-separated.
ran_set() {
    [ -d "$1/ran" ] || return 0
    ls "$1/ran" | sort | tr '\n' ' ' | sed 's/ $//'
}

# The "## $2" section of report $1, heading included, up to the next "## " heading.
section_of() {
    awk -v h="## $2" '$0 == h {on = 1; print; next} on && /^## / {exit} on {print}' "$1"
}

# Exit 0 when the "## $2" section of report $1 has a line equal to $3. Matched on a captured
# copy: piping into grep -q can SIGPIPE the writer and fail a match that succeeded.
section_has_line() {
    local sec
    sec=$(section_of "$1" "$2")
    grep -qxF -- "$3" <<< "$sec"
}

expect_eq() {
    [ "$2" = "$3" ] && ok "$1" || fail "$1 (got [$2], want [$3])"
}

# affected_off_runs_all: with the switch absent every repo runs and the report has no scope or skip text.
SA="$TESTDIR/affected_off_runs_all"
make_repo "$SA" x; make_repo "$SA" y
write_config "$SA" "" "x:y" "y:"
run_scoped "$SA"
expect_eq "affected_off_runs_all: exits 0" "$RC" 0
expect_eq "affected_off_runs_all: every repo ran" "$(ran_set "$SA")" "x y"
grep -qF "Test scope" "$SA/workspace/out/test_report.md" \
    && fail "affected_off_runs_all: no Test scope section" || ok "affected_off_runs_all: no Test scope section"
grep -qF "unaffected" "$SA/workspace/out/test_report.md" \
    && fail "affected_off_runs_all: nothing reported unaffected" || ok "affected_off_runs_all: nothing reported unaffected"

# leaf_change_runs_only_leaf: x depends on y; only x changed, and nothing depends on x.
SB="$TESTDIR/leaf_change_runs_only_leaf"
make_repo "$SB" x; make_repo "$SB" y
commit_in "$SB" x
write_config "$SB" true "x:y" "y:"
run_scoped "$SB"
expect_eq "leaf_change_runs_only_leaf: exits 0" "$RC" 0
expect_eq "leaf_change_runs_only_leaf: only the changed leaf ran" "$(ran_set "$SB")" "x"
section_has_line "$SB/workspace/out/test_report.md" y "$SKIP_LINE" \
    && ok "leaf_change_runs_only_leaf: y carries the exact skip line" \
    || fail "leaf_change_runs_only_leaf: y carries the exact skip line"

# dependency_change_runs_dependents: x depends on y; y changed.
SC="$TESTDIR/dependency_change_runs_dependents"
make_repo "$SC" x; make_repo "$SC" y
commit_in "$SC" y
write_config "$SC" true "x:y" "y:"
run_scoped "$SC"
expect_eq "dependency_change_runs_dependents: exits 0" "$RC" 0
expect_eq "dependency_change_runs_dependents: the dependency and its dependent ran" "$(ran_set "$SC")" "x y"

# transitive_chain: a -> b -> c; c changed.
SD="$TESTDIR/transitive_chain"
make_repo "$SD" a; make_repo "$SD" b; make_repo "$SD" c
commit_in "$SD" c
write_config "$SD" true "a:b" "b:c" "c:"
run_scoped "$SD"
expect_eq "transitive_chain: exits 0" "$RC" 0
expect_eq "transitive_chain: every transitive dependent ran" "$(ran_set "$SD")" "a b c"

# middle_repo_without_suite_propagates: a -> b -> c, b has no test_command; c changed.
SE="$TESTDIR/middle_repo_without_suite_propagates"
make_repo "$SE" a; make_repo "$SE" b; make_repo "$SE" c
commit_in "$SE" c
write_config "$SE" true "a:b" "b!:c" "c:"
run_scoped "$SE"
expect_eq "middle_repo_without_suite_propagates: exits 0" "$RC" 0
expect_eq "middle_repo_without_suite_propagates: a still ran through suite-less b" "$(ran_set "$SE")" "a c"
section_has_line "$SE/workspace/out/test_report.md" b "**Status:** SKIPPED (no test_command configured)" \
    && ok "middle_repo_without_suite_propagates: b keeps the no-test_command skip" \
    || fail "middle_repo_without_suite_propagates: b keeps the no-test_command skip"

# nothing_changed_runs_nothing_exit0: switch on, no repo changed.
SF="$TESTDIR/nothing_changed_runs_nothing_exit0"
make_repo "$SF" x; make_repo "$SF" y; make_repo "$SF" z
write_config "$SF" true "x:y" "y:" "z!:"
run_scoped "$SF"
REPORTF="$SF/workspace/out/test_report.md"
expect_eq "nothing_changed_runs_nothing_exit0: exits 0" "$RC" 0
expect_eq "nothing_changed_runs_nothing_exit0: nothing ran" "$(ran_set "$SF")" ""
for r in x y; do
    section_has_line "$REPORTF" "$r" "$SKIP_LINE" \
        && ok "nothing_changed_runs_nothing_exit0: $r carries the skip line" \
        || fail "nothing_changed_runs_nothing_exit0: $r carries the skip line"
done
grep -qF "PASSED" "$REPORTF" \
    && fail "nothing_changed_runs_nothing_exit0: nothing reported PASSED" \
    || ok "nothing_changed_runs_nothing_exit0: nothing reported PASSED"
grep -qF "No test_command configured for any repo" "$REPORTF" "$SF/stdout.txt" \
    && fail "nothing_changed_runs_nothing_exit0: does not claim no repo has a test_command" \
    || ok "nothing_changed_runs_nothing_exit0: does not claim no repo has a test_command"

# uncommitted_and_untracked_count: x has a tracked edit, y an untracked file, z only an
# ignored one.
SG="$TESTDIR/uncommitted_and_untracked_count"
make_repo "$SG" x; make_repo "$SG" y; make_repo "$SG" z
echo edited >> "$SG/workspace/repo/x/README"
echo new > "$SG/workspace/repo/y/new.txt"
echo noise > "$SG/workspace/repo/z/ignored.txt"
write_config "$SG" true "x:" "y:" "z:"
run_scoped "$SG"
expect_eq "uncommitted_and_untracked_count: exits 0" "$RC" 0
expect_eq "uncommitted_and_untracked_count: tracked edit and untracked file count, ignored does not" \
    "$(ran_set "$SG")" "x y"

# unresolvable_base_counts_as_changed: x's remote-tracking refs are gone.
SH="$TESTDIR/unresolvable_base_counts_as_changed"
make_repo "$SH" x; make_repo "$SH" y
fixture_git -C "$SH/workspace/repo/x" symbolic-ref --delete refs/remotes/origin/HEAD
for ref in $(fixture_git -C "$SH/workspace/repo/x" for-each-ref --format='%(refname)' refs/remotes/origin/); do
    fixture_git -C "$SH/workspace/repo/x" update-ref -d "$ref"
done
fixture_git -C "$SH/workspace/repo/x" rev-parse --verify --quiet refs/remotes/origin/main > /dev/null \
    && fail "unresolvable_base_counts_as_changed: fixture removed origin/main" \
    || ok "unresolvable_base_counts_as_changed: fixture removed origin/main"
write_config "$SH" true "x:" "y:"
run_scoped "$SH"
expect_eq "unresolvable_base_counts_as_changed: exits 0" "$RC" 0
expect_eq "unresolvable_base_counts_as_changed: x ran, unchanged y did not" "$(ran_set "$SH")" "x"

# affected_failure_fails_run: x (affected) has a failing suite, y is unaffected.
SM="$TESTDIR/affected_failure_fails_run"
make_repo "$SM" x; make_repo "$SM" y
commit_in "$SM" x
write_config "$SM" true "x:" "y:"
jq '.repos[0].test_command = "exit 1"' "$SM/workspace/config.json" > "$SM/config.tmp" \
    && mv "$SM/config.tmp" "$SM/workspace/config.json"
run_scoped "$SM"
expect_eq "affected_failure_fails_run: exits 1" "$RC" 1
section_of "$SM/workspace/out/test_report.md" x | grep -qF "**Status:** FAILED" \
    && ok "affected_failure_fails_run: x reported FAILED" \
    || fail "affected_failure_fails_run: x reported FAILED"
section_has_line "$SM/workspace/out/test_report.md" y "$SKIP_LINE" \
    && ok "affected_failure_fails_run: unaffected y still SKIPPED" \
    || fail "affected_failure_fails_run: unaffected y still SKIPPED"

# changed_set_computed_before_running: x's suite writes an untracked file into unchanged y.
SI="$TESTDIR/changed_set_computed_before_running"
make_repo "$SI" x; make_repo "$SI" y
commit_in "$SI" x
write_config "$SI" true "x:" "y:"
jq --arg stray "$SI/workspace/repo/y/stray.txt" '.repos[0].test_command += (" && touch " + $stray)' \
    "$SI/workspace/config.json" > "$SI/config.tmp" && mv "$SI/config.tmp" "$SI/workspace/config.json"
run_scoped "$SI"
expect_eq "changed_set_computed_before_running: exits 0" "$RC" 0
[ -f "$SI/workspace/repo/y/stray.txt" ] \
    && ok "changed_set_computed_before_running: x's suite did dirty y" \
    || fail "changed_set_computed_before_running: x's suite did dirty y"
expect_eq "changed_set_computed_before_running: y stays skipped" "$(ran_set "$SI")" "x"

# cyclic_depends_on_terminates: x and y depend on each other; x changed.
SJ="$TESTDIR/cyclic_depends_on_terminates"
make_repo "$SJ" x; make_repo "$SJ" y
commit_in "$SJ" x
write_config "$SJ" true "x:y" "y:x"
run_scoped "$SJ"
expect_eq "cyclic_depends_on_terminates: exits 0 (not killed)" "$RC" 0
expect_eq "cyclic_depends_on_terminates: both ran" "$(ran_set "$SJ")" "x y"

# rerun_is_idempotent: a second run in the same pod classifies every repo the same way.
SK="$TESTDIR/rerun_is_idempotent"
make_repo "$SK" x; make_repo "$SK" y; make_repo "$SK" z
commit_in "$SK" y
write_config "$SK" true "x:y" "y:" "z:"
run_scoped "$SK"
RAN_K1=$(ran_set "$SK")
SCOPE_K1=$(section_of "$SK/workspace/out/test_report.md" "Test scope")
rm -rf "$SK/ran"
run_scoped "$SK"
expect_eq "rerun_is_idempotent: second run exits 0" "$RC" 0
expect_eq "rerun_is_idempotent: first run ran the affected repos" "$RAN_K1" "x y"
expect_eq "rerun_is_idempotent: same repos ran again" "$(ran_set "$SK")" "$RAN_K1"
expect_eq "rerun_is_idempotent: same Test scope section" \
    "$(section_of "$SK/workspace/out/test_report.md" "Test scope")" "$SCOPE_K1"

# report_has_test_scope_section: x depends on y; y changed; z unaffected.
SL="$TESTDIR/report_has_test_scope_section"
make_repo "$SL" x; make_repo "$SL" y; make_repo "$SL" z
commit_in "$SL" y
write_config "$SL" true "x:y" "y:" "z:"
run_scoped "$SL"
REPORTL="$SL/workspace/out/test_report.md"
SCOPE_L=$(section_of "$REPORTL" "Test scope")
expect_eq "report_has_test_scope_section: exits 0" "$RC" 0
expect_eq "report_has_test_scope_section: Test scope is the report's first section" \
    "$(grep -m1 '^## ' "$REPORTL")" "## Test scope"
grep -qxF -- '**Changed:** `y`' <<< "$SCOPE_L" \
    && ok "report_has_test_scope_section: names the changed repo" \
    || fail "report_has_test_scope_section: names the changed repo"
grep -qxF -- '- `x`: affected (depends on y)' <<< "$SCOPE_L" \
    && ok "report_has_test_scope_section: gives the dependent's reason" \
    || fail "report_has_test_scope_section: gives the dependent's reason"
grep -qxF -- '- `y`: affected (changed)' <<< "$SCOPE_L" \
    && ok "report_has_test_scope_section: gives the changed repo's reason" \
    || fail "report_has_test_scope_section: gives the changed repo's reason"
grep -qxF -- '- `z`: unaffected' <<< "$SCOPE_L" \
    && ok "report_has_test_scope_section: lists the unaffected repo" \
    || fail "report_has_test_scope_section: lists the unaffected repo"
section_has_line "$REPORTL" z "$SKIP_LINE" \
    && ok "report_has_test_scope_section: z carries the skip line" \
    || fail "report_has_test_scope_section: z carries the skip line"
SECTION_Z=$(section_of "$REPORTL" z)
grep -qF "PASSED" <<< "$SECTION_Z" \
    && fail "report_has_test_scope_section: skipped z never says PASSED" \
    || ok "report_has_test_scope_section: skipped z never says PASSED"

# unknown_depends_on_warns: x names a repo this run does not have; it is ignored with a warning.
SM="$TESTDIR/unknown_depends_on_warns"
make_repo "$SM" x; make_repo "$SM" y
commit_in "$SM" y
write_config "$SM" true "x:ghost" "y:"
run_scoped "$SM"
expect_eq "unknown_depends_on_warns: exits 0" "$RC" 0
expect_eq "unknown_depends_on_warns: the unknown name pulls nothing in" "$(ran_set "$SM")" "y"
section_has_line "$SM/workspace/out/test_report.md" "Test scope" \
    '- Warning: `x` depends on `ghost`, which is not a repo in this run; ignored.' \
    && ok "unknown_depends_on_warns: Test scope warns about the unknown name" \
    || fail "unknown_depends_on_warns: Test scope warns about the unknown name"

# --- Summary ---
echo ""
echo "Results: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
