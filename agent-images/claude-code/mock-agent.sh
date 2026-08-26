#!/bin/bash
# scripts/mock-agent.sh — Mock agent script for E2E testing
#
# Simulates various agent behaviors deterministically. Designed to be used
# as the COMMAND for script-executor nodes in test graph templates.
#
# Usage:
#   mock-agent.sh <scenario> [options]
#
# Scenarios:
#   success          Exit 0 with a success message and optional artifact
#   failure          Exit 1 with an error message
#   timeout          Sleep forever (tests timeout enforcement)
#   slow             Sleep for --delay seconds, then succeed
#   flaky            Fail on iterations < --succeed-after, then succeed
#   gate_approve     Submit "approved" decision via API (for human-type nodes)
#   gate_reject      Submit "rejected" decision via API (for human-type nodes)
#   live_chat        Simulate a live chat session: submit transcript + decision
#   multi_repo_pr    Register PRs for all repos in a multi-repo run
#   check_prs_gate   Drives the real check-prs/register-pr CLI contract end to end
#                    (Decision 3/§3.3 PR completion gate): the FIRST repo in this
#                    run's config.json repos[] is left unchanged but pushed at parity
#                    (`git push origin HEAD` with no commits added — entrypoint.sh's
#                    Step 3 already checked the run branch out at the default
#                    branch's tip, so this publishes it to origin with nothing new),
#                    exercising check-prs's branch_adds_commits `ahead == 0` exemption
#                    path rather than the branch-absent-on-origin skip path (both exit
#                    0, but for different reasons — see Part 2's E2E coverage note).
#                    Every OTHER repo pushes a marker commit to origin and registers a
#                    PR for it via register-pr, same as before. Finally runs check-prs
#                    for real and asserts it reports nothing missing — same contract
#                    entrypoint.sh's PR-verification block exercises against a real
#                    agent when a node has needs_pr: true. No-op (exit 0) if no
#                    repos are configured for this node.
#   roadmap_status_update  Fetch an Epic's Roadmap Graph View, then report a Task's outcome
#                          via update-task-status (Decision 1/3/4) — same contract a real
#                          agent uses; requires --epic-id and --task-id
#   roadmap_status_update_env_default  Same contract as roadmap_status_update, but calls
#                          get-roadmap-graph/update-task-status with NO --epic-id/--task-id
#                          flags at all, proving the $EPIC_ID/$TASK_ID environment-default
#                          path a task-triggered run's entrypoint.sh exports actually works
#                          end to end; requires this run to have been started from a Task
#   roadmap_status_update_missing_task_id  Negative-path counterpart: simulates a
#                          manually-started run (unsets $TASK_ID) and asserts a bare
#                          update-task-status call exits 1 with a clear message instead of
#                          sending a malformed/empty id
#   roadmap_candidates     Analyzer stand-in for the Roadmap Provisioner's structured
#                          candidate-breakdown gate (Decision 1): writes both
#                          roadmap_analysis.md and roadmap_candidates.json, matching the
#                          two-artifact contract BaseRoadmapProvisionerSeeder's real
#                          "Roadmap Analyzer" node declares. roadmap_candidates.json is
#                          the document shape { milestones[], epics[], dependencies[] }
#                          (Decision 5) — one Milestone, one keyed Epic/Story/Task each
#                          carrying a priority, and one dependency edge — so this
#                          exercises the new fields directly rather than the resolver's
#                          legacy-bare-array back-compat path.
#   roadmap_imperative_links  Imperative counterpart to roadmap_candidates (Decision 6):
#                          drives create-proposal/create-story/create-task (the latter
#                          with --priority) to build an Epic/Story/two Tasks, then
#                          create-dependency to link the two Tasks, then
#                          create-milestone plus update-proposal --milestone-id to
#                          assign the Epic to it — the same write surface the
#                          declarative roadmap_candidates.json artifact expresses, but
#                          called live against the real API server, no JSON artifact
#                          or human gate involved.
#   single_repo_claude_md  Verify SYSTEM_PROMPT is exported (tests the export fix)
#   dind_isolation   Verify DinD isolation: DOCKER_HOST set, no ChorusKube services visible
#   dind_network_connectivity  Verify API server is reachable from DinD agent
#   many_artifacts   Write --count small output files (default: 40), for E2E fixtures
#                    exercising the artifact viewer's many-files layout (see
#                    ArtifactViewerDialog.tsx)
#   rate_limited     Mock parity for the Claude quota park-and-resume contract
#                    (entrypoint.sh's Step 6): reports status "rate_limited" with a
#                    resume_at MOCK_RESUME_SECONDS out (env var, default 5 — no
#                    --flag, since this is the only scenario that parks) and a
#                    synthetic session_id, instead of waiting on a real quota
#                    event, which cannot be summoned on demand. Reads
#                    /workspace/config.json's session_id (the same key the real
#                    entrypoint reads as RESUME_SESSION_ID) on each invocation: if
#                    present, this is a resumed iteration and completes instead of
#                    parking again.
#
# Options:
#   --delay <seconds>         Sleep duration for 'slow' scenario (default: 30)
#   --succeed-after <n>       Iteration on which 'flaky' succeeds (default: 3)
#   --artifact <name>         Write the success/slow artifact to /workspace/out/<name>
#                             (default: result.txt). The uploaded artifact's name IS
#                             this filename, so gate templates that declare
#                             requiredInputArtifacts by name must match it.
#   --decision <value>        Custom decision value for gate scenarios
#   --escalate-after <n>      'gate_approve' only: once the current iteration (read from
#                             /workspace/config.json, same key the real agent reads) is >=
#                             <n>, submit --escalate-decision's value instead of the normal
#                             decision. Lets the mock deterministically reach a human
#                             escalation without relying on any server-side cap override.
#                             Requires --escalate-decision. Default: unset (disabled) —
#                             gate_approve's existing behavior is unchanged when omitted.
#   --escalate-decision <value>  Decision value to submit for 'gate_approve' once
#                             --escalate-after's threshold is reached. Required if
#                             --escalate-after is passed.
#   --epic-id <uuid>          Epic UUID for 'roadmap_status_update'
#   --task-id <uuid>          Task UUID for 'roadmap_status_update' (must already be
#                             in_progress — e.g. this run was started via Task-start)
#   --count <n>               Number of files to write for 'many_artifacts' (default: 40)
#   --expect-input <relpath>  Assert $WORKSPACE_IN/<relpath> (default base /workspace/in)
#                             exists and is non-empty before the scenario runs; exit 1 with
#                             a listing of what IS present otherwise. Repeatable. <relpath>
#                             is the manifest key the api-server resolves and the entrypoint
#                             downloads — "<source_node_label>/<filename>". Applies to every
#                             scenario, so any template declaring requiredInputArtifacts can
#                             prove the declaration actually reached disk.
set -euo pipefail

# --- Defaults ---
SCENARIO="${1:-}"
DELAY=30
SUCCEED_AFTER=3
ARTIFACT_TEXT=""
CUSTOM_DECISION=""
ESCALATE_AFTER=""
ESCALATE_DECISION=""
EPIC_ID_ARG=""
TASK_ID_ARG=""
ARTIFACT_COUNT=40
EXPECT_INPUTS=()

# --- Parse arguments ---
shift || true
while [[ $# -gt 0 ]]; do
  case "$1" in
    --delay)
      DELAY="$2"
      shift 2
      ;;
    --succeed-after)
      SUCCEED_AFTER="$2"
      shift 2
      ;;
    --artifact)
      ARTIFACT_TEXT="$2"
      shift 2
      ;;
    --decision)
      CUSTOM_DECISION="$2"
      shift 2
      ;;
    --escalate-after)
      ESCALATE_AFTER="$2"
      shift 2
      ;;
    --escalate-decision)
      ESCALATE_DECISION="$2"
      shift 2
      ;;
    --epic-id)
      EPIC_ID_ARG="$2"
      shift 2
      ;;
    --task-id)
      TASK_ID_ARG="$2"
      shift 2
      ;;
    --count)
      ARTIFACT_COUNT="$2"
      shift 2
      ;;
    --expect-input)
      EXPECT_INPUTS+=("$2")
      shift 2
      ;;
    *)
      echo "Unknown option: $1" >&2
      exit 1
      ;;
  esac
done

# --- Input artifact assertions (--expect-input) ---
# Sits between argument parsing and scenario dispatch so every scenario gets the check,
# not just 'success'. The api-server resolves a node's requiredInputArtifacts into a
# manifest and entrypoint.sh downloads each key to $WORKSPACE_IN/<key> before this script
# runs, so an absent or zero-byte file here means one of manifest resolution, config.json
# plumbing, or the entrypoint download loop regressed — a listing of what did arrive is
# what tells the three apart.
#
# The base directory is a variable purely so this block is runnable against a fake root in
# test/test-mock-agent.sh; in a pod nothing sets it and it stays /workspace/in.
WORKSPACE_IN="${WORKSPACE_IN:-/workspace/in}"
for expected in ${EXPECT_INPUTS[@]+"${EXPECT_INPUTS[@]}"}; do
  expected_path="${WORKSPACE_IN}/${expected}"
  if [ ! -s "$expected_path" ]; then
    if [ -e "$expected_path" ]; then
      echo "ERROR: expected input artifact is empty: $expected_path" >&2
    else
      echo "ERROR: expected input artifact not found: $expected_path" >&2
    fi
    if [ -d "$WORKSPACE_IN" ]; then
      echo "Contents of ${WORKSPACE_IN}:" >&2
      ls -laR "$WORKSPACE_IN" >&2
    else
      echo "No such directory: ${WORKSPACE_IN}" >&2
    fi
    exit 1
  fi
  echo "Input artifact present: $expected_path ($(wc -c < "$expected_path" | tr -d ' ') bytes)"
done

# --- Helpers ---
# write_artifact <filename> [content]
#   Writes <content> (default: a generic stamp) to /workspace/out/<filename>.
#   The uploaded artifact's name is the filename, so gate templates that declare
#   requiredInputArtifacts must reference this exact name (see E2eTestDataSeeder).
write_artifact() {
  local fname="$1"
  local text="${2:-Mock artifact ${fname} written at $(date -u +%Y-%m-%dT%H:%M:%SZ)}"
  mkdir -p /workspace/out
  echo "$text" > "/workspace/out/${fname}"
  echo "Artifact written to /workspace/out/${fname}"
}

# Read iteration from config.json if available
get_iteration() {
  if [ -f /workspace/config.json ]; then
    jq -r '.iteration // 1' /workspace/config.json
  else
    echo "1"
  fi
}

submit_decision() {
  local decision="$1"

  # Mock parity: the real agent queries list-decisions before submitting so it
  # knows which decisions are routable. Exercising it here keeps the contract
  # warm in E2E even when the mock's chosen decision is hardcoded.
  if command -v list-decisions &>/dev/null; then
    echo "[mock-agent] Fetching valid decisions..."
    list-decisions || echo "[mock-agent] list-decisions failed (continuing for mock test)"
  fi

  # Primary path: use the report-result CLI tool (always available inside agent pods).
  # The entrypoint.sh sets REPORT_RESULT_URL and puts report-result on PATH.
  if command -v report-result &>/dev/null; then
    report-result "$decision"
    echo "Decision '$decision' submitted via report-result"
    return
  fi

  # Fallback: direct API call for running outside an agent pod (e.g. local dev/testing).
  # This constructs the same URL that report-result would use, but from individual env vars
  # since REPORT_RESULT_URL is only set by the agent pod entrypoint.
  if [ -n "${API_SERVER_URL:-}" ] && [ -n "${RUN_ID:-}" ] && [ -n "${NODE_EXECUTION_ID:-}" ]; then
    local url="${API_SERVER_URL}/internal/runs/${RUN_ID}/node-executions/${NODE_EXECUTION_ID}/decision"
    curl -sf -X PUT "$url" \
      -H "Content-Type: application/json" \
      -H "Authorization: Bearer ${JOB_SECRET:-}" \
      -d "{\"decision\": \"$decision\"}"
    echo "Decision '$decision' submitted via API"
  else
    echo "ERROR: Cannot submit decision — report-result not on PATH and API_SERVER_URL/RUN_ID/NODE_EXECUTION_ID not set" >&2
    exit 1
  fi
}

# --- Scenario dispatch ---
case "$SCENARIO" in
  success)
    echo "Mock agent: success scenario"
    # --artifact names the output file (default result.txt); the content is a stamp.
    write_artifact "${ARTIFACT_TEXT:-result.txt}" \
      "Mock agent completed successfully at $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "Mock agent completed successfully"
    exit 0
    ;;

  failure)
    echo "Mock agent: failure scenario"
    echo "Simulated error: task failed as expected" >&2
    exit 1
    ;;

  timeout)
    echo "Mock agent: timeout scenario — sleeping indefinitely"
    # Sleep in a loop to avoid shell sleep limits
    while true; do
      sleep 3600
    done
    ;;

  slow)
    echo "Mock agent: slow scenario — sleeping ${DELAY}s"
    sleep "$DELAY"
    write_artifact "${ARTIFACT_TEXT:-result.txt}" "Slow agent completed after ${DELAY}s delay"
    echo "Mock agent completed after ${DELAY}s delay"
    exit 0
    ;;

  flaky)
    ITERATION=$(get_iteration)
    echo "Mock agent: flaky scenario — iteration=$ITERATION, succeed_after=$SUCCEED_AFTER"
    if [ "$ITERATION" -ge "$SUCCEED_AFTER" ]; then
      write_artifact "result.txt" "Flaky agent succeeded on iteration $ITERATION"
      echo "Mock agent succeeded (iteration $ITERATION >= $SUCCEED_AFTER)"
      exit 0
    else
      echo "Mock agent failed (iteration $ITERATION < $SUCCEED_AFTER)" >&2
      exit 1
    fi
    ;;

  gate_approve)
    # --escalate-after/--escalate-decision let the mock deterministically reach a human
    # escalation on its own, now that the server always stores the reviewer's decision
    # verbatim (no more server-side iteration-cap override). When --escalate-after is
    # unset this scenario behaves exactly as before.
    if [ -n "$ESCALATE_AFTER" ] && [ -z "$ESCALATE_DECISION" ]; then
      echo "ERROR: gate_approve --escalate-after requires --escalate-decision" >&2
      exit 1
    fi

    if [ -n "$ESCALATE_AFTER" ]; then
      ITERATION=$(get_iteration)
      if [ "$ITERATION" -ge "$ESCALATE_AFTER" ]; then
        DECISION="$ESCALATE_DECISION"
        echo "Mock agent: gate_approve scenario — iteration=$ITERATION >= escalate-after=$ESCALATE_AFTER, escalating with decision '$DECISION'"
      else
        DECISION="${CUSTOM_DECISION:-approved}"
        echo "Mock agent: gate_approve scenario — iteration=$ITERATION < escalate-after=$ESCALATE_AFTER, submitting decision '$DECISION'"
      fi
    else
      DECISION="${CUSTOM_DECISION:-approved}"
      echo "Mock agent: gate_approve scenario — submitting decision '$DECISION'"
    fi
    submit_decision "$DECISION"
    write_artifact "result.txt" "Gate approved with decision: $DECISION"
    exit 0
    ;;

  gate_reject)
    DECISION="${CUSTOM_DECISION:-rejected}"
    echo "Mock agent: gate_reject scenario — submitting decision '$DECISION'"
    submit_decision "$DECISION"
    write_artifact "result.txt" "Gate rejected with decision: $DECISION"
    exit 0
    ;;

  live_chat)
    echo "Mock agent: live_chat scenario — simulating chat session"

    # Generate a pre-scripted transcript
    TRANSCRIPT="**Human:** Can you explain the changes you made?\n\n**AI:** I modified the authentication module to use JWT tokens instead of session cookies. The key changes are:\n1. Added jwt-decode dependency\n2. Updated AuthService to issue and validate tokens\n3. Added token refresh middleware\n\n**Human:** Looks good, I approve."

    # Write transcript as artifact
    mkdir -p /workspace/out
    echo -e "$TRANSCRIPT" > /workspace/out/chat_transcript.md

    # If we have API access, update the session using the node-execution-scoped endpoint
    if [ -n "${API_SERVER_URL:-}" ] && [ -n "${RUN_ID:-}" ] && [ -n "${NODE_EXECUTION_ID:-}" ]; then
      # Use node-execution-scoped endpoint (matches InternalAuthFilter pattern)
      curl -sf -X PUT "${API_SERVER_URL}/internal/runs/${RUN_ID}/node-executions/${NODE_EXECUTION_ID}/live-chat/session" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer ${JOB_SECRET}" \
        -d "{\"status\": \"completed\", \"transcript\": \"$(echo -e "$TRANSCRIPT" | sed 's/"/\\"/g')\"}" || true
    fi

    # Submit approved decision
    DECISION="${CUSTOM_DECISION:-approved}"
    submit_decision "$DECISION"
    write_artifact "result.txt" "Live chat completed with decision: $DECISION"
    echo "Mock agent: live_chat completed"
    exit 0
    ;;

  multi_repo_pr)
    echo "Mock agent: multi_repo_pr scenario — registering PRs for all repos"
    REPOS_JSON=$(jq -r '.repos // empty' /workspace/config.json 2>/dev/null || true)

    if [ -n "$REPOS_JSON" ] && [ "$REPOS_JSON" != "null" ]; then
      # Use process substitution for set -e compatibility
      while IFS= read -r repo; do
        repo_id=$(echo "$repo" | jq -r '.id')
        repo_name=$(echo "$repo" | jq -r '.name')
        repo_url=$(echo "$repo" | jq -r '.url')

        pr_number=$((RANDOM % 1000 + 1))
        pr_url="${repo_url}/pull/${pr_number}"

        register-pr \
          --repo-id "$repo_id" \
          --pr-url "$pr_url" \
          --pr-number "$pr_number" \
          --title "feat: multi-repo changes (${repo_name})" \
          --repo-name "$repo_name"
      done < <(echo "$REPOS_JSON" | jq -c '.[]')
    fi

    write_artifact "result.txt" "PRs created for all repositories"
    echo "Mock agent: multi_repo_pr completed"
    exit 0
    ;;

  check_prs_gate)
    # Exercises the check-prs/register-pr CLI contract end to end (Decision 3/§3.3
    # PR completion gate), the same way multi_repo_pr exercises register-pr's
    # contract: real CLI calls against the live API, not faked output. The FIRST
    # repo in this run's config.json repos[] is left UNCHANGED but pushed at
    # parity with the default branch (`git push origin HEAD` with no commits
    # added — entrypoint.sh's Step 3 already checked the run branch out at the
    # default branch's tip, so this publishes it to origin with nothing new): no
    # marker file, no register-pr call. This drives check-prs through
    # branch_adds_commits's `ahead == 0` exemption path (branch found on origin
    # via `ls-remote`, GitHub compare shows zero-ahead → exempt), distinct from
    # the branch-absent-on-origin skip path (`ls-remote` exits 2 → continue) that
    # a repo which was never pushed at all would take — both exit 0, but for
    # different reasons (Part 2's E2E coverage gap this scenario closes). Every
    # OTHER repo (already cloned onto its working branch by entrypoint.sh's Step
    # 3, before this scenario ever runs) pushes a marker commit to origin (`git
    # push origin HEAD`, no `-u` — matching the real Implement/Code Review
    # prompts' own push convention, see Decision 5) so check-prs's `git
    # ls-remote` detects the repo as pushed, then registers a PR for it via the
    # real register-pr CLI (same fabricated-PR-URL convention as multi_repo_pr,
    # since there is no real `gh pr create` call here). Finally runs the real
    # check-prs CLI and asserts it reports nothing missing — proving
    # entrypoint.sh's PR-verification block has something real to gate on when a
    # node has needs_pr: true, without a live Claude call.
    echo "Mock agent: check_prs_gate scenario"
    REPOS_JSON=$(jq -r '.repos // empty' /workspace/config.json 2>/dev/null || true)

    if [ -z "$REPOS_JSON" ] || [ "$REPOS_JSON" = "null" ]; then
      echo "No repos configured for this node — nothing for check-prs to gate on."
      write_artifact "result.txt" "check_prs_gate: no repos configured; nothing to verify"
      exit 0
    fi

    repo_index=0
    while IFS= read -r repo; do
      repo_id=$(echo "$repo" | jq -r '.id')
      repo_name=$(echo "$repo" | jq -r '.name')
      repo_url=$(echo "$repo" | jq -r '.url')
      repo_dir=$(echo "$repo" | jq -r '.local_path')

      if [ "$repo_index" -eq 0 ]; then
        echo "[check_prs_gate] $repo_name: left unchanged, pushing the run branch at parity with the default branch"
        (
          cd "$repo_dir"
          git push origin HEAD
        ) || { echo "ERROR: check_prs_gate: git push failed for $repo_name" >&2; exit 1; }
        repo_index=$((repo_index + 1))
        continue
      fi

      echo "[check_prs_gate] $repo_name: committing + pushing a marker change"
      (
        cd "$repo_dir"
        echo "check_prs_gate marker $(date -u +%Y-%m-%dT%H:%M:%SZ)" > .check-prs-gate-marker
        git add .check-prs-gate-marker
        git commit -q -m "mock-agent: check_prs_gate marker" --allow-empty
        git push origin HEAD
      ) || { echo "ERROR: check_prs_gate: git push failed for $repo_name" >&2; exit 1; }

      pr_number=$((RANDOM % 1000 + 1))
      pr_url="${repo_url}/pull/${pr_number}"

      register-pr \
        --repo-id "$repo_id" \
        --pr-url "$pr_url" \
        --pr-number "$pr_number" \
        --title "chore: check_prs_gate marker (${repo_name})" \
        --repo-name "$repo_name"
      repo_index=$((repo_index + 1))
    done < <(echo "$REPOS_JSON" | jq -c '.[]')

    echo "[check_prs_gate] running check-prs..."
    if check-prs; then
      echo "check-prs passed: the parity repo is exempt and every repo carrying commits has a registered PR"
      write_artifact "result.txt" "check_prs_gate: check-prs passed with one repo at parity (exempt) and the rest pushed + registered"
      echo "Mock agent: check_prs_gate completed"
      exit 0
    else
      echo "ERROR: check_prs_gate: check-prs reported missing PR(s) after registering every repo carrying commits" >&2
      exit 1
    fi
    ;;

  roadmap_status_update)
    # Exercises the get-roadmap-graph / update-task-status CLI contract (Roadmap Graph View,
    # Decision 1/3/4) the same way multi_repo_pr exercises register-pr's contract: calls the
    # real API server so E2E drives the same protocol a real agent would, without a live
    # Claude call. The Task is expected to already be in_progress (this run's own
    # Task-start is what created it), matching the internal status endpoint's whitelist.
    echo "Mock agent: roadmap_status_update scenario"
    if [ -z "$EPIC_ID_ARG" ] || [ -z "$TASK_ID_ARG" ]; then
      echo "ERROR: roadmap_status_update requires --epic-id and --task-id" >&2
      exit 1
    fi

    GRAPH=$(get-roadmap-graph --epic-id "$EPIC_ID_ARG")
    if ! echo "$GRAPH" | jq -e --arg id "$TASK_ID_ARG" '.tasks[] | select(.id == $id)' > /dev/null; then
      echo "ERROR: Task $TASK_ID_ARG not found in Epic $EPIC_ID_ARG's graph" >&2
      exit 1
    fi

    update-task-status --task-id "$TASK_ID_ARG" --status done --note "Completed by mock agent"

    write_artifact "result.txt" "Task $TASK_ID_ARG marked done via update-task-status"
    echo "Mock agent: roadmap_status_update completed"
    exit 0
    ;;

  roadmap_status_update_env_default)
    # Exercises Decision 4's environment-default path: get-roadmap-graph and
    # update-task-status are called with NO --epic-id/--task-id flags at all,
    # relying purely on $TASK_ID/$EPIC_ID as ordinary inherited environment.
    # mock-agent.sh runs as entrypoint.sh's $COMMAND (script executor), after
    # entrypoint.sh's unconditional config-parsing/export block already ran, so
    # it inherits TASK_ID/EPIC_ID for free exactly like RUN_ID/API_SERVER_URL
    # today — no independent config.json parsing needed here (Caveat 4).
    echo "Mock agent: roadmap_status_update_env_default scenario"
    if [ -z "${TASK_ID:-}" ]; then
      echo "ERROR: roadmap_status_update_env_default requires \$TASK_ID to be set (start this run from a Task)" >&2
      exit 1
    fi

    GRAPH=$(get-roadmap-graph)
    if [ -n "${EPIC_ID:-}" ] && ! echo "$GRAPH" | jq -e --arg id "$TASK_ID" '.tasks[] | select(.id == $id)' > /dev/null; then
      echo "ERROR: Task $TASK_ID not found in Epic $EPIC_ID's graph" >&2
      exit 1
    fi

    update-task-status --status done --note "Completed by mock agent (env default)"

    write_artifact "result.txt" "Task $TASK_ID marked done via update-task-status (no --task-id flag)"
    echo "Mock agent: roadmap_status_update_env_default completed"
    exit 0
    ;;

  roadmap_status_update_missing_task_id)
    # Negative path backing §6's Behavioral claim: $TASK_ID unset (manual run)
    # plus a bare update-task-status call must exit non-zero with a clear
    # message, not send a malformed/empty id. Unsets $TASK_ID even if
    # entrypoint.sh happened to export one, so this scenario always simulates
    # a manually-started run regardless of how it was launched.
    echo "Mock agent: roadmap_status_update_missing_task_id scenario"
    unset TASK_ID || true

    set +e
    ERROR_OUTPUT=$(update-task-status --status done 2>&1)
    EXIT_CODE=$?
    set -e

    if [ "$EXIT_CODE" -eq 0 ]; then
      echo "ERROR: update-task-status succeeded with no --task-id and \$TASK_ID unset; expected exit 1" >&2
      echo "$ERROR_OUTPUT" >&2
      exit 1
    fi
    if ! echo "$ERROR_OUTPUT" | grep -q "no task id available"; then
      echo "ERROR: update-task-status failed as expected (exit $EXIT_CODE) but without the 'no task id available' message:" >&2
      echo "$ERROR_OUTPUT" >&2
      exit 1
    fi

    write_artifact "result.txt" "update-task-status correctly rejected missing --task-id/\$TASK_ID"
    echo "Mock agent: roadmap_status_update_missing_task_id completed (update-task-status correctly failed)"
    exit 0
    ;;

  roadmap_candidates)
    # Analyzer stand-in for the Roadmap Provisioner's structured candidate-breakdown gate
    # (Decision 1). Writes the same two artifacts BaseRoadmapProvisionerSeeder's real
    # "Roadmap Analyzer" node declares in its outputSpec — roadmap_analysis.md (free-text,
    # unused by materialization) and roadmap_candidates.json (structured, read by
    # RoadmapCandidatesArtifactResolver via the ARTIFACT_FILENAME contract). The JSON is the
    # document shape { milestones[], epics[], dependencies[] } (Decision 5) and must satisfy
    # the same Bean Validation constraints as RoadmapCandidatesDocument/CandidateMilestone/
    # CandidateEpicProposal/CandidateStoryProposal/CandidateTaskProposal/CandidateDependency
    # (non-blank titles/names, <=255 chars, <=8 items per Epic/Story/Task list, <=32 per
    # milestones/dependencies list) or the resolver degrades to null and materialization is
    # skipped. Two Tasks (not one) so the dependency edge below has two distinct keys to
    # reference — a self-edge would be rejected as a trivial cycle.
    echo "Mock agent: roadmap_candidates scenario"
    write_artifact "roadmap_analysis.md" \
      "Mock roadmap analysis: one candidate Epic proposed for E2E coverage of the structured candidate-breakdown gate (Decision 1), with a Milestone, per-level priorities, and a dependency edge (Decision 4/2/5)."

    mkdir -p /workspace/out
    cat > /workspace/out/roadmap_candidates.json <<'JSON'
{
  "milestones": [
    {
      "key": "mock-milestone",
      "name": "Mock Roadmap Milestone",
      "description": "A mock Milestone proposed by the roadmap_candidates mock-agent scenario.",
      "targetDate": "2027-01-01"
    }
  ],
  "epics": [
    {
      "key": "mock-epic",
      "title": "Mock Roadmap Candidate Epic",
      "description": "A mock Epic proposed by the roadmap_candidates mock-agent scenario.",
      "motivation": "Exercises the structured candidate-breakdown gate end-to-end in E2E.",
      "repos": ["e2e-test/mock-repo"],
      "priority": "High",
      "milestone": "mock-milestone",
      "stories": [
        {
          "key": "mock-story",
          "title": "Mock Candidate Story",
          "description": "A mock Story under the candidate Epic.",
          "priority": "Medium",
          "tasks": [
            {
              "key": "mock-task-a",
              "title": "Mock Candidate Task A (blocking)",
              "description": "First mock Task under the candidate Story; blocks Task B.",
              "priority": "High"
            },
            {
              "key": "mock-task-b",
              "title": "Mock Candidate Task B (blocked)",
              "description": "Second mock Task under the candidate Story; blocked by Task A.",
              "priority": "Low"
            }
          ]
        }
      ]
    }
  ],
  "dependencies": [
    {
      "blocking": "mock-task-a",
      "blocked": "mock-task-b"
    }
  ]
}
JSON
    echo "Artifact written to /workspace/out/roadmap_candidates.json"
    echo "Mock agent: roadmap_candidates completed"
    exit 0
    ;;

  roadmap_imperative_links)
    # Imperative counterpart to roadmap_candidates above (Decision 6): drives the real
    # create-proposal/create-story/create-task/create-dependency/create-milestone/
    # update-proposal CLI contract against the API server instead of writing a JSON
    # artifact for a human gate to approve — mirroring how roadmap_status_update above
    # exercises get-roadmap-graph/update-task-status live rather than through a fixture.
    # Each create-* call's JSON response is captured and picked apart with jq for the
    # next call, the same id-chaining idiom check_prs_gate/multi_repo_pr use to thread
    # repo/PR ids through register-pr.
    #
    # Two Tasks (not one) so create-dependency has two distinct ids to link — a
    # self-edge would be rejected as a trivial cycle, same reasoning as the two-Task
    # shape in roadmap_candidates' JSON above. The Epic is created first with
    # --priority high and only assigned to the Milestone at the end via
    # update-proposal --milestone-id, since create-milestone (unlike the declarative
    # artifact's milestone-by-key resolution) returns a real id only once the
    # Milestone itself has been created.
    echo "Mock agent: roadmap_imperative_links scenario"

    EPIC=$(create-proposal \
      --title "Mock Imperative Links Epic" \
      --description "Epic created by the roadmap_imperative_links mock-agent scenario." \
      --priority high)
    EPIC_ID=$(echo "$EPIC" | jq -r '.id')
    echo "Created Epic $EPIC_ID"

    STORY=$(create-story \
      --epic-id "$EPIC_ID" \
      --title "Mock Imperative Links Story" \
      --description "Story created by the roadmap_imperative_links mock-agent scenario." \
      --priority medium)
    STORY_ID=$(echo "$STORY" | jq -r '.id')
    echo "Created Story $STORY_ID"

    TASK_A=$(create-task \
      --epic-id "$EPIC_ID" --story-id "$STORY_ID" \
      --title "Mock Imperative Links Task A (blocking)" \
      --description "First Task; blocks Task B below." \
      --priority high)
    TASK_A_ID=$(echo "$TASK_A" | jq -r '.id')
    echo "Created Task A $TASK_A_ID (priority high)"

    TASK_B=$(create-task \
      --epic-id "$EPIC_ID" --story-id "$STORY_ID" \
      --title "Mock Imperative Links Task B (blocked)" \
      --description "Second Task; blocked by Task A above." \
      --priority low)
    TASK_B_ID=$(echo "$TASK_B" | jq -r '.id')
    echo "Created Task B $TASK_B_ID (priority low)"

    DEPENDENCY=$(create-dependency \
      --blocking-type task --blocking-id "$TASK_A_ID" \
      --blocked-type task --blocked-id "$TASK_B_ID")
    DEPENDENCY_ID=$(echo "$DEPENDENCY" | jq -r '.id')
    echo "Created dependency $DEPENDENCY_ID ($TASK_A_ID blocks $TASK_B_ID)"

    MILESTONE=$(create-milestone \
      --name "Mock Imperative Links Milestone" \
      --description "Milestone created by the roadmap_imperative_links mock-agent scenario." \
      --target-date "2027-01-01")
    MILESTONE_ID=$(echo "$MILESTONE" | jq -r '.id')
    echo "Created Milestone $MILESTONE_ID"

    update-proposal --proposal-id "$EPIC_ID" --milestone-id "$MILESTONE_ID" > /dev/null
    echo "Assigned Epic $EPIC_ID to Milestone $MILESTONE_ID"

    write_artifact "result.txt" \
      "roadmap_imperative_links: epic=$EPIC_ID story=$STORY_ID task_a=$TASK_A_ID(blocking) task_b=$TASK_B_ID(blocked) dependency=$DEPENDENCY_ID milestone=$MILESTONE_ID"
    echo "Mock agent: roadmap_imperative_links completed"
    exit 0
    ;;

  single_repo_claude_md)
    echo "Mock agent: single_repo_claude_md scenario"
    # Verify SYSTEM_PROMPT is exported by entrypoint.sh and visible here.
    # This exercises the 'export SYSTEM_PROMPT' fix (Task C5).
    if [ -n "${SYSTEM_PROMPT:-}" ]; then
      echo "SYSTEM_PROMPT is exported (length: ${#SYSTEM_PROMPT})"
      write_artifact "result.txt" "SYSTEM_PROMPT exported: YES (length=${#SYSTEM_PROMPT})"
    else
      echo "ERROR: SYSTEM_PROMPT is not exported or empty" >&2
      write_artifact "result.txt" "SYSTEM_PROMPT exported: NO"
      exit 1
    fi
    exit 0
    ;;

  dind_isolation)
    echo "Mock agent: dind_isolation scenario"
    if [ -z "${DOCKER_HOST:-}" ]; then
      echo "FAIL: DOCKER_HOST is not set — DinD is not active" >&2
      exit 1
    fi

    echo "DOCKER_HOST=$DOCKER_HOST"
    docker version --format '{{.Server.Version}}' || {
      echo "FAIL: Cannot connect to Docker daemon at $DOCKER_HOST" >&2
      exit 1
    }

    CONTAINER_NAMES=$(docker ps --format '{{.Names}}' 2>/dev/null || true)
    echo "Containers visible from DinD: ${CONTAINER_NAMES:-<none>}"

    for svc in api-server orchestrator postgres temporal minio wiremock; do
      if echo "$CONTAINER_NAMES" | grep -q "$svc"; then
        echo "FAIL: Found ChorusKube service '$svc' in docker ps — isolation broken" >&2
        exit 1
      fi
    done

    write_artifact "result.txt" "PASS: DinD isolation verified — no ChorusKube service containers visible"
    echo "PASS: DinD isolation verified — no ChorusKube service containers visible"
    exit 0
    ;;

  dind_network_connectivity)
    echo "Mock agent: dind_network_connectivity scenario"
    if [ -z "${DOCKER_HOST:-}" ]; then
      echo "FAIL: DOCKER_HOST is not set — DinD is not active" >&2
      exit 1
    fi

    API_URL=$(jq -r '.api_server_url // empty' /workspace/config.json)
    if [ -z "$API_URL" ]; then
      echo "FAIL: api_server_url not in config.json" >&2
      exit 1
    fi

    HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "${API_URL}/actuator/health" \
        2>/dev/null || echo "000")

    if [ "$HTTP_CODE" = "200" ]; then
      write_artifact "result.txt" "PASS: API server reachable from DinD agent (HTTP $HTTP_CODE)"
      echo "PASS: API server reachable from DinD agent (HTTP $HTTP_CODE)"
    else
      echo "FAIL: API server unreachable from DinD agent (HTTP $HTTP_CODE)" >&2
      exit 1
    fi
    exit 0
    ;;

  many_artifacts)
    # Fixture scenario for the artifact viewer's "content pane collapses when a node
    # has many files" layout regression. Writes $ARTIFACT_COUNT small distinct files
    # so E2E can exercise the file-switcher pill row at realistic-to-large scale.
    echo "Mock agent: many_artifacts scenario — writing ${ARTIFACT_COUNT} files"
    mkdir -p /workspace/out
    for ((i = 1; i <= ARTIFACT_COUNT; i++)); do
      printf 'Mock artifact %d of %d\n' "$i" "$ARTIFACT_COUNT" \
        > "/workspace/out/$(printf 'report-%03d.txt' "$i")"
    done
    echo "Mock agent: many_artifacts completed — wrote ${ARTIFACT_COUNT} files"
    exit 0
    ;;

  rate_limited)
    # Parks like a real agent that hit the org's Claude quota: reports the park
    # status with a reset time MOCK_RESUME_SECONDS out (default 5s so E2E does
    # not wait), plus a synthetic session reference, matching the real agent's
    # callback field-for-field (entrypoint.sh's Step 6: node_execution_id, run_id,
    # status, result, artifact_refs, error_message, resume_at, session_id,
    # session_artifact_path). On the resumed iteration config.json carries
    # session_id — the same key the real entrypoint reads as RESUME_SESSION_ID,
    # and the same one activities.go writes back into the next iteration's
    # config.json once a park sets it — so we complete instead of parking again.
    #
    # session_id and session_artifact_path are coupled in the real emission and
    # must stay that way here: entrypoint.sh:1191 derives session_id as
    # "${SESSION_ARTIFACT_PATH:+$CLAUDE_SESSION_ID}", so session_id is non-empty
    # ONLY when SESSION_ARTIFACT_PATH is non-empty, and entrypoint.sh:1158 clears
    # SESSION_ARTIFACT_PATH="" on an upload failure precisely so both fields go
    # null together (a sessionless park; the next iteration starts fresh, per
    # activities.go's own comment on the pair). A non-null session_id next to a
    # null session_artifact_path is a combination the real agent can never
    # produce, so this scenario emits the successful-park shape: both fields
    # non-null, session_artifact_path a synthetic path of the same
    # runs/<run>/<exec>/session/<session-id>.jsonl shape entrypoint.sh builds
    # from "${OUTPUT_PATH%out/}session/${CLAUDE_SESSION_ID}.jsonl".
    #
    # Completion here is a bare exit 0, the same idiom as the 'success' scenario,
    # not a report-result call: report-result's contract is a single positional
    # <decision> argument for a human-gate node's routing decision (see
    # submit_decision above), a different node type from the script-executor one
    # this scenario is designed for. entrypoint.sh's own script-executor path
    # already submits the passed/failed decision and default "completed" status
    # once this command returns, so nothing more is needed on the resume path.
    echo "Mock agent: rate_limited scenario"
    RESUMED=$(jq -r '.session_id // empty' /workspace/config.json)
    if [ -n "$RESUMED" ]; then
      echo "Mock agent: resumed session $RESUMED — completing instead of parking again"
      write_artifact "result.txt" "Resumed after quota park (session $RESUMED)"
      exit 0
    fi
    RESET_AT=$(date -u -d "@$(( $(date -u +%s) + ${MOCK_RESUME_SECONDS:-5} ))" '+%Y-%m-%dT%H:%M:%SZ')
    SESSION="mock-session-${NODE_EXECUTION_ID}"
    SESSION_PATH="runs/${RUN_ID}/${NODE_EXECUTION_ID}/session/${SESSION}.jsonl"
    # Assigned to a variable first, then passed, rather than
    # send-callback "$(jq -n ...)" inline — the same two-step shape
    # entrypoint.sh's own Step 6 uses to build CALLBACK_BODY.
    CALLBACK_BODY=$(jq -n \
      --arg id "$NODE_EXECUTION_ID" --arg run_id "$RUN_ID" \
      --arg resume_at "$RESET_AT" --arg session_id "$SESSION" \
      --arg session_path "$SESSION_PATH" \
      '{node_execution_id:$id, run_id:$run_id, status:"rate_limited",
        result:"You'"'"'ve hit your session limit",
        artifact_refs:{}, error_message:"Claude quota exhausted",
        resume_at:$resume_at, session_id:$session_id,
        session_artifact_path:$session_path}')
    send-callback "$CALLBACK_BODY"
    echo "Mock agent: rate_limited — parked session $SESSION until $RESET_AT"
    exit 0
    ;;

  "")
    echo "ERROR: No scenario specified" >&2
    echo "Usage: mock-agent.sh <scenario> [options]" >&2
    echo "Scenarios: success, failure, timeout, slow, flaky, gate_approve, gate_reject, live_chat, multi_repo_pr, check_prs_gate, roadmap_status_update, roadmap_status_update_env_default, roadmap_status_update_missing_task_id, roadmap_candidates, roadmap_imperative_links, single_repo_claude_md, dind_isolation, dind_network_connectivity, many_artifacts, rate_limited" >&2
    exit 1
    ;;

  *)
    echo "ERROR: Unknown scenario '$SCENARIO'" >&2
    echo "Scenarios: success, failure, timeout, slow, flaky, gate_approve, gate_reject, live_chat, multi_repo_pr, check_prs_gate, roadmap_status_update, roadmap_status_update_env_default, roadmap_status_update_missing_task_id, roadmap_candidates, roadmap_imperative_links, single_repo_claude_md, dind_isolation, dind_network_connectivity, many_artifacts, rate_limited" >&2
    exit 1
    ;;
esac
