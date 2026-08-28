#!/bin/bash
# Unit tests for check-prs (no live container needed).
#
# check-prs (PR completion gate) has real branching that nothing
# else in this repo's test suite exercises directly: test-config-parsing.sh's Test 20
# only greps entrypoint.sh's *call site* of check-prs (structural, not check-prs's own
# logic), and mock-agent.sh's check_prs_gate scenario only exercises the happy path
# (every repo pushed and registered) against a live API server, so it can't be run here
# and wouldn't cover the failure branches below even where it can run.
#
# This file runs the real check-prs script (CONFIG_FILE path swapped to a fixture, the
# same technique test-config-parsing.sh's Test 18 uses for entrypoint.sh) against a real
# local git remote (a bare repo standing in for origin — actual `git ls-remote`, not a
# fake) and a stub `curl` prepended onto PATH (controlled via CURL_STUB_* env vars) that
# stands in for the ChorusKube API server, so every branch below is exercised through
# check-prs's real, unmodified control flow.
set -euo pipefail

PASS=0
FAIL=0
TESTDIR=$(mktemp -d)
trap 'rm -rf "$TESTDIR"' EXIT

ok() { echo "PASS: $1"; PASS=$((PASS+1)); }
fail() { echo "FAIL: $1"; FAIL=$((FAIL+1)); }

CHECK_PRS_SRC="$(dirname "${BASH_SOURCE[0]}")/../check-prs"

# --- Fixture plumbing ---

# A real bare repo standing in for "origin". Returns the bare repo's path.
make_origin() {
    local bare_dir="$1"
    git init -q --bare "$bare_dir"
}

# A real working clone of $1 (the bare "origin"), with one commit on its default
# branch, checked out (not detached) — the shape check-prs's `git -C "$REPO_DIR"
# rev-parse --abbrev-ref HEAD` expects. Returns the clone's path via $2.
make_pushed_or_local_repo() {
    local origin_dir="$1"
    local work_dir="$2"
    git clone -q "$origin_dir" "$work_dir" 2>/dev/null
    (
        cd "$work_dir"
        git config user.email test@test.com
        git config user.name test
        echo hi >f.txt
        git add f.txt
        git commit -q -m init
    )
}

# A clone of $1 whose default branch has one commit pushed to origin, plus a run
# branch that is also pushed. $3 sets the run branch's relationship to the default
# branch: "ahead" (one commit the default branch doesn't have), "parity" (tip
# identical), or "behind" (the default branch advanced after the run branch was cut).
#
# This is the shape the platform actually produces and make_pushed_or_local_repo is
# not: a node works on a run branch, never on the default branch, and the failure
# safety net pushes that run branch even when the working tree is clean — which is
# exactly how a parity or behind branch reaches origin.
make_run_branch_repo() {
    local origin_dir="$1" work_dir="$2" relation="$3"
    git clone -q "$origin_dir" "$work_dir" 2>/dev/null
    (
        cd "$work_dir"
        git config user.email test@test.com
        git config user.name test
        # symbolic-ref, not rev-parse: the clone of an empty bare repo has an
        # unborn HEAD, which rev-parse cannot resolve.
        local default_branch
        default_branch=$(git symbolic-ref --short HEAD)
        echo base >base.txt
        git add base.txt
        git commit -q -m base
        git push -q origin "$default_branch"

        git checkout -q -b run-branch
        case "$relation" in
            ahead)
                echo work >work.txt
                git add work.txt
                git commit -q -m work
                ;;
            parity) ;;
            behind)
                git checkout -q "$default_branch"
                echo more >more.txt
                git add more.txt
                git commit -q -m more
                git push -q origin "$default_branch"
                git checkout -q run-branch
                ;;
        esac
        git push -q origin run-branch
    )
}

# Stub `curl` on PATH standing in for the ChorusKube API server's PR-list endpoint.
# Controlled per-invocation via CURL_STUB_EXIT (nonzero => simulate a transport
# failure, mirroring curl's own exit codes for DNS/connect/timeout failures),
# CURL_STUB_HTTP_CODE (default 200), and CURL_STUB_BODY (default "[]"). Mirrors
# check-prs's own `curl -s -w "\n%{http_code}" ...` output shape: body, newline,
# status code — exactly what `RESPONSE=$(curl ...)` there captures.
FAKE_BIN="$TESTDIR/bin"
mkdir -p "$FAKE_BIN"
cat >"$FAKE_BIN/curl" <<'STUB'
#!/bin/bash
if [ -n "${CURL_STUB_EXIT:-}" ] && [ "${CURL_STUB_EXIT}" != "0" ]; then
  exit "$CURL_STUB_EXIT"
fi
printf '%s\n%s' "${CURL_STUB_BODY:-[]}" "${CURL_STUB_HTTP_CODE:-200}"
STUB
chmod +x "$FAKE_BIN/curl"

# Writes a fixture config.json with the given repos array (already-built JSON) and
# returns a sed-copied check-prs pointed at it (same technique as Test 18's
# ENTRYPOINT_COPY), so every test below exercises check-prs's real, unmodified logic.
make_check_prs_copy() {
    local repos_json="$1"
    local config="$TESTDIR/config_$$_$RANDOM.json"
    local copy="$TESTDIR/check-prs_$$_$RANDOM"
    printf '{"run_id":"r","node_execution_id":"n","prompt":"p","repos":%s}\n' "$repos_json" >"$config"
    sed "s#/workspace/config.json#$config#g" "$CHECK_PRS_SRC" >"$copy"
    chmod +x "$copy"
    echo "$copy"
}

run_check_prs() {
    local copy="$1"
    shift
    set +e
    OUT=$(PATH="$FAKE_BIN:$PATH" API_SERVER_URL="http://api.example" RUN_ID="run-1" \
        NODE_EXECUTION_ID="exec-1" JOB_SECRET="test-secret" "$@" bash "$copy" 2>&1)
    RC=$?
    set -e
}

# --- Test 1: no repos configured — exits 0, nothing to gate on ---
COPY=$(make_check_prs_copy '[]')
run_check_prs "$COPY"
[ "$RC" -eq 0 ] && ok "no repos configured: exits 0" || fail "no repos configured: exits 0 (got $RC: $OUT)"

# --- Test 2: repo not pushed (branch exists locally, not on origin) — exits 0, curl
# never called (no CURL_STUB_* needed; the stub would just return its default 200/[]
# if it were, which would still pass, so this alone doesn't prove curl was skipped —
# Test 2b below proves that directly) ---
ORIGIN2="$TESTDIR/origin2.git"
WORK2="$TESTDIR/work2"
make_origin "$ORIGIN2"
make_pushed_or_local_repo "$ORIGIN2" "$WORK2"
REPOS2=$(printf '[{"id":"repo-1","name":"svc","local_path":"%s"}]' "$WORK2")
COPY=$(make_check_prs_copy "$REPOS2")
run_check_prs "$COPY"
[ "$RC" -eq 0 ] && ok "repo not pushed: exits 0" || fail "repo not pushed: exits 0 (got $RC: $OUT)"

# --- Test 2b: repo not pushed — curl is never invoked at all (not just "invoked but
# ignored") ---
CANARY="$TESTDIR/curl_invoked_canary"
rm -f "$CANARY"
cat >"$FAKE_BIN/curl" <<STUB
#!/bin/bash
touch "$CANARY"
printf '%s\n%s' "\${CURL_STUB_BODY:-[]}" "\${CURL_STUB_HTTP_CODE:-200}"
STUB
chmod +x "$FAKE_BIN/curl"
COPY=$(make_check_prs_copy "$REPOS2")
run_check_prs "$COPY"
[ ! -f "$CANARY" ] && ok "repo not pushed: curl is never invoked" || fail "repo not pushed: curl is never invoked"
# Restore the normal stub for the remaining tests.
cat >"$FAKE_BIN/curl" <<'STUB'
#!/bin/bash
if [ -n "${CURL_STUB_EXIT:-}" ] && [ "${CURL_STUB_EXIT}" != "0" ]; then
  exit "$CURL_STUB_EXIT"
fi
printf '%s\n%s' "${CURL_STUB_BODY:-[]}" "${CURL_STUB_HTTP_CODE:-200}"
STUB
chmod +x "$FAKE_BIN/curl"

# --- Test 3: repo pushed and its PR is registered — exits 0 ---
ORIGIN3="$TESTDIR/origin3.git"
WORK3="$TESTDIR/work3"
make_origin "$ORIGIN3"
make_run_branch_repo "$ORIGIN3" "$WORK3" ahead
REPOS3=$(printf '[{"id":"repo-3","name":"svc3","local_path":"%s"}]' "$WORK3")
COPY=$(make_check_prs_copy "$REPOS3")
CURL_STUB_BODY='[{"gitRepoId":"repo-3","prUrl":"https://github.com/org/svc3/pull/1"}]' \
    run_check_prs "$COPY"
[ "$RC" -eq 0 ] && ok "repo pushed + PR registered: exits 0" || fail "repo pushed + PR registered: exits 0 (got $RC: $OUT)"

# --- Test 4: repo pushed but its PR is NOT registered — exits 1, names the repo ---
ORIGIN4="$TESTDIR/origin4.git"
WORK4="$TESTDIR/work4"
make_origin "$ORIGIN4"
make_run_branch_repo "$ORIGIN4" "$WORK4" ahead
REPOS4=$(printf '[{"id":"repo-4","name":"svc4","local_path":"%s"}]' "$WORK4")
COPY=$(make_check_prs_copy "$REPOS4")
CURL_STUB_BODY='[]' run_check_prs "$COPY"
[ "$RC" -eq 1 ] && ok "repo pushed, no PR registered: exits 1" || fail "repo pushed, no PR registered: exits 1 (got $RC)"
echo "$OUT" | grep -qF "svc4: no PR registered" \
    && ok "repo pushed, no PR registered: names the repo" || fail "repo pushed, no PR registered: names the repo (got: $OUT)"

# --- Test 5: multi-repo — one registered, one missing — only the missing one is
# reported, and the registered one doesn't false-positive ---
ORIGIN5A="$TESTDIR/origin5a.git"; WORK5A="$TESTDIR/work5a"
ORIGIN5B="$TESTDIR/origin5b.git"; WORK5B="$TESTDIR/work5b"
make_origin "$ORIGIN5A"; make_run_branch_repo "$ORIGIN5A" "$WORK5A" ahead
make_origin "$ORIGIN5B"; make_run_branch_repo "$ORIGIN5B" "$WORK5B" ahead
REPOS5=$(printf '[{"id":"repo-5a","name":"svc5a","local_path":"%s"},{"id":"repo-5b","name":"svc5b","local_path":"%s"}]' "$WORK5A" "$WORK5B")
COPY=$(make_check_prs_copy "$REPOS5")
CURL_STUB_BODY='[{"gitRepoId":"repo-5a","prUrl":"https://github.com/org/svc5a/pull/1"}]' run_check_prs "$COPY"
[ "$RC" -eq 1 ] && ok "multi-repo partial registration: exits 1" || fail "multi-repo partial registration: exits 1 (got $RC)"
echo "$OUT" | grep -qF "svc5b: no PR registered" \
    && ok "multi-repo partial registration: names only the missing repo" || fail "multi-repo partial registration: names only the missing repo (got: $OUT)"
echo "$OUT" | grep -qF "svc5a: no PR registered" \
    && fail "multi-repo partial registration: false-positives the registered repo" || ok "multi-repo partial registration: does not false-positive the registered repo"

# --- Test 6: curl transport failure (e.g. DNS/connect/timeout) — exits 1 loudly,
# doesn't die silently under set -euo pipefail (the exact bug
# iteration 1 of this PR's own review fixed) ---
ORIGIN6="$TESTDIR/origin6.git"; WORK6="$TESTDIR/work6"
make_origin "$ORIGIN6"; make_run_branch_repo "$ORIGIN6" "$WORK6" ahead
REPOS6=$(printf '[{"id":"repo-6","name":"svc6","local_path":"%s"}]' "$WORK6")
COPY=$(make_check_prs_copy "$REPOS6")
CURL_STUB_EXIT=7 run_check_prs "$COPY"
[ "$RC" -eq 1 ] && ok "curl transport failure: exits 1 (not a silent set -e death)" || fail "curl transport failure: exits 1 (got $RC)"
echo "$OUT" | grep -qF "could not reach" \
    && ok "curl transport failure: loud diagnostic" || fail "curl transport failure: loud diagnostic (got: $OUT)"

# --- Test 7: API server reachable but returns a non-200 (e.g. 500) — exits 1 loudly ---
ORIGIN7="$TESTDIR/origin7.git"; WORK7="$TESTDIR/work7"
make_origin "$ORIGIN7"; make_run_branch_repo "$ORIGIN7" "$WORK7" ahead
REPOS7=$(printf '[{"id":"repo-7","name":"svc7","local_path":"%s"}]' "$WORK7")
COPY=$(make_check_prs_copy "$REPOS7")
CURL_STUB_HTTP_CODE=500 CURL_STUB_BODY='{"error":"boom"}' run_check_prs "$COPY"
[ "$RC" -eq 1 ] && ok "API HTTP 500: exits 1" || fail "API HTTP 500: exits 1 (got $RC)"
echo "$OUT" | grep -qF "HTTP 500" \
    && ok "API HTTP 500: names the status code" || fail "API HTTP 500: names the status code (got: $OUT)"

# --- Test 8: origin unreachable entirely (git ls-remote fails, not just "ref not
# found") — exits 1 loudly, distinct from the "not pushed" (exit 2) case ---
WORK8="$TESTDIR/work8"
mkdir -p "$WORK8"
(
    cd "$WORK8"
    git init -q
    git config user.email test@test.com
    git config user.name test
    echo hi >f.txt
    git add f.txt
    git commit -q -m init
    git remote add origin "$TESTDIR/does-not-exist.git"
)
REPOS8=$(printf '[{"id":"repo-8","name":"svc8","local_path":"%s"}]' "$WORK8")
COPY=$(make_check_prs_copy "$REPOS8")
run_check_prs "$COPY"
[ "$RC" -eq 1 ] && ok "unreachable origin: exits 1" || fail "unreachable origin: exits 1 (got $RC)"
echo "$OUT" | grep -qF "could not reach origin" \
    && ok "unreachable origin: loud diagnostic, distinct from 'not pushed'" || fail "unreachable origin: loud diagnostic (got: $OUT)"

# --- Test 9: required env vars missing — fails loudly before any git/curl call ---
COPY=$(make_check_prs_copy '[]')
set +e
OUT=$(env -u API_SERVER_URL RUN_ID="r" NODE_EXECUTION_ID="n" JOB_SECRET="s" bash "$COPY" 2>&1)
RC=$?
set -e
[ "$RC" -eq 1 ] && ok "missing API_SERVER_URL: exits 1" || fail "missing API_SERVER_URL: exits 1 (got $RC)"
echo "$OUT" | grep -qF "API_SERVER_URL not set" \
    && ok "missing API_SERVER_URL: clear diagnostic" || fail "missing API_SERVER_URL: clear diagnostic (got: $OUT)"

# --- Test 10: run branch pushed at parity with the default branch — exits 0.
# No pull request can be opened for a branch that adds no commits, so demanding one
# would gate on something impossible. The failure safety net pushes the run branch
# whenever a node fails with a clean tree, which is how this branch exists at all. ---
ORIGIN10="$TESTDIR/origin10.git"; WORK10="$TESTDIR/work10"
make_origin "$ORIGIN10"; make_run_branch_repo "$ORIGIN10" "$WORK10" parity
REPOS10=$(printf '[{"id":"repo-10","name":"svc10","local_path":"%s"}]' "$WORK10")
COPY=$(make_check_prs_copy "$REPOS10")
CURL_STUB_BODY='[]' run_check_prs "$COPY"
[ "$RC" -eq 0 ] && ok "run branch at parity with default: exits 0" || fail "run branch at parity with default: exits 0 (got $RC: $OUT)"
echo "$OUT" | grep -qF "svc10: no PR registered" \
    && fail "run branch at parity with default: must not demand a PR" || ok "run branch at parity with default: does not demand a PR"

# --- Test 11: run branch pushed but behind the default branch — exits 0, same
# reasoning as Test 10. Distinct from parity because the two tips differ, so a
# SHA-equality check would miss this one. ---
ORIGIN11="$TESTDIR/origin11.git"; WORK11="$TESTDIR/work11"
make_origin "$ORIGIN11"; make_run_branch_repo "$ORIGIN11" "$WORK11" behind
REPOS11=$(printf '[{"id":"repo-11","name":"svc11","local_path":"%s"}]' "$WORK11")
COPY=$(make_check_prs_copy "$REPOS11")
CURL_STUB_BODY='[]' run_check_prs "$COPY"
[ "$RC" -eq 0 ] && ok "run branch behind default: exits 0" || fail "run branch behind default: exits 0 (got $RC: $OUT)"
echo "$OUT" | grep -qF "svc11: no PR registered" \
    && fail "run branch behind default: must not demand a PR" || ok "run branch behind default: does not demand a PR"

# --- Test 12: run branch pushed with a commit the default branch lacks — still
# gated. The whole point of the gate; Tests 10 and 11 must not have widened the
# escape hatch far enough to swallow real work. ---
ORIGIN12="$TESTDIR/origin12.git"; WORK12="$TESTDIR/work12"
make_origin "$ORIGIN12"; make_run_branch_repo "$ORIGIN12" "$WORK12" ahead
REPOS12=$(printf '[{"id":"repo-12","name":"svc12","local_path":"%s"}]' "$WORK12")
COPY=$(make_check_prs_copy "$REPOS12")
CURL_STUB_BODY='[]' run_check_prs "$COPY"
[ "$RC" -eq 1 ] && ok "run branch ahead of default, no PR: exits 1" || fail "run branch ahead of default, no PR: exits 1 (got $RC: $OUT)"
echo "$OUT" | grep -qF "svc12: no PR registered" \
    && ok "run branch ahead of default, no PR: names the repo" || fail "run branch ahead of default, no PR: names the repo (got: $OUT)"

# --- Test 13: run branch ahead of the default branch and its PR is registered —
# exits 0, so the ahead path still passes when the contract is met. ---
ORIGIN13="$TESTDIR/origin13.git"; WORK13="$TESTDIR/work13"
make_origin "$ORIGIN13"; make_run_branch_repo "$ORIGIN13" "$WORK13" ahead
REPOS13=$(printf '[{"id":"repo-13","name":"svc13","local_path":"%s"}]' "$WORK13")
COPY=$(make_check_prs_copy "$REPOS13")
CURL_STUB_BODY='[{"gitRepoId":"repo-13","prUrl":"https://example.invalid/pull/1"}]' run_check_prs "$COPY"
[ "$RC" -eq 0 ] && ok "run branch ahead of default + PR registered: exits 0" || fail "run branch ahead of default + PR registered: exits 0 (got $RC: $OUT)"

# --- Summary ---
echo ""
echo "Results: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
