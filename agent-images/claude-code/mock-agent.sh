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
#                          "Roadmap Analyzer" node declares.
#   single_repo_claude_md  Verify SYSTEM_PROMPT is exported (tests the export fix)
#   dind_isolation   Verify DinD isolation: DOCKER_HOST set, no ChorusKube services visible
#   dind_network_connectivity  Verify API server is reachable from DinD agent
#   many_artifacts   Write --count small output files (default: 40), for E2E fixtures
#                    exercising the artifact viewer's many-files layout (see
#                    ArtifactViewerDialog.tsx)
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
    # RoadmapCandidatesArtifactResolver via the ARTIFACT_FILENAME contract). The JSON must
    # satisfy the same Bean Validation constraints as CandidateEpicProposal/
    # CandidateStoryProposal/CandidateTaskProposal (non-blank titles, <=255 chars, <=8 items
    # per list) or the resolver degrades to null and materialization is skipped.
    echo "Mock agent: roadmap_candidates scenario"
    write_artifact "roadmap_analysis.md" \
      "Mock roadmap analysis: one candidate Epic proposed for E2E coverage of the structured candidate-breakdown gate (Decision 1)."

    mkdir -p /workspace/out
    cat > /workspace/out/roadmap_candidates.json <<'JSON'
[
  {
    "title": "Mock Roadmap Candidate Epic",
    "description": "A mock Epic proposed by the roadmap_candidates mock-agent scenario.",
    "motivation": "Exercises the structured candidate-breakdown gate end-to-end in E2E.",
    "repos": ["e2e-test/mock-repo"],
    "priority": "medium",
    "stories": [
      {
        "title": "Mock Candidate Story",
        "description": "A mock Story under the candidate Epic.",
        "tasks": [
          {
            "title": "Mock Candidate Task",
            "description": "A mock Task under the candidate Story."
          }
        ]
      }
    ]
  }
]
JSON
    echo "Artifact written to /workspace/out/roadmap_candidates.json"
    echo "Mock agent: roadmap_candidates completed"
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

  "")
    echo "ERROR: No scenario specified" >&2
    echo "Usage: mock-agent.sh <scenario> [options]" >&2
    echo "Scenarios: success, failure, timeout, slow, flaky, gate_approve, gate_reject, live_chat, multi_repo_pr, roadmap_status_update, roadmap_status_update_env_default, roadmap_status_update_missing_task_id, roadmap_candidates, single_repo_claude_md, dind_isolation, dind_network_connectivity, many_artifacts" >&2
    exit 1
    ;;

  *)
    echo "ERROR: Unknown scenario '$SCENARIO'" >&2
    echo "Scenarios: success, failure, timeout, slow, flaky, gate_approve, gate_reject, live_chat, multi_repo_pr, roadmap_status_update, roadmap_status_update_env_default, roadmap_status_update_missing_task_id, roadmap_candidates, single_repo_claude_md, dind_isolation, dind_network_connectivity, many_artifacts" >&2
    exit 1
    ;;
esac
