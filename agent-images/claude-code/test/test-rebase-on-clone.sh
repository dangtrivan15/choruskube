#!/bin/bash
# Unit tests for entrypoint.sh's rebase-on-clone functions (no live container needed).
#
# Runs the real functions, extracted from entrypoint.sh, against real git repos (a bare
# repo standing in for origin), the same extract-the-fragment style as
# test-config-parsing.sh's Test 21, so the outcome recorded is the one that ships.
set -euo pipefail

PASS=0
FAIL=0
TESTDIR=$(mktemp -d)
trap 'rm -rf "$TESTDIR"' EXIT

ok() { echo "PASS: $1"; PASS=$((PASS+1)); }
fail() { echo "FAIL: $1"; FAIL=$((FAIL+1)); }

ENTRYPOINT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/entrypoint.sh"

# The entrypoint sets the committer identity globally before cloning (rebase needs one).
# A throwaway HOME gives these repos the same, and keeps the developer's own git config
# (signing, hooks, default branch) out of the result.
export HOME="$TESTDIR/home"
export XDG_CONFIG_HOME="$HOME/.config"
export GIT_CONFIG_NOSYSTEM=1
mkdir -p "$HOME"
git config --global user.email "agent@choruskube.local"
git config --global user.name "ChorusKube Agent"
git config --global commit.gpgsign false
git config --global init.defaultBranch main
git config --global advice.detachedHead false

# record-base is the API boundary; the stub only proves it was called.
FAKE_BIN="$TESTDIR/bin"
mkdir -p "$FAKE_BIN"
cat > "$FAKE_BIN/record-base" <<'STUB'
#!/bin/bash
touch "$CASE_DIR/recorded"
STUB
chmod +x "$FAKE_BIN/record-base"

# --- Fixtures ---

rc_origin() {
  # $1 = repo name, $2 = default branch. Creates $TESTDIR/$1.git (bare, HEAD -> $2) with a
  # seed commit carrying f.txt, plus $TESTDIR/$1-seed, a full clone used to push more commits.
  local name="$1" default="$2"
  local remote="$TESTDIR/$name.git" seed="$TESTDIR/$name-seed"
  git init -q --bare "$remote"
  git -C "$remote" symbolic-ref HEAD "refs/heads/$default"
  git init -q "$seed"
  echo base > "$seed/f.txt"
  git -C "$seed" add -A
  git -C "$seed" commit -q -m seed
  git -C "$seed" branch -M "$default"
  git -C "$seed" remote add origin "$remote"
  git -C "$seed" push -q origin "$default"
}

rc_commit() {
  # $1 = repo name, $2 = branch (created from the current one if new), $3 = file, $4 = content.
  local seed="$TESTDIR/$1-seed"
  git -C "$seed" checkout -q "$2" 2>/dev/null || git -C "$seed" checkout -q -b "$2"
  echo "$4" > "$seed/$3"
  git -C "$seed" add -A
  git -C "$seed" commit -q -m "edit $3 on $2"
  git -C "$seed" push -q origin "$2"
}

rc_clone() {
  # $1 = repo name, $2 = clone path, $3 = run branch ("" for none). Mirrors Step 3's clone.
  git clone -q --depth 1 --no-single-branch "file://$TESTDIR/$1.git" "$2"
  if [ -n "$3" ]; then
    git -C "$2" checkout -q "$3" 2>/dev/null || git -C "$2" checkout -q -b "$3"
  fi
  return 0
}

rc_run() {
  # $1 = case dir; stdin = commands to run after the extracted functions are defined.
  # Leaves combined output in RC_OUT and the exit status in RC_RC.
  local case_dir="$1" harness="$1/harness.sh"
  mkdir -p "$case_dir"
  {
    echo 'set -euo pipefail'
    printf 'WORKSPACE_IN=%q\n' "$case_dir/in"
    printf 'REPO_BASE_DIR=%q\n' "$case_dir/bases"
    printf 'export CASE_DIR=%q\n' "$case_dir"
    printf 'PATH=%q:"$PATH"\n' "$FAKE_BIN"
    awk '/^# --- Rebase-on-clone:/{f=1} /^# --- Step 3:/{f=0} f' "$ENTRYPOINT"
    cat
  } > "$harness"
  set +e
  RC_OUT=$(bash "$harness" 2>&1)
  RC_RC=$?
  set -e
}

base_field() {
  # $1 = case dir, $2 = repo name, $3 = jq filter over that repo's recorded base
  jq -r "$3" "$1/bases/$2.json" 2>/dev/null || echo "<no record>"
}

# --- Test 1: no run branch (spec node) — the clone's HEAD is the base ---
rc_origin nobranch main
C1="$TESTDIR/case1"
rc_clone nobranch "$C1/work" ""
rc_run "$C1" <<EOF
rebase_onto_default nobranch "$C1/work" "" id-1
finish_repo_bases
EOF
[ "$RC_RC" -eq 0 ] && ok "no branch: exits 0" || fail "no branch: exits 0 ($RC_OUT)"
[ "$(base_field "$C1" nobranch .outcome)" = "clean" ] \
  && ok "no branch: outcome clean" || fail "no branch: outcome clean ($(base_field "$C1" nobranch .))"
[ "$(base_field "$C1" nobranch .baseSha)" = "$(git -C "$TESTDIR/nobranch.git" rev-parse main)" ] \
  && ok "no branch: baseSha is origin's main" || fail "no branch: baseSha is origin's main"
[ "$(base_field "$C1" nobranch .gitRepoId)" = "id-1" ] \
  && ok "no branch: record carries the git repo id" || fail "no branch: record carries the git repo id"
[ ! -e "$C1/in/rebase_status.md" ] \
  && ok "no branch: no rebase status for the agent" || fail "no branch: no rebase status for the agent"

# --- Test 2: clean rebase onto a default branch that moved after the branch was cut ---
rc_origin clean main
rc_commit clean run b.txt branch-work
git -C "$TESTDIR/clean-seed" checkout -q main
rc_commit clean main m.txt main-moved
C2="$TESTDIR/case2"
rc_clone clean "$C2/work" run
rc_run "$C2" <<EOF
rebase_onto_default clean "$C2/work" run id-2
finish_repo_bases
EOF
MAIN_TIP=$(git -C "$TESTDIR/clean.git" rev-parse main)
[ "$RC_RC" -eq 0 ] && ok "clean rebase: exits 0" || fail "clean rebase: exits 0 ($RC_OUT)"
[ "$(base_field "$C2" clean .outcome)" = "clean" ] \
  && ok "clean rebase: outcome clean" || fail "clean rebase: outcome clean ($(base_field "$C2" clean .))"
[ "$(base_field "$C2" clean .baseSha)" = "$MAIN_TIP" ] \
  && ok "clean rebase: baseSha is the new main tip" || fail "clean rebase: baseSha is the new main tip"
[ "$(git -C "$C2/work" rev-parse HEAD^)" = "$MAIN_TIP" ] \
  && ok "clean rebase: the run branch now sits on the new main tip" \
  || fail "clean rebase: the run branch now sits on the new main tip"
[ "$(git -C "$C2/work" rev-parse --abbrev-ref HEAD)" = "run" ] \
  && ok "clean rebase: still on the run branch" || fail "clean rebase: still on the run branch"
[ "$(base_field "$C2" clean '.conflictedFiles | length')" = "0" ] \
  && ok "clean rebase: no conflicted files" || fail "clean rebase: no conflicted files"
[ ! -e "$C2/in/rebase_status.md" ] \
  && ok "clean rebase: no rebase status for the agent" || fail "clean rebase: no rebase status for the agent"
[ -e "$C2/recorded" ] && ok "clean rebase: bases recorded" || fail "clean rebase: bases recorded"

# --- Test 3: run branch not on origin yet (the first push) ---
# Fetching the default branch must not depend on the run branch existing remotely.
rc_origin newbranch main
C3="$TESTDIR/case3"
rc_clone newbranch "$C3/work" run
rc_run "$C3" <<EOF
rebase_onto_default newbranch "$C3/work" run id-3
EOF
[ "$RC_RC" -eq 0 ] && ok "new branch: exits 0" || fail "new branch: exits 0 ($RC_OUT)"
[ "$(base_field "$C3" newbranch .outcome)" = "clean" ] \
  && ok "new branch not on origin: outcome clean" \
  || fail "new branch not on origin: outcome clean ($(base_field "$C3" newbranch .))"
[ "$(base_field "$C3" newbranch .baseSha)" = "$(git -C "$TESTDIR/newbranch.git" rev-parse main)" ] \
  && ok "new branch not on origin: baseSha is origin's main" \
  || fail "new branch not on origin: baseSha is origin's main"
[ ! -e "$C3/bases/newbranch.status.md" ] \
  && ok "new branch not on origin: no status fragment" || fail "new branch not on origin: no status fragment"

# --- Test 4: conflict — recorded, aborted, and surfaced to the agent ---
rc_origin conflict main
rc_commit conflict run f.txt from-branch
git -C "$TESTDIR/conflict-seed" checkout -q main
rc_commit conflict main f.txt from-main
C4="$TESTDIR/case4"
rc_clone conflict "$C4/work" run
RUN_TIP_BEFORE=$(git -C "$C4/work" rev-parse HEAD)
rc_run "$C4" <<EOF
rebase_onto_default conflict "$C4/work" run id-4
finish_repo_bases
EOF
[ "$RC_RC" -eq 0 ] && ok "conflict: exits 0" || fail "conflict: exits 0 ($RC_OUT)"
[ "$(base_field "$C4" conflict .outcome)" = "conflict" ] \
  && ok "conflict: outcome conflict" || fail "conflict: outcome conflict ($(base_field "$C4" conflict .))"
[ "$(base_field "$C4" conflict .baseSha)" = "null" ] \
  && ok "conflict: baseSha null" || fail "conflict: baseSha null"
[ "$(base_field "$C4" conflict '.conflictedFiles | tojson')" = '["f.txt"]' ] \
  && ok "conflict: conflictedFiles names f.txt" \
  || fail "conflict: conflictedFiles names f.txt ($(base_field "$C4" conflict '.conflictedFiles | tojson'))"
GIT_DIR_4=$(git -C "$C4/work" rev-parse --absolute-git-dir)
[ ! -d "$GIT_DIR_4/rebase-merge" ] && [ ! -d "$GIT_DIR_4/rebase-apply" ] \
  && ok "conflict: no rebase left in progress" || fail "conflict: no rebase left in progress"
[ "$(git -C "$C4/work" rev-parse HEAD)" = "$RUN_TIP_BEFORE" ] && [ -z "$(git -C "$C4/work" status --porcelain)" ] \
  && ok "conflict: the branch is back where it was, with no conflict markers" \
  || fail "conflict: the branch is back where it was, with no conflict markers"
grep -q "f.txt" "$C4/in/rebase_status.md" 2>/dev/null \
  && ok "conflict: rebase status names the conflicted file" \
  || fail "conflict: rebase status names the conflicted file"
grep -q "force-with-lease" "$C4/in/rebase_status.md" 2>/dev/null \
  && ok "conflict: rebase status tells the agent how to push" \
  || fail "conflict: rebase status tells the agent how to push"
[ -e "$C4/recorded" ] && ok "conflict: bases still recorded" || fail "conflict: bases still recorded"

# --- Test 5: a default branch that is not main ---
rc_origin trunkrepo trunk
rc_commit trunkrepo run b.txt branch-work
git -C "$TESTDIR/trunkrepo-seed" checkout -q trunk
rc_commit trunkrepo trunk m.txt trunk-moved
C5="$TESTDIR/case5"
rc_clone trunkrepo "$C5/work" run
rc_run "$C5" <<EOF
rebase_onto_default trunkrepo "$C5/work" run id-5
EOF
TRUNK_TIP=$(git -C "$TESTDIR/trunkrepo.git" rev-parse trunk)
[ "$RC_RC" -eq 0 ] && ok "default branch trunk: exits 0" || fail "default branch trunk: exits 0 ($RC_OUT)"
[ "$(base_field "$C5" trunkrepo .outcome)" = "clean" ] \
  && ok "default branch trunk: outcome clean" \
  || fail "default branch trunk: outcome clean ($(base_field "$C5" trunkrepo .))"
[ "$(base_field "$C5" trunkrepo .baseSha)" = "$TRUNK_TIP" ] \
  && ok "default branch trunk: baseSha is the trunk tip" || fail "default branch trunk: baseSha is the trunk tip"
[ "$(git -C "$C5/work" rev-parse HEAD^)" = "$TRUNK_TIP" ] \
  && ok "default branch trunk: rebased onto trunk" || fail "default branch trunk: rebased onto trunk"

# --- Test 6: the default branch cannot be fetched ---
rc_origin unreachable main
rc_commit unreachable run b.txt branch-work
C6="$TESTDIR/case6"
rc_clone unreachable "$C6/work" run
git -C "$C6/work" remote set-url origin "$TESTDIR/missing.git"
rc_run "$C6" <<EOF
rebase_onto_default unreachable "$C6/work" run id-6
finish_repo_bases
EOF
[ "$RC_RC" -eq 0 ] && ok "fetch fails: exits 0" || fail "fetch fails: exits 0 ($RC_OUT)"
[ "$(base_field "$C6" unreachable .outcome)" = "unavailable" ] \
  && ok "fetch fails: outcome unavailable" || fail "fetch fails: outcome unavailable ($(base_field "$C6" unreachable .))"
[ "$(base_field "$C6" unreachable .baseSha)" = "null" ] \
  && ok "fetch fails: baseSha null" || fail "fetch fails: baseSha null"
grep -q "could not fetch" "$C6/bases/unreachable.status.md" 2>/dev/null \
  && ok "fetch fails: status fragment says could not fetch" \
  || fail "fetch fails: status fragment says could not fetch"
grep -q "could not fetch" "$C6/in/rebase_status.md" 2>/dev/null \
  && ok "fetch fails: surfaced to the agent" || fail "fetch fails: surfaced to the agent"

# --- Test 7: the rebased branch reaches origin only with a lease ---
# Continues Test 2: origin's run branch still holds the pre-rebase commit.
set +e
git -C "$C2/work" push -q origin HEAD:run >/dev/null 2>&1
PLAIN_RC=$?
set -e
[ "$PLAIN_RC" -ne 0 ] && ok "diverged push: a plain push is rejected" \
  || fail "diverged push: a plain push is rejected"
set +e
git -C "$C2/work" push -q --force-with-lease origin HEAD:run >/dev/null 2>&1
LEASE_RC=$?
set -e
[ "$LEASE_RC" -eq 0 ] && ok "diverged push: --force-with-lease succeeds" \
  || fail "diverged push: --force-with-lease succeeds"
[ "$(git -C "$TESTDIR/clean.git" rev-parse run)" = "$(git -C "$C2/work" rev-parse HEAD)" ] \
  && ok "diverged push: origin's run branch is the rebased HEAD" \
  || fail "diverged push: origin's run branch is the rebased HEAD"

# --- Test 8: no git repo id (single-repo mode) — nothing recorded, agent still told ---
rc_origin noid main
rc_commit noid run b.txt branch-work
C8="$TESTDIR/case8"
rc_clone noid "$C8/work" run
git -C "$C8/work" remote set-url origin "$TESTDIR/missing.git"
rc_run "$C8" <<EOF
rebase_onto_default noid "$C8/work" run ""
finish_repo_bases
EOF
[ "$RC_RC" -eq 0 ] && ok "no repo id: exits 0" || fail "no repo id: exits 0 ($RC_OUT)"
[ ! -e "$C8/bases/noid.json" ] && ok "no repo id: no base record" || fail "no repo id: no base record"
grep -q "could not fetch" "$C8/in/rebase_status.md" 2>/dev/null \
  && ok "no repo id: rebase status still written" || fail "no repo id: rebase status still written"

# --- Test 9: Step 3 wiring, multi-repo — every clone recorded under its own repo id ---
# Runs Step 3 itself, so the functions are proven to be called, not just correct.
rc_origin wired-a main
rc_commit wired-a run b.txt branch-work
git -C "$TESTDIR/wired-a-seed" checkout -q main
rc_commit wired-a main m.txt main-moved
rc_origin wired-b main
C9="$TESTDIR/case9"
mkdir -p "$C9"
jq -n --arg ua "file://$TESTDIR/wired-a.git" --arg pa "$C9/repo/wired-a" \
  --arg ub "file://$TESTDIR/wired-b.git" --arg pb "$C9/repo/wired-b" \
  '{repos: [{id: "id-a", name: "wired-a", url: $ua, local_path: $pa, working_branch: "run"},
            {id: "id-b", name: "wired-b", url: $ub, local_path: $pb}]}' > "$C9/config.json"
{
  echo 'SYSTEM_PROMPT=""'
  echo 'REPO_URL=""'
  echo 'WORKING_BRANCH=""'
  awk '/^# --- Step 3:/{f=1} f{print} f && /^fi$/{exit}' "$ENTRYPOINT" | sed "s#/workspace/config.json#$C9/config.json#g"
} > "$C9/step3.sh"
rc_run "$C9" < "$C9/step3.sh"
[ "$RC_RC" -eq 0 ] && ok "multi-repo Step 3: exits 0" || fail "multi-repo Step 3: exits 0 ($RC_OUT)"
[ "$(base_field "$C9" wired-a .gitRepoId):$(base_field "$C9" wired-a .outcome)" = "id-a:clean" ] \
  && ok "multi-repo Step 3: branch repo recorded clean under its id" \
  || fail "multi-repo Step 3: branch repo recorded clean under its id ($(base_field "$C9" wired-a .))"
[ "$(base_field "$C9" wired-a .baseSha)" = "$(git -C "$TESTDIR/wired-a.git" rev-parse main)" ] \
  && ok "multi-repo Step 3: branch repo's base is the new main tip" \
  || fail "multi-repo Step 3: branch repo's base is the new main tip"
[ "$(git -C "$C9/repo/wired-a" rev-parse HEAD^)" = "$(git -C "$TESTDIR/wired-a.git" rev-parse main)" ] \
  && ok "multi-repo Step 3: branch repo rebased" || fail "multi-repo Step 3: branch repo rebased"
[ "$(base_field "$C9" wired-b .gitRepoId):$(base_field "$C9" wired-b .outcome)" = "id-b:clean" ] \
  && ok "multi-repo Step 3: branchless repo recorded clean under its id" \
  || fail "multi-repo Step 3: branchless repo recorded clean under its id ($(base_field "$C9" wired-b .))"
[ -e "$C9/recorded" ] && ok "multi-repo Step 3: bases recorded after the clones" \
  || fail "multi-repo Step 3: bases recorded after the clones"

# --- Test 10: Step 3 wiring, single repo — a conflict still reaches the agent ---
rc_origin wired-single main
rc_commit wired-single run f.txt from-branch
git -C "$TESTDIR/wired-single-seed" checkout -q main
rc_commit wired-single main f.txt from-main
C10="$TESTDIR/case10"
mkdir -p "$C10"
echo '{}' > "$C10/config.json"
{
  echo 'SYSTEM_PROMPT=""'
  printf 'REPO_URL=%q\n' "file://$TESTDIR/wired-single.git"
  echo 'WORKING_BRANCH=run'
  awk '/^# --- Step 3:/{f=1} f{print} f && /^fi$/{exit}' "$ENTRYPOINT" \
    | sed -e "s#/workspace/config.json#$C10/config.json#g" -e "s#/workspace/repo#$C10/repo#g"
} > "$C10/step3.sh"
rc_run "$C10" < "$C10/step3.sh"
[ "$RC_RC" -eq 0 ] && ok "single-repo Step 3: exits 0" || fail "single-repo Step 3: exits 0 ($RC_OUT)"
grep -q "f.txt" "$C10/in/rebase_status.md" 2>/dev/null \
  && ok "single-repo Step 3: conflict surfaced to the agent" \
  || fail "single-repo Step 3: conflict surfaced to the agent ($RC_OUT)"
[ -e "$C10/recorded" ] && ok "single-repo Step 3: record-base still called" \
  || fail "single-repo Step 3: record-base still called"

# --- Summary ---
echo ""
echo "Results: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
