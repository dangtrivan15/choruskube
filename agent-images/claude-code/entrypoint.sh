#!/bin/bash
# agent-images/claude-code/entrypoint.sh
set -euo pipefail

# --- Configuration ---
CONFIG_FILE="/workspace/config.json"
WORKSPACE_IN="/workspace/in"
WORKSPACE_OUT="/workspace/out"

if [ ! -f "$CONFIG_FILE" ]; then
  echo "ERROR: $CONFIG_FILE not found"
  exit 1
fi

# Export variables used by helper scripts (report-result, fetch-github-token, check-decision, etc.)
export NODE_EXECUTION_ID=$(jq -r '.node_execution_id' "$CONFIG_FILE")
export RUN_ID=$(jq -r '.run_id' "$CONFIG_FILE")
PROMPT=$(jq -r '.prompt' "$CONFIG_FILE")
export CALLBACK_URL=$(jq -r '.callback_url' "$CONFIG_FILE")
OUTPUT_PATH=$(jq -r '.output_path' "$CONFIG_FILE")
REPO_URL=$(jq -r '.repo_url // empty' "$CONFIG_FILE")
WORKING_BRANCH=$(jq -r '.working_branch // empty' "$CONFIG_FILE")
export GITHUB_TOKEN_URL=$(jq -r '.github_token_url // empty' "$CONFIG_FILE")
COMMAND=$(jq -r '.command // empty' "$CONFIG_FILE")
EXECUTOR_TYPE=$(jq -r '.executor_type // "ai"' "$CONFIG_FILE")
MODEL=$(jq -r '.model // empty' "$CONFIG_FILE")
# A session parked by a previous iteration on a quota hit (see the "Quota resume"
# block below). Both empty on an ordinary run; set only when this execution
# carries a park reference forward.
RESUME_SESSION_ID=$(jq -r '.session_id // empty' "$CONFIG_FILE")
RESUME_SESSION_PATH=$(jq -r '.session_artifact_path // empty' "$CONFIG_FILE")
# Not validated, unlike max_turns/max_retries below: a bad effort is not
# structurally fatal — claude warns and falls back to its default, degrading the
# run rather than breaking it.
EFFORT=$(jq -r '.effort // empty' "$CONFIG_FILE")
# --- Per-node turn/retry budget (max_turns / max_retries) ---
# Validate before any run_claude() call: a typo'd value must fail loudly here
# rather than reach claude's argv unexamined or collapse the retry loops to zero
# attempts. Empty means "not configured"; defaults are set in the AI branch below.
MAX_TURNS=$(jq -r '.max_turns // empty' "$CONFIG_FILE")
MAX_RETRIES=$(jq -r '.max_retries // empty' "$CONFIG_FILE")
validate_positive_int() {
  # $1 = config.json key name (for the message), $2 = its value.
  if [ -n "$2" ] && ! [[ "$2" =~ ^[1-9][0-9]*$ ]]; then
    echo "ERROR: unsupported $1 value '$2' (must be a positive integer)" >&2
    exit 1
  fi
}
validate_positive_int max_turns "$MAX_TURNS"
validate_positive_int max_retries "$MAX_RETRIES"
# Build system prompt from image-local template + run context
SYSTEM_PROMPT_FILE="/usr/local/share/choruskube/system_prompt.md"
if [ -f "$SYSTEM_PROMPT_FILE" ]; then
  SYSTEM_PROMPT="$(cat "$SYSTEM_PROMPT_FILE")"
else
  SYSTEM_PROMPT=""
fi
RUN_LOG_PATH=$(jq -r '.run_log_path // empty' "$CONFIG_FILE")
export API_SERVER_URL=$(jq -r '.api_server_url // empty' "$CONFIG_FILE")
NEED_DECISION=$(jq -r '.need_decision // false' "$CONFIG_FILE")
NEED_PR=$(jq -r '.needs_pr // false' "$CONFIG_FILE")

# Triggering Task's identity, present only for Task-started runs; Story/Epic may
# each be empty even when TASK_ID is set. update-task-status/get-roadmap-graph
# default their --task-id/--epic-id flags from these.
export TASK_ID=$(jq -r '.task_context.task_id // empty' "$CONFIG_FILE")
export TASK_TITLE=$(jq -r '.task_context.task_title // empty' "$CONFIG_FILE")
export STORY_ID=$(jq -r '.task_context.story_id // empty' "$CONFIG_FILE")
export STORY_TITLE=$(jq -r '.task_context.story_title // empty' "$CONFIG_FILE")
export EPIC_ID=$(jq -r '.task_context.epic_id // empty' "$CONFIG_FILE")
export EPIC_TITLE=$(jq -r '.task_context.epic_title // empty' "$CONFIG_FILE")

# The Task's own not-yet-done incoming blocking edges — informational, does not
# gate the run. "// []" defaults to empty when task_context or the open_blockers
# key is absent, so an older config.json from a mismatched API-server never crashes here.
OPEN_BLOCKERS_JSON=$(jq -c '.task_context.open_blockers // []' "$CONFIG_FILE")
export OPEN_BLOCKERS_JSON

if [ -z "${JOB_SECRET:-}" ]; then
  echo "ERROR: JOB_SECRET environment variable not set"
  exit 1
fi

# Claude credentials arrive via the CLAUDE_CODE_OAUTH_TOKEN env var, read natively
# as a bearer token. Script-executor nodes get no token on purpose, so this check
# is gated to AI/human/both executors.
if [ "$EXECUTOR_TYPE" != "script" ] && [ -z "${CLAUDE_CODE_OAUTH_TOKEN:-}" ]; then
  echo "ERROR: CLAUDE_CODE_OAUTH_TOKEN env var is empty for executor_type=$EXECUTOR_TYPE"
  echo "       Generate one with \`claude setup-token\` and inject it into the api-server pod."
  exit 1
fi

# --- Step 1: Pull input artifacts via presigned URLs ---
# Keys are paths ("<source_label>/<filename>"), not flat names, so create each
# parent dir. Absence is normal — a prior iteration missing on iteration 1, an
# unattached gate file — so only api-server-required keys are fatal; the rest log and continue.
mkdir -p "$WORKSPACE_IN"
REQUIRED_KEYS=$(jq -r '.required_input_artifacts // [] | .[]' "$CONFIG_FILE" 2>/dev/null || true)
INPUT_KEYS=$(jq -r '.input_artifacts // {} | keys[]' "$CONFIG_FILE" 2>/dev/null || true)
while IFS= read -r key; do
  [ -z "$key" ] && continue
  MINIO_PATH=$(jq -r ".input_artifacts.\"$key\"" "$CONFIG_FILE")
  DEST="${WORKSPACE_IN}/${key}"
  mkdir -p "$(dirname "$DEST")"
  if artifact get "$MINIO_PATH" "$DEST" 2>/dev/null; then
    echo "Pulled input artifact: $key from $MINIO_PATH"
  elif printf '%s\n' "$REQUIRED_KEYS" | grep -qxF "$key"; then
    echo "ERROR: required input artifact missing: $key ($MINIO_PATH)" >&2
    exit 1
  else
    echo "Optional input artifact absent, skipping: $key ($MINIO_PATH)"
  fi
done <<< "$INPUT_KEYS"

# Pull run log (may not exist for first iteration, that's OK)
if [ -n "$RUN_LOG_PATH" ]; then
  artifact get "$RUN_LOG_PATH" "${WORKSPACE_IN}/run_log.md" 2>/dev/null || true
fi

# --- Step 2: Configure git credentials (before clone, so private repos work) ---
# Identity is required by git rebase (it stamps the committer) and by in-repo
# commits. Set unconditionally: harmless when unused, and without it the
# rebase-on-clone below silently falls back.
git config --global user.email "agent@choruskube.local"
git config --global user.name "ChorusKube Agent"

if [ -n "$GITHUB_TOKEN_URL" ]; then
  git config --global credential.helper \
    '!f() { echo "protocol=https"; echo "host=github.com"; echo "username=x-access-token"; echo "password=$(fetch-github-token)"; }; f'
  TOKEN=$(fetch-github-token 2>/dev/null || true)
  if [ -n "$TOKEN" ] && [ "$TOKEN" != "null" ]; then
    echo "$TOKEN" | gh auth login --with-token 2>/dev/null || true
  fi
  echo "Git credentials configured"
fi

# --- Step 3: Clone repo(s) if configured ---
REPOS_JSON=$(jq -r '.repos // empty' /workspace/config.json)

if [ -n "$REPOS_JSON" ] && [ "$REPOS_JSON" != "null" ]; then
  echo "Multi-repo mode: cloning repositories..."

  # Launch all clones in parallel
  PIDS=()
  while IFS= read -r repo; do
    repo_url=$(echo "$repo" | jq -r '.url')
    repo_name=$(echo "$repo" | jq -r '.name')
    repo_branch=$(echo "$repo" | jq -r '.working_branch // empty')
    repo_path=$(echo "$repo" | jq -r '.local_path')

    (
      echo "Cloning $repo_name: $repo_url -> $repo_path"
      git clone --depth 1 --no-single-branch "$repo_url" "$repo_path"
      if [ -n "$repo_branch" ]; then
        cd "$repo_path"
        git checkout "$repo_branch" 2>/dev/null || git checkout -b "$repo_branch"
        # Best-effort rebase onto origin/main so safety-net commits reach run
        # branches cut from an older base. Depth 200 covers typical branch
        # lifetimes; on conflict, abort and continue on the stale base rather than break the run.
        if git fetch --depth=200 origin main "$repo_branch" 2>/dev/null; then
          if ! git rebase origin/main; then
            echo "WARNING: rebase onto origin/main failed for $repo_name; continuing on stale base" >&2
            git rebase --abort 2>/dev/null || true
          fi
        else
          echo "WARNING: could not fetch origin/main for $repo_name; continuing on stale base" >&2
        fi
        cd /workspace
      fi
      echo "Cloned $repo_name"
    ) &
    PIDS+=($!)
  done < <(echo "$REPOS_JSON" | jq -c '.[]')

  # Wait for all clones; fail if any clone failed
  CLONE_FAILED=0
  for pid in "${PIDS[@]}"; do
    wait "$pid" || CLONE_FAILED=1
  done
  if [ "$CLONE_FAILED" = "1" ]; then
    echo "ERROR: One or more repository clones failed" >&2
    exit 1
  fi

  echo "Multi-repo workspace ready. $(echo "$REPOS_JSON" | jq length) repos cloned."

  # Describe the multi-repo workspace in the system prompt. Repos are peers, no primary.
  REPO_LIST=""
  while IFS= read -r repo; do
    repo_path=$(echo "$repo" | jq -r '.local_path')
    REPO_LIST="${REPO_LIST}\n- ${repo_path}"
  done < <(echo "$REPOS_JSON" | jq -c '.[]')

  SYSTEM_PROMPT="${SYSTEM_PROMPT}

## Multi-Repository Workspace
This is a multi-repository run. Repositories available:
$(echo -e "$REPO_LIST")

All repositories are cloned under /workspace/repo/. Your working directory is
/workspace/repo, which is not itself a git repository — use absolute paths, and
cd into a specific repo before running git commands."

elif [ -n "$REPO_URL" ]; then
  # Single-repo mode (backwards compatible)
  echo "Cloning $REPO_URL..."
  git clone --depth 1 --no-single-branch "$REPO_URL" /workspace/repo/
  if [ -n "$WORKING_BRANCH" ]; then
    cd /workspace/repo
    git checkout "$WORKING_BRANCH" 2>/dev/null || git checkout -b "$WORKING_BRANCH"
    # See multi-repo path above for rationale.
    if git fetch --depth=200 origin main "$WORKING_BRANCH" 2>/dev/null; then
      if ! git rebase origin/main; then
        echo "WARNING: rebase onto origin/main failed; continuing on stale base" >&2
        git rebase --abort 2>/dev/null || true
      fi
    else
      echo "WARNING: could not fetch origin/main; continuing on stale base" >&2
    fi
    cd /workspace
  fi
  echo "Repo ready at /workspace/repo/"
fi

# Narrate the Task identity into the system prompt too: the exports above drive
# the roadmap CLI tools, but an env var the model never sees is invisible to it.
if [ -n "$TASK_ID" ]; then
  SYSTEM_PROMPT="${SYSTEM_PROMPT}

## Triggering Task
This run was started from Task: ${TASK_TITLE}${STORY_TITLE:+
Story: ${STORY_TITLE}}${EPIC_TITLE:+
Epic: ${EPIC_TITLE}}

You can call \`get-roadmap-graph\` without passing --epic-id — it defaults to this
run's Epic automatically. Do not mark this Task done: it closes by itself once this
run's pull requests are merged."

  # Narrate open blockers. Readiness gates Task start, so open_blockers is empty at
  # launch — this block only fires for an edge added mid-run.
  BLOCKER_COUNT=$(echo "$OPEN_BLOCKERS_JSON" | jq 'length')
  if [ "$BLOCKER_COUNT" -gt 0 ]; then
    BLOCKER_LINES=$(echo "$OPEN_BLOCKERS_JSON" | jq -r '.[] | "- \(.title) (\(.item_type), status: \(.status))"')
    SYSTEM_PROMPT="${SYSTEM_PROMPT}

## Open Blockers
This Task has ${BLOCKER_COUNT} unresolved blocking dependencies not yet done:
${BLOCKER_LINES}

These appeared after this run started — this Task was ready when it launched.
Factor the change into your remaining work."
  fi
fi
export SYSTEM_PROMPT

# --- Dependency proxy (Gradle init script) ---
# Must run AFTER the clone: the helper ships in the repo tree. Only Gradle needs
# it; GOPROXY/npm_config_registry are injected as env by the executor. Sources the
# first dep-proxy.sh match (single- or multi-repo); no-op if unset or absent.
if [ -n "${DEP_PROXY_BASE:-}" ]; then
  for _dp in /workspace/repo/scripts/lib/dep-proxy.sh /workspace/repo/*/scripts/lib/dep-proxy.sh; do
    if [ -f "$_dp" ]; then
      source "$_dp"
      apply_dep_proxy "${GRADLE_USER_HOME:-$HOME/.gradle}"
      break
    fi
  done

  # npm bakes the registry it fetched from into package-lock.json "resolved" URLs,
  # so a dependency the agent adds would commit the proxy host. This pre-commit hook
  # normalizes staged lockfiles back to the public registry (see normalize-lockfiles).
  # core.hooksPath is global to cover every clone; a repo's own husky hooksPath still wins.
  HOOKS_DIR="$HOME/.choruskube-git-hooks"
  mkdir -p "$HOOKS_DIR"
  cat > "$HOOKS_DIR/pre-commit" <<'HOOK'
#!/bin/bash
set -euo pipefail
# read loop, not mapfile (bash 4+): this hook can run on a stock macOS shell.
LOCKS=()
while IFS= read -r f; do
  [ -n "$f" ] && LOCKS+=("$f")
done < <(git diff --cached --name-only --diff-filter=ACM \
  | grep -E '(^|/)package-lock\.json$' || true)
[ "${#LOCKS[@]}" -gt 0 ] || exit 0
normalize-lockfiles "${LOCKS[@]}"
# Re-stage so the rewrite lands in this commit rather than the working tree.
git add -- "${LOCKS[@]}"
HOOK
  chmod +x "$HOOKS_DIR/pre-commit"
  git config --global core.hooksPath "$HOOKS_DIR"
fi

# --- Step 3b: Declare the workspace roots to Claude Code ---
# Claude Code loads CLAUDE.md/.claude from the cwd plus each --add-dir; it does NOT
# descend into subdirs on its own, so every repo must be named explicitly.
# CLAUDE_CODE_ADDITIONAL_DIRECTORIES_CLAUDE_MD (Dockerfile) makes CLAUDE.md load from added dirs.
ADD_DIR_ARGS=()
if [ -n "$REPOS_JSON" ] && [ "$REPOS_JSON" != "null" ]; then
  for repo_dir in /workspace/repo/*/; do
    [ -d "$repo_dir" ] && ADD_DIR_ARGS+=(--add-dir "$repo_dir")
  done
elif [ -d /workspace/repo ]; then
  ADD_DIR_ARGS+=(--add-dir /workspace/repo)
fi
echo "Workspace roots: ${ADD_DIR_ARGS[*]:-none}"

export HEARTBEAT_URL="${CALLBACK_URL%/callback}/heartbeat"

# Shared stream file: run_claude writes it, send-heartbeat reads it. Heartbeats
# only POST when the event count advances, so a stuck Claude session stops
# heartbeating and Temporal's timeout fires instead of running out the full deadline.
export CLAUDE_STREAM_FILE="/tmp/claude_stream_current.jsonl"

# shellcheck source=/dev/null
source /usr/local/bin/quota-lib.sh

# --- Heartbeat loop (background) ---
heartbeat_loop() {
    while true; do
        sleep 60
        send-heartbeat  # errors suppressed inside the script
    done
}
heartbeat_loop &
HEARTBEAT_PID=$!

cleanup_heartbeat() {
    kill $HEARTBEAT_PID 2>/dev/null || true
}
trap cleanup_heartbeat EXIT

# --- Step 4: Execute ---
mkdir -p "$WORKSPACE_OUT"
RESULT_STATUS="completed"
RESULT="completed"
ERROR_MESSAGE=""
CLAUDE_OUTPUT=""
# Init here or set -u aborts the pod when a script/skipped-agent node reaches the
# quota-park block and callback below without entering the AI branch that assigns
# them — aborting before send-callback runs, so the node only surfaces at heartbeat timeout.
QUOTA_RESET_AT=""
CLAUDE_SESSION_ID=""

# --- Compose decisions suffix into the system prompt (AI nodes only) ---
# Append the decisions this node may submit so the agent knows them up front.
# Fetch failure is fatal here: an agent that doesn't know its allowed decisions
# invents values that 400 at submit time, wasting a full run.
SKIP_AGENT_INVOCATION=false
if [ "$NEED_DECISION" = "true" ] && [ "$EXECUTOR_TYPE" != "script" ] && [ -n "${API_SERVER_URL:-}" ]; then
  echo "Fetching valid decisions for this node execution..."
  if VALID_DECISIONS=$(list-decisions); then
    if [ -n "$VALID_DECISIONS" ]; then
      DECISIONS_LIST="- ${VALID_DECISIONS//$'\n'/$'\n'- }"
      SYSTEM_PROMPT="${SYSTEM_PROMPT}

## Decisions you may submit for this node

Submit exactly one of the following via \`report-result <decision>\`:

${DECISIONS_LIST}

Knowing this set up-front frames how to approach the work. The absence of a decision (e.g. \`redraft\`) means this node is not authorized for that mode of conclusion — focus on the modes that are listed."
      export SYSTEM_PROMPT
      DECISION_COUNT=$(echo "$VALID_DECISIONS" | wc -l | tr -d ' ')
      echo "Composed system prompt with $DECISION_COUNT valid decisions"
    else
      echo "list-decisions returned empty (node has no conditional edges)"
    fi
  else
    echo "ERROR: list-decisions failed — cannot start agent without knowing valid decisions" >&2
    SKIP_AGENT_INVOCATION=true
    RESULT_STATUS="failed"
    ERROR_MESSAGE="list-decisions failed at agent startup; aborting before invocation"
  fi
fi

# --- Compose the Supervisor escalation contract (AI nodes only) ---
# Only when the template declares a routing_hub (surfaced as config.json's
# "supervisor" key), so a graph version without one gets no escalation framing.
SUPERVISOR_LABEL=$(jq -r '.supervisor.label // ""' "$CONFIG_FILE")
if [ -n "$SUPERVISOR_LABEL" ] && [ "$EXECUTOR_TYPE" != "script" ]; then
  SYSTEM_PROMPT="${SYSTEM_PROMPT}

## Escalating to the Supervisor

This workflow's graph describes the happy path only. When you cannot proceed along it, do
NOT guess and do NOT force a decision that misrepresents your confidence. Submit
\`report-result escalate\` instead. That pages the Supervisor: a human who reads your
escalation and then routes the run to whichever node they judge correct — which may be any
node in the workflow, including one that skips steps ahead of you.

Escalating is a normal, expected outcome, not a failure. Use it whenever a judgement call is
genuinely not yours to make.

**Before calling \`report-result escalate\` you MUST write \`/workspace/out/escalation.md\`.**
An escalation without it is rejected and your node fails. Use exactly this structure:

\`\`\`markdown
---
category: review_conflict | uncertainty | alternative_proposal | environment | blocked_external
summary: <one line the reviewer sees before opening the document>
---
## What I was doing
## Why I can't proceed
## Options I considered
(each with its tradeoff)
## What I need from you
(and, if you have one, a suggested next node — advisory only; the Supervisor decides)
\`\`\`

Pick \`category\` from exactly these five:

- \`review_conflict\` — your fix would reverse or re-litigate a prior iteration's decision.
- \`uncertainty\` — the flaw is real but no candidate fix is clearly correct.
- \`alternative_proposal\` — a fundamentally different approach would be better.
- \`environment\` — you are blocked by infrastructure or tooling, not by the code under change.
- \`blocked_external\` — you are blocked on something outside this run's reach.

If the Supervisor routes work back to you, their guidance arrives at
\`/workspace/in/${SUPERVISOR_LABEL}/human_guidance.md\`. Read it first and honour it.

**If you later resolve the blocker yourself, retract the escalation before you finish:**

\`\`\`
report-result --withdraw
\`\`\`

This matters when something later in the node — including a prompt from this harness after
you escalated — unblocks you. The decision was written the moment you submitted it and does
not expire on its own, so a node that finishes carrying a stale \`escalate\` is rejected for
a missing escalation.md it no longer needs. Withdrawing is recorded, not erased: the
Supervisor can still see that you asked and then stood down. Withdraw only when you are
genuinely unblocked — if the judgement call is still not yours to make, escalate properly
and write the document."
  export SYSTEM_PROMPT
  echo "Composed system prompt with Supervisor escalation contract (label=$SUPERVISOR_LABEL)"
fi

# Compose a PR-requirement note into the system prompt (AI nodes only). Informational
# only — the actual gate runs after the session (see "PR verification" below); this
# tells the agent up front instead of only on retry.
if [ "$NEED_PR" = "true" ] && [ "$EXECUTOR_TYPE" != "script" ]; then
  SYSTEM_PROMPT="${SYSTEM_PROMPT}

## Pull Request Requirement

Before you finish, every repo you pushed commits to this run must have a
registered pull request: open (or update) its PR and run \`register-pr\` for
it. If any repo you pushed to is left without a registered PR, this node will
be resumed and asked to fix it before it can complete."
  export SYSTEM_PROMPT
fi

if [ "$SKIP_AGENT_INVOCATION" = "true" ]; then
  echo "Skipping agent invocation: $ERROR_MESSAGE"
  # Nothing ran; clear the "completed" sentinel so it isn't reported for a failed
  # node. The failure composer below supplies the real text.
  RESULT=""
elif [ "$EXECUTOR_TYPE" = "script" ]; then
  echo "Running script: $COMMAND"
  cd /workspace/repo 2>/dev/null || cd /workspace
  set +e
  SCRIPT_OUTPUT=$(eval "$COMMAND" 2>&1)
  SCRIPT_EXIT=$?
  set -e

  if [ $SCRIPT_EXIT -eq 0 ]; then
    SCRIPT_DECISION="passed"
    echo "Script passed"
  else
    SCRIPT_DECISION="failed"
    echo "Script failed (exit $SCRIPT_EXIT)"
  fi

  # Submit decision via API (same endpoint as report-result for AI nodes)
  if [ -n "$API_SERVER_URL" ]; then
    REPORT_RESULT_URL="$API_SERVER_URL/internal/runs/$RUN_ID/node-executions/$NODE_EXECUTION_ID/decision"
    DECISION_RESPONSE=$(curl -s -w "\n%{http_code}" -X PUT \
        "$REPORT_RESULT_URL" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $JOB_SECRET" \
        -d "{\"decision\": \"$SCRIPT_DECISION\"}")
    DECISION_HTTP=$(echo "$DECISION_RESPONSE" | tail -1)
    if [ "$DECISION_HTTP" = "200" ]; then
      echo "Decision '$SCRIPT_DECISION' submitted successfully"
    else
      echo "WARNING: Decision submission returned HTTP $DECISION_HTTP (non-fatal)"
    fi
  fi

  # Full output to a file, not RESULT: RESULT lands in run_log.md (read by every
  # downstream node) and a large value hits ARG_MAX. test_report.md isn't always
  # produced, so RESULT points at it only when present, else the raw output.
  mkdir -p /workspace/out
  echo "$SCRIPT_OUTPUT" > /workspace/out/test_output.txt
  if [ -f /workspace/out/test_report.md ]; then
    RESULT="Read test_report.md first — it is the index: verdict, the failing tests named per component, and the archived reports. test_output.txt has the raw script output."
  else
    RESULT="Read test_output.txt for full script output"
  fi
else
  # AI path — run Claude Code with retry on missing result

  if [ -n "$API_SERVER_URL" ]; then
    export REPORT_RESULT_URL="$API_SERVER_URL/internal/runs/$RUN_ID/node-executions/$NODE_EXECUTION_ID/decision"
  fi

  # /workspace/repo is the repo (single-repo) or the parent of the clones
  # (multi-repo); either way the workspace root. Repos are declared via ADD_DIR_ARGS.
  if [ -d /workspace/repo ]; then
    cd /workspace/repo
  fi

  # Refresh GitHub token — installation tokens expire after 1 hour,
  # and human review gates can delay execution by hours/days
  if [ -n "$GITHUB_TOKEN_URL" ]; then
    FRESH_TOKEN=$(fetch-github-token 2>/dev/null || true)
    if [ -n "$FRESH_TOKEN" ] && [ "$FRESH_TOKEN" != "null" ]; then
      echo "$FRESH_TOKEN" | gh auth login --with-token 2>/dev/null || true
      export GH_TOKEN="$FRESH_TOKEN"
      echo "GitHub token refreshed before AI execution"
    fi
  fi

  # Log tool_use events to /dev/stderr directly (run_claude runs inside $(...), which
  # captures stdout). Order matters: /dev/stderr is /proc/self/fd/2, so 2>/dev/null
  # before >/dev/stderr would send all progress to /dev/null — stdout to fd 2 first.
  log_progress() {
    local line="$1"
    local msg_type
    msg_type=$(echo "$line" | jq -r '.type // empty' 2>/dev/null) || return 0
    if [ "$msg_type" = "assistant" ]; then
      echo "$line" | jq -r '
        .message.content[]? | select(.type == "tool_use") |
        if .name == "Read" then "  → Read " + (.input.file_path // "")
        elif .name == "Edit" then "  → Edit " + (.input.file_path // "")
        elif .name == "Write" then "  → Write " + (.input.file_path // "")
        elif .name == "Bash" then "  → Bash " + (.input.command // (.input.description // "") | .[0:80])
        elif .name == "Glob" then "  → Glob " + (.input.pattern // "")
        elif .name == "Grep" then "  → Grep " + (.input.pattern // "")
        else "  → " + .name
        end
      ' > /dev/stderr 2>/dev/null || true
    elif [ "$msg_type" = "result" ]; then
      echo "$line" | jq -r '
        "  ✓ Done: turns=" + (.num_turns // "?" | tostring) +
        " cost=$" + (.total_cost_usd // 0 | tostring)
      ' > /dev/stderr 2>/dev/null || true
    fi
  }

  # Helper: run claude with streaming progress output
  run_claude() {
    local prompt="$1"
    local extra_flags="${2:-}"
    local sys_prompt="${3:-}"
    local sys_args=""
    if [ -n "$sys_prompt" ]; then
      sys_args="--system-prompt"
    fi
    local stream_file="${CLAUDE_STREAM_FILE:-/tmp/claude_stream_$$.jsonl}"
    # Truncate before each invocation so send-heartbeat's line-count comparison
    # observes a fresh stream for every claude run (retries, decision retries).
    : > "$stream_file"
    # stdbuf -oL forces line-buffered stdout so events flow through the tee|while
    # pipeline in real time instead of block-buffering; tee also saves the stream for
    # parse_claude_output.
    # --max-turns is a CLI flag, not a settings.json key: an unrecognized settings key
    # rejects the whole file. Hitting the cap exits non-zero with is_error set, which
    # the caller gates on.
    set +e
    stdbuf -oL claude -p "$prompt" \
      --output-format stream-json \
      --verbose \
      --dangerously-skip-permissions \
      --disallowed-tools "AskUserQuestion" \
      --max-turns "$MAX_TURNS" \
      ${MODEL:+--model "$MODEL"} \
      ${EFFORT:+--effort "$EFFORT"} \
      ${ADD_DIR_ARGS[@]+"${ADD_DIR_ARGS[@]}"} \
      $sys_args ${sys_prompt:+"$sys_prompt"} \
      $extra_flags \
      2>/tmp/claude_stderr.log | stdbuf -oL tee "$stream_file" | while IFS= read -r line; do
        log_progress "$line"
      done
    # PIPESTATUS[0] is claude's own status, not tee's or the read loop's. The
    # caller runs run_claude inside $(...), so a variable cannot carry this out
    # of the subshell — hand it over through a file instead.
    local rc=${PIPESTATUS[0]}
    set -e
    echo "$rc" > /tmp/claude_exit_code
    if [ "$rc" -ne 0 ]; then
      echo "Claude Code exited with status $rc" >&2
      cat /tmp/claude_stderr.log >&2
    fi
    cat "$stream_file"
  }

  # Helper: extract fields from Claude stream-json output
  parse_claude_output() {
    local output="$1"
    CLAUDE_SESSION_ID=$(echo "$output" | grep '"subtype":"init"' | head -1 | jq -r '.session_id // empty' 2>/dev/null || true)
    local result_line
    result_line=$(echo "$output" | grep '"type":"result"' | tail -1)
    CLAUDE_RESULT=$(echo "$result_line" | jq -r '.result // empty' 2>/dev/null || true)
    CLAUDE_SUBTYPE=$(echo "$result_line" | jq -r '.subtype // empty' 2>/dev/null || true)
    CLAUDE_TURNS=$(echo "$result_line" | jq -r '.num_turns // empty' 2>/dev/null || true)
    # Branch on is_error, not subtype: is_error is stable across SDK surfaces, the
    # subtype enum is not (TS and Python references differ). subtype/terminal_reason/
    # errors are opaque strings for the message only.
    CLAUDE_IS_ERROR=$(echo "$result_line" | jq -r 'if .is_error == true then "true" else "false" end' 2>/dev/null || true)
    [ -n "$CLAUDE_IS_ERROR" ] || CLAUDE_IS_ERROR="false"
    CLAUDE_ERRORS=$(echo "$result_line" | jq -r '(.errors // []) | join("; ")' 2>/dev/null || true)
    CLAUDE_TERMINAL_REASON=$(echo "$result_line" | jq -r '.terminal_reason // empty' 2>/dev/null || true)
    # Last assistant message, kept separate from CLAUDE_RESULT: an error result has no
    # .result, so folding it in would make a truncated run look finished, skip the
    # retry loop, and call back "completed" on a mid-task message.
    CLAUDE_PARTIAL_TEXT=$(echo "$output" | grep '"type":"assistant"' | tail -1 | \
      jq -r '[.message.content[]? | select(.type == "text") | .text] | join("\n")' 2>/dev/null || true)
  }

  # (Re-)decide whether this attempt hit quota exhaustion. Quota is fleet-wide and
  # wall-clock-reset, so a hit can land on any attempt; called after every retry
  # parse so a mid-loop hit parks the node instead of burning the rest of the budget.
  # Every retry loop is guarded on [ -z "$QUOTA_RESET_AT" ], so setting it here exits them.
  #
  # Only sets, never clears: a decided park must not be revoked by a later parse.
  # Sets RESULT_STATUS="failed" to run the failure safety net (commit+push work
  # before eviction); the quota-park block below flips it to rate_limited.
  refresh_quota_reset_at() {
    [ -z "$QUOTA_RESET_AT" ] || return 0
    [ "${CLAUDE_IS_ERROR:-false}" = "true" ] || return 0
    QUOTA_RESET_AT=$(quota_reset_at "${CLAUDE_RESULT:-}") || QUOTA_RESET_AT=""
    [ -n "$QUOTA_RESET_AT" ] || return 0
    RESULT="${CLAUDE_RESULT:-$CLAUDE_PARTIAL_TEXT}"
    RESULT_STATUS="failed"
    ERROR_MESSAGE="Claude quota exhausted; resuming at $(date -u -d "@$QUOTA_RESET_AT" '+%H:%M UTC' 2>/dev/null || echo "the reset")"
    echo "QUOTA: $ERROR_MESSAGE"
  }

  # Defaults; validated config.json max_retries/max_turns override them.
  MAX_RETRIES="${MAX_RETRIES:-3}"
  MAX_TURNS="${MAX_TURNS:-100}"
  ATTEMPT=1
  CLAUDE_SESSION_ID=""
  CLAUDE_RESULT=""
  CLAUDE_PARTIAL_TEXT=""
  CLAUDE_IS_ERROR="false"

  # Pre-invocation diagnostic to distinguish auth failures from prompt/model issues.
  # Reports only presence and length of the OAuth token, never its value.
  echo "=== Pre-claude auth diagnostic ==="
  echo "HOME=$HOME  whoami=$(whoami)  id=$(id -u):$(id -g)"
  if [ -n "${CLAUDE_CODE_OAUTH_TOKEN:-}" ]; then
    echo "CLAUDE_CODE_OAUTH_TOKEN: present (length=${#CLAUDE_CODE_OAUTH_TOKEN})"
  else
    echo "CLAUDE_CODE_OAUTH_TOKEN: MISSING"
  fi
  echo "claude-version: $(claude --version 2>&1)"
  echo "=== End diagnostic ==="

  # Prepend Run ID to user message (keeps system prompt cache-stable across runs)
  FULL_PROMPT="Run ID: ${RUN_ID}

${PROMPT}"

  # --- Quota resume: restore a session parked by a previous iteration ---
  # Restoring the transcript alone is sufficient; the directory is keyed on the
  # claude cwd, which is always /workspace/repo.
  if [ -n "$RESUME_SESSION_ID" ] && [ -n "$RESUME_SESSION_PATH" ]; then
    # Claude Code's project-directory encoding: "/" → "-" across the absolute cwd.
    # Derived by substitution from the literal cwd so a change to either side is
    # visibly a change to the other. If the encoding changes, --resume silently finds
    # nothing; the guard below degrades to a fresh run rather than failing the node.
    RESTORE_CWD="/workspace/repo"
    RESTORE_DIR="$HOME/.claude/projects/${RESTORE_CWD//\//-}"
    RESTORE_TRANSCRIPT="$RESTORE_DIR/${RESUME_SESSION_ID}.jsonl"
    # mkdir failure (unwritable $HOME, disk pressure, a file at that path) must not
    # abort under set -e — folded into the fetch's guarded if so both fall back to a fresh run.
    #
    # mkdir's stderr is left unsuppressed on purpose: plain OS diagnostics with nothing
    # secret, exactly what an operator needs when a restore degrades to a fresh run.
    #
    # artifact's stderr is captured and excerpted for the same reason: it never prints
    # the signed URL, only the presign endpoint's error body or curl's -f message —
    # no signature. Without it a silently degrading restore loses its whole diagnosis.
    RESTORE_ERR_FILE="/tmp/session_restore.err"
    # Truncated up front: a failing mkdir short-circuits the && before the redirection
    # creates the file, and a stale file would attach the wrong cause to the warning.
    : > "$RESTORE_ERR_FILE" 2>/dev/null || true
    if mkdir -p "$RESTORE_DIR" && artifact get "$RESUME_SESSION_PATH" "$RESTORE_TRANSCRIPT" 2>"$RESTORE_ERR_FILE"; then
      echo "Restored parked session $RESUME_SESSION_ID -> $RESTORE_TRANSCRIPT"
      # Consume-once: overwrite with zero bytes. The parked transcript may hold
      # credentials no redaction caught, so it must not outlive the resume. Overwrite
      # not delete because the presign endpoint allows only GET/PUT.
      : > /tmp/empty_session
      CLEAR_ERR_FILE="/tmp/session_clear.err"
      if ! artifact put /tmp/empty_session "$RESUME_SESSION_PATH" 2>"$CLEAR_ERR_FILE"; then
        CLEAR_ERR=$(head -c 200 "$CLEAR_ERR_FILE" 2>/dev/null || true)
        echo "WARNING: could not clear the parked session object${CLEAR_ERR:+ ($CLEAR_ERR)}" >&2
      fi
      rm -f /tmp/empty_session "$CLEAR_ERR_FILE"
    else
      RESUME_SESSION_ID=""  # restore failed; fall back to a fresh run
      RESTORE_ERR=$(head -c 200 "$RESTORE_ERR_FILE" 2>/dev/null || true)
      echo "WARNING: could not restore the parked session; starting fresh${RESTORE_ERR:+ ($RESTORE_ERR)}" >&2
    fi
    rm -f "$RESTORE_ERR_FILE"
  fi

  # Attempt 1: resume the parked session if there is one, else run the prompt.
  echo "=== AI attempt $ATTEMPT/$MAX_RETRIES ==="
  if [ -n "$RESUME_SESSION_ID" ]; then
    RESUME_PROMPT="Your previous session was paused because the Claude usage quota was exhausted. Quota has now reset. Continue exactly where you left off and finish the task."
    CLAUDE_OUTPUT=$(run_claude "$RESUME_PROMPT" "--resume $RESUME_SESSION_ID")
  else
    CLAUDE_OUTPUT=$(run_claude "$FULL_PROMPT" "" "$SYSTEM_PROMPT")
  fi
  parse_claude_output "$CLAUDE_OUTPUT"
  echo "Attempt $ATTEMPT: subtype=$CLAUDE_SUBTYPE turns=$CLAUDE_TURNS result_length=${#CLAUDE_RESULT}"

  # Retry loop: resume session if no result
  while [ -z "$CLAUDE_RESULT" ] && [ $ATTEMPT -lt $MAX_RETRIES ] && [ -n "$CLAUDE_SESSION_ID" ]; do
    ATTEMPT=$((ATTEMPT + 1))
    echo "=== AI attempt $ATTEMPT/$MAX_RETRIES (resuming session $CLAUDE_SESSION_ID) ==="

    RETRY_PROMPT="Your previous session was interrupted before producing a final result. Either you were cut off mid-task and should continue where you left off, or you completed the work but forgot to write your result as a final message. Please complete the task and provide your result as your final response."

    CLAUDE_OUTPUT=$(run_claude "$RETRY_PROMPT" "--resume $CLAUDE_SESSION_ID")
    parse_claude_output "$CLAUDE_OUTPUT"
    echo "Attempt $ATTEMPT: subtype=$CLAUDE_SUBTYPE turns=$CLAUDE_TURNS result_length=${#CLAUDE_RESULT}"
  done

  CLAUDE_EXIT_CODE=$(cat /tmp/claude_exit_code 2>/dev/null || echo 0)

  QUOTA_RESET_AT=""
  if [ "$CLAUDE_IS_ERROR" = "true" ]; then
    # A quota hit is transient and org-wide: retrying gains nothing and wastes the
    # retry budget. Detect it before anything else consumes an attempt.
    refresh_quota_reset_at
    if [ -z "$QUOTA_RESET_AT" ]; then
      RESULT="${CLAUDE_RESULT:-$CLAUDE_PARTIAL_TEXT}"
      RESULT_STATUS="failed"
      ERROR_MESSAGE="Claude reported is_error after $ATTEMPT attempts (subtype=${CLAUDE_SUBTYPE:-unknown}${CLAUDE_TERMINAL_REASON:+, terminal_reason=$CLAUDE_TERMINAL_REASON})${CLAUDE_ERRORS:+: $CLAUDE_ERRORS}"
      echo "ERROR: $ERROR_MESSAGE"
    fi
  elif [ -n "$CLAUDE_RESULT" ]; then
    RESULT="$CLAUDE_RESULT"
    echo "AI result captured (${#RESULT} chars)"
    if [ "$CLAUDE_EXIT_CODE" -ne 0 ]; then
      echo "WARNING: result message reports success but claude exited $CLAUDE_EXIT_CODE" >&2
    fi
  else
    echo "WARNING: No .result after $ATTEMPT attempts (exit=$CLAUDE_EXIT_CODE)"
    RESULT="$CLAUDE_PARTIAL_TEXT"
    RESULT_STATUS="failed"
    ERROR_MESSAGE="Claude produced no result after $ATTEMPT attempts (last subtype=${CLAUDE_SUBTYPE:-none}, exit=$CLAUDE_EXIT_CODE)"
  fi

  # $ATTEMPT is shared across the main-retry, artifact, decision, and PR loops to cap
  # total retries at $MAX_RETRIES across all phases.
  # --- Artifact enforcement: verify required output files were produced ---
  OUTPUT_SPEC=$(jq -r '.output_spec // ""' "$CONFIG_FILE")
  if [ -n "$OUTPUT_SPEC" ] && [ "$OUTPUT_SPEC" != "{}" ] && [ -n "$CLAUDE_RESULT" ]; then
    REQUIRED_FILES=$(echo "$OUTPUT_SPEC" | jq -r '.files[]? | select(.required == true) | .name' 2>/dev/null || true)
    MISSING_FILES=""
    while IFS= read -r fname; do
      [ -z "$fname" ] && continue
      if [ ! -f "$WORKSPACE_OUT/$fname" ]; then
        MISSING_FILES="$MISSING_FILES $fname"
      fi
    done <<< "$REQUIRED_FILES"

    while [ -z "$QUOTA_RESET_AT" ] && [ $ATTEMPT -lt $MAX_RETRIES ] && [ -n "$MISSING_FILES" ] && [ -n "$CLAUDE_SESSION_ID" ]; do
      ATTEMPT=$((ATTEMPT + 1))
      echo "=== Artifact retry $ATTEMPT/$MAX_RETRIES — missing:$MISSING_FILES ==="
      ARTIFACT_RETRY_PROMPT="You completed your task but did not produce required output files:$MISSING_FILES. Write these files to /workspace/out/ before finishing."
      CLAUDE_OUTPUT=$(run_claude "$ARTIFACT_RETRY_PROMPT" "--resume $CLAUDE_SESSION_ID")
      parse_claude_output "$CLAUDE_OUTPUT"
      refresh_quota_reset_at
      MISSING_FILES=""
      while IFS= read -r fname; do
        [ -z "$fname" ] && continue
        if [ ! -f "$WORKSPACE_OUT/$fname" ]; then
          MISSING_FILES="$MISSING_FILES $fname"
        fi
      done <<< "$REQUIRED_FILES"
    done

    if [ -z "$QUOTA_RESET_AT" ] && [ -n "$MISSING_FILES" ]; then
      echo "ERROR: Required output files still missing after $ATTEMPT attempts:$MISSING_FILES"
      RESULT_STATUS="failed"
      ERROR_MESSAGE="Required output files not produced after $ATTEMPT attempts: $MISSING_FILES"
    fi
  fi

  # Decision verification: only for nodes that require a decision (conditional edges).
  if [ "$NEED_DECISION" = "true" ] && [ -n "$API_SERVER_URL" ] && [ -n "$CLAUDE_RESULT" ]; then
    DECISION=$(check-decision 2>/dev/null || echo "")
    if [ "$DECISION" = "(none)" ]; then DECISION=""; fi

    while [ -z "$QUOTA_RESET_AT" ] && [ $ATTEMPT -lt $MAX_RETRIES ] && [ -z "$DECISION" ] && [ -n "$CLAUDE_SESSION_ID" ]; do
      ATTEMPT=$((ATTEMPT + 1))
      echo "=== Decision retry $ATTEMPT/$MAX_RETRIES (resuming session $CLAUDE_SESSION_ID) ==="

      DECISION_RETRY_PROMPT="You completed your task but did not submit a decision. You MUST call report-result with your decision before finishing. Run: report-result <decision>"

      CLAUDE_OUTPUT=$(run_claude "$DECISION_RETRY_PROMPT" "--resume $CLAUDE_SESSION_ID")
      parse_claude_output "$CLAUDE_OUTPUT"
      refresh_quota_reset_at

      DECISION=$(check-decision 2>/dev/null || echo "")
      if [ "$DECISION" = "(none)" ]; then DECISION=""; fi
    done

    if [ -z "$QUOTA_RESET_AT" ] && [ -z "$DECISION" ]; then
      echo "ERROR: Node requires a decision but none was submitted after $ATTEMPT attempts"
      RESULT_STATUS="failed"
      ERROR_MESSAGE="Node requires a decision but agent did not call report-result after $ATTEMPT attempts"
    elif [ -n "$DECISION" ]; then
      echo "Decision verified: $DECISION"
    fi
  fi

  # PR verification (nodes that must register a PR for every repo they pushed).
  # check-prs has no single-value sentinel like DECISION, so branch on exit status:
  # 0 = nothing missing, non-zero = missing or check-prs failed. Shares the $ATTEMPT
  # budget; it is the last phase that can resume the session, so the escalation gate
  # sits below it (see that block's header).
  #
  # 2>&1 captures check-prs's stderr: its loud failure diagnostics go there, and
  # without this they would never reach the retry prompt or ERROR_MESSAGE, silently
  # defeating the fail-loudly intent.
  if [ "$NEED_PR" = "true" ] && [ -n "$API_SERVER_URL" ] && [ -n "$CLAUDE_RESULT" ]; then
    set +e
    PR_CHECK_OUTPUT=$(check-prs 2>&1)
    PR_CHECK_STATUS=$?
    set -e

    while [ -z "$QUOTA_RESET_AT" ] && [ $ATTEMPT -lt $MAX_RETRIES ] && [ "$PR_CHECK_STATUS" -ne 0 ] && [ -n "$CLAUDE_SESSION_ID" ]; do
      ATTEMPT=$((ATTEMPT + 1))
      echo "=== PR retry $ATTEMPT/$MAX_RETRIES (resuming session $CLAUDE_SESSION_ID) ==="

      PR_RETRY_PROMPT="PR verification reported a problem before this node can finish: ${PR_CHECK_OUTPUT}. If this lists repo(s) with no pull request registered, run: register-pr --repo-id <id> --pr-url <url> [--pr-number <n>] [--title <t>] [--repo-name <name>] for each one. If it instead describes a different failure (e.g. an unreachable API server or git remote), resolve that before finishing."

      CLAUDE_OUTPUT=$(run_claude "$PR_RETRY_PROMPT" "--resume $CLAUDE_SESSION_ID")
      parse_claude_output "$CLAUDE_OUTPUT"
      refresh_quota_reset_at

      set +e
      PR_CHECK_OUTPUT=$(check-prs 2>&1)
      PR_CHECK_STATUS=$?
      set -e
    done

    if [ -z "$QUOTA_RESET_AT" ] && [ "$PR_CHECK_STATUS" -ne 0 ]; then
      PR_FAILURE_MESSAGE="PR registration missing for ${PR_CHECK_OUTPUT:-unknown repo(s)} after $ATTEMPT/$MAX_RETRIES total resume attempts this node"
      echo "ERROR: $PR_FAILURE_MESSAGE"
      # Don't clobber an earlier phase's diagnosis — append, so the persisted
      # error_message names the more fundamental problem, not only the last checked
      # (e.g. a node with both NEED_DECISION and NEED_PR that exhausted $ATTEMPT earlier).
      if [ "$RESULT_STATUS" = "failed" ]; then
        ERROR_MESSAGE="${ERROR_MESSAGE}; additionally, ${PR_FAILURE_MESSAGE}"
      else
        RESULT_STATUS="failed"
        ERROR_MESSAGE="$PR_FAILURE_MESSAGE"
      fi
    elif [ "$PR_CHECK_STATUS" -eq 0 ]; then
      echo "PR verification passed"
    fi
  fi

  # --- Escalation artifact enforcement ---
  # Gated on SUPERVISOR_LABEL, not NEED_DECISION: NEED_DECISION is edge-based and
  # knows nothing about the implicit escalate decision, so a node with no conditional
  # edges can still be offered escalate whenever a Supervisor is configured.
  #
  # Deliberately the LAST verification phase: every phase above can resume the
  # session and mint another decision, so the decision is only final here. Placed
  # earlier, an agent that escalates during PR-retry walks past a gate that already
  # ran and the server rejects the completed status for a missing escalation.md. So
  # $DECISION is re-read (the block above's value can be stale). Still above the upload.
  #
  # Two exits: write escalation.md, or withdraw a now-stale escalation (the blocker
  # may have been resolved in a later phase). Both leave the node consistent.
  if [ -n "$SUPERVISOR_LABEL" ] && [ -n "$API_SERVER_URL" ] && [ -n "$CLAUDE_RESULT" ]; then
    DECISION=$(check-decision 2>/dev/null || echo "")
    if [ "$DECISION" = "(none)" ]; then DECISION=""; fi

    if [ "$DECISION" = "escalate" ]; then
      while [ -z "$QUOTA_RESET_AT" ] && [ $ATTEMPT -lt $MAX_RETRIES ] && [ ! -f "$WORKSPACE_OUT/escalation.md" ] && [ -n "$CLAUDE_SESSION_ID" ]; do
        ATTEMPT=$((ATTEMPT + 1))
        echo "=== Escalation retry $ATTEMPT/$MAX_RETRIES — escalation.md missing ==="
        ESCALATION_RETRY_PROMPT="You submitted the decision 'escalate' but did not write /workspace/out/escalation.md. Do one of two things before finishing. If you still need the Supervisor, write escalation.md now, using the front matter and section structure from your system prompt. If you resolved the blocker yourself after escalating, that escalation is stale — retract it by running: report-result --withdraw"
        CLAUDE_OUTPUT=$(run_claude "$ESCALATION_RETRY_PROMPT" "--resume $CLAUDE_SESSION_ID")
        parse_claude_output "$CLAUDE_OUTPUT"
        refresh_quota_reset_at

        # Withdrawal resolves this gate without a file, so re-read the decision rather than
        # loop on escalation.md alone — else the loop ignores the exit it just offered.
        DECISION=$(check-decision 2>/dev/null || echo "")
        if [ "$DECISION" = "(none)" ]; then DECISION=""; fi
        if [ "$DECISION" != "escalate" ]; then
          echo "Escalation withdrawn by the agent; nothing left to enforce"
          break
        fi
      done

      if [ -z "$QUOTA_RESET_AT" ] && [ "$DECISION" = "escalate" ] && [ ! -f "$WORKSPACE_OUT/escalation.md" ]; then
        ESCALATION_FAILURE_MESSAGE="Decision 'escalate' requires /workspace/out/escalation.md, which was not produced after $ATTEMPT attempts"
        echo "ERROR: $ESCALATION_FAILURE_MESSAGE"
        # Append, don't overwrite (as PR verification above): the persisted message should
        # name the more fundamental problem, not only the last checked.
        if [ "$RESULT_STATUS" = "failed" ]; then
          ERROR_MESSAGE="${ERROR_MESSAGE}; additionally, ${ESCALATION_FAILURE_MESSAGE}"
        else
          RESULT_STATUS="failed"
          ERROR_MESSAGE="$ESCALATION_FAILURE_MESSAGE"
        fi
      fi
    fi
  fi

fi

# --- Failure safety net: preserve in-progress work ---
# A failed node is retried as a brand-new pod and clone, so work only in this pod's
# filesystem is lost. Commit and push each run branch so the retry inherits it, and
# name what was pushed: an errored run has no .result and often no last-message text,
# so the callback otherwise reports a failure with no content for the retry to use.
#
# Best-effort by construction: the failure that must reach the caller is the
# original one, so no git command here may abort the pod or alter RESULT_STATUS.

# Commits and pushes one clone's run branch. Emits one summary line per repo on
# stdout (it becomes part of the reported result); diagnostics go to stderr.
preserve_repo_work() {
  local repo_name="$1" repo_path="$2" repo_branch="$3"
  (
    set +e
    # A node that was given no run branch is still on the repo's default
    # branch, which is never ours to commit to.
    [ -n "$repo_branch" ] || exit 0
    cd "$repo_path" 2>/dev/null || exit 0
    git rev-parse --git-dir >/dev/null 2>&1 || exit 0

    local current_branch
    current_branch=$(git rev-parse --abbrev-ref HEAD 2>/dev/null)
    if [ "$current_branch" != "$repo_branch" ]; then
      echo "WARNING: $repo_name is on '$current_branch', not run branch '$repo_branch' — leaving it untouched" >&2
      exit 0
    fi

    if [ -n "$(git status --porcelain 2>/dev/null)" ]; then
      git add -A >/dev/null 2>&1
      git commit -q \
        -m "WIP: agent work preserved after node failure" \
        -m "Committed by the agent entrypoint so a retry starts from the work already done. Run $RUN_ID, node execution $NODE_EXECUTION_ID." \
        >/dev/null 2>&1 \
        || echo "WARNING: could not commit pending changes in $repo_name" >&2
    fi

    local head_sha remote_sha
    head_sha=$(git rev-parse --short HEAD 2>/dev/null)
    [ -n "$head_sha" ] || exit 0
    remote_sha=$(git rev-parse --short --verify --quiet "refs/remotes/origin/$repo_branch" 2>/dev/null)
    if [ "$head_sha" = "$remote_sha" ]; then
      echo "- $repo_name: nothing new to push ($repo_branch @ $head_sha)"
      exit 0
    fi

    if git push origin "HEAD:$repo_branch" >/dev/null 2>&1; then
      echo "- $repo_name: pushed $repo_branch @ $head_sha"
    else
      echo "WARNING: could not push $repo_branch for $repo_name; the work stays in this pod" >&2
      echo "- $repo_name: $repo_branch @ $head_sha committed locally, push failed"
    fi
  )
}

# The same repo set Step 3 cloned, iterated the same way: every entry of `repos`
# in multi-repo mode, or the single clone at /workspace/repo.
preserve_work() {
  if [ -n "$REPOS_JSON" ] && [ "$REPOS_JSON" != "null" ]; then
    while IFS= read -r repo; do
      preserve_repo_work \
        "$(echo "$repo" | jq -r '.name')" \
        "$(echo "$repo" | jq -r '.local_path')" \
        "$(echo "$repo" | jq -r '.working_branch // empty')"
    done < <(echo "$REPOS_JSON" | jq -c '.[]')
  elif [ -n "$REPO_URL" ]; then
    preserve_repo_work "$(basename "$REPO_URL" .git)" "/workspace/repo" "$WORKING_BRANCH"
  fi
}

if [ "$RESULT_STATUS" = "failed" ]; then
  echo "=== Preserving in-progress work before reporting failure ==="
  PRESERVED_WORK=$(preserve_work) || true
  [ -n "$PRESERVED_WORK" ] || PRESERVED_WORK="- (no repository work to preserve)"
  echo "$PRESERVED_WORK"
  RESULT="Node failed: ${ERROR_MESSAGE}

Repository state preserved for the retry:
${PRESERVED_WORK}${RESULT:+

Last agent output before the failure:
${RESULT}}"
fi

# --- Quota park: carry the Claude session across the pod's eviction ---
# The transcript is the session's entire portable state — restoring just that file
# into a fresh pod is enough for claude --resume; nothing else in ~/.claude is needed.
SESSION_ARTIFACT_PATH=""
if [ -n "$QUOTA_RESET_AT" ]; then
  RESULT_STATUS="rate_limited"

  # Discover the directory rather than rebuild it from cwd, so an encoding change
  # can't silently park a sessionless run. The trailing || true is load-bearing: with
  # no session there may be no projects dir, and ls failing under pipefail would abort
  # the pod before the callback — the outcome this whole block exists to avoid.
  CLAUDE_PROJECT_DIR=$(ls -d "$HOME"/.claude/projects/*/ 2>/dev/null | head -1 || true)
  # More than one project dir means head -1 chooses arbitrarily, so the parked
  # transcript may belong to a different session — an obscure resume failure a pod
  # later. Name what was found while it is still a visible anomaly.
  CLAUDE_PROJECT_DIR_COUNT=$(ls -d "$HOME"/.claude/projects/*/ 2>/dev/null | wc -l | tr -d '[:space:]' || true)
  if [ "${CLAUDE_PROJECT_DIR_COUNT:-0}" -gt 1 ]; then
    CLAUDE_PROJECT_DIRS=$(ls -d "$HOME"/.claude/projects/*/ 2>/dev/null | tr '\n' ' ' || true)
    echo "WARNING: $CLAUDE_PROJECT_DIR_COUNT Claude project directories found ($CLAUDE_PROJECT_DIRS); parking the session under ${CLAUDE_PROJECT_DIR:-(none)}" >&2
  fi
  TRANSCRIPT_SRC="${CLAUDE_PROJECT_DIR%/}/${CLAUDE_SESSION_ID}.jsonl"
  TRANSCRIPT_REDACTED="/tmp/session_redacted.jsonl"

  # The session id comes from the stream's init event, so it can be empty (a quota
  # hit before the first event). A park with no session is a supported degraded
  # outcome — the next iteration starts fresh; a missing id costs the resume, not the
  # park. Handled like a failed upload below: session_id and path both null.
  if [ -z "$CLAUDE_SESSION_ID" ]; then
    echo "WARNING: the quota hit carries no Claude session id; parking without one, the retry will start fresh" >&2
  elif [ -f "$TRANSCRIPT_SRC" ]; then
    # Capture stderr to a private file so a token/upload failure leaves a cause behind,
    # but only excerpt a short truncated prefix: these are the fetch/presign endpoints'
    # own diagnostics (HTTP status, body), never a token or transcript value.
    TOKEN_FETCH_ERR_FILE="/tmp/session_token_fetch.err"
    if ! GITHUB_TOKEN_FOR_REDACTION=$(fetch-github-token 2>"$TOKEN_FETCH_ERR_FILE"); then
      GITHUB_TOKEN_FOR_REDACTION=""
      TOKEN_FETCH_ERR=$(head -c 200 "$TOKEN_FETCH_ERR_FILE" 2>/dev/null || true)
      echo "WARNING: could not fetch a GitHub token for redaction, continuing without it${TOKEN_FETCH_ERR:+ ($TOKEN_FETCH_ERR)}" >&2
    fi
    rm -f "$TOKEN_FETCH_ERR_FILE"
    # Deliberately NOT exported: redact_transcript is sourced into this shell and reads
    # the plain variable. Exporting would leak the token to every child (artifact, curl).
    if redact_transcript "$TRANSCRIPT_SRC" "$TRANSCRIPT_REDACTED"; then
      SESSION_ARTIFACT_PATH="${OUTPUT_PATH%out/}session/${CLAUDE_SESSION_ID}.jsonl"
      UPLOAD_ERR_FILE="/tmp/session_upload.err"
      if artifact put "$TRANSCRIPT_REDACTED" "$SESSION_ARTIFACT_PATH" 2>"$UPLOAD_ERR_FILE"; then
        echo "QUOTA: parked session $CLAUDE_SESSION_ID at $SESSION_ARTIFACT_PATH"
      else
        SESSION_ARTIFACT_PATH=""  # upload failed; park without a session
        UPLOAD_ERR=$(head -c 200 "$UPLOAD_ERR_FILE" 2>/dev/null || true)
        echo "WARNING: could not upload the session transcript; the retry will start fresh${UPLOAD_ERR:+ ($UPLOAD_ERR)}" >&2
      fi
      rm -f "$UPLOAD_ERR_FILE"
    fi
    unset GITHUB_TOKEN_FOR_REDACTION
    rm -f "$TRANSCRIPT_REDACTED"
  else
    echo "WARNING: no transcript at $TRANSCRIPT_SRC; the retry will start fresh" >&2
  fi
fi

# --- Step 5: Push output artifacts via presigned URLs ---
ARTIFACT_REFS="{}"
if [ -d "$WORKSPACE_OUT" ] && [ "$(ls -A "$WORKSPACE_OUT" 2>/dev/null)" ]; then
  (cd "$WORKSPACE_OUT" && find . -type f | while read -r file; do
    rel_path="${file#./}"
    artifact put "$WORKSPACE_OUT/$rel_path" "${OUTPUT_PATH}${rel_path}"
  done)
  ARTIFACT_REFS=$(jq -n --arg path "$OUTPUT_PATH" '{"output": $path}')
fi

# --- Step 6: POST callback to orchestrator ---
# resume_at is hard-required on a rate_limited callback (400 without it) and
# send-callback has no retry, so convert first and check: if date can't render the
# epoch, fail the node instead. A failed node is retried; a silently dead pod is not.
RESUME_AT_RFC3339=""
if [ -n "$QUOTA_RESET_AT" ]; then
  RESUME_AT_RFC3339=$(date -u -d "@$QUOTA_RESET_AT" '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null || true)
  if [ -z "$RESUME_AT_RFC3339" ]; then
    echo "WARNING: could not render the quota reset instant ($QUOTA_RESET_AT) as RFC3339; failing the node instead of parking" >&2
    RESULT_STATUS="failed"
    ERROR_MESSAGE="${ERROR_MESSAGE:-Claude quota exhausted}; the reset instant could not be encoded for the callback, so this node fails rather than parking"
    # A failed node is retried from scratch, so a session reference would point at an
    # object nothing reads. Clearing the path clears the id too (session_id below is
    # ${SESSION_ARTIFACT_PATH:+$CLAUDE_SESSION_ID}).
    SESSION_ARTIFACT_PATH=""
  fi
fi

CALLBACK_BODY=$(jq -n \
  --arg id "$NODE_EXECUTION_ID" \
  --arg run_id "$RUN_ID" \
  --arg status "$RESULT_STATUS" \
  --arg result "$RESULT" \
  --argjson artifacts "$ARTIFACT_REFS" \
  --arg error "$ERROR_MESSAGE" \
  --arg resume_at "$RESUME_AT_RFC3339" \
  --arg session_id "${SESSION_ARTIFACT_PATH:+$CLAUDE_SESSION_ID}" \
  --arg session_path "$SESSION_ARTIFACT_PATH" \
  '{
    node_execution_id: $id,
    run_id: $run_id,
    status: $status,
    result: $result,
    artifact_refs: $artifacts,
    error_message: (if $error == "" then null else $error end),
    resume_at: (if $resume_at == "" then null else $resume_at end),
    session_id: (if $session_id == "" then null else $session_id end),
    session_artifact_path: (if $session_path == "" then null else $session_path end)
  }')

send-callback "$CALLBACK_BODY"

# --- Diagnostic logs ---
echo "=== Agent Summary ==="
echo "Status: $RESULT_STATUS | Result length: ${#RESULT} | Artifacts: $ARTIFACT_REFS"
echo "=== Claude stderr ==="
cat /tmp/claude_stderr.log 2>/dev/null || echo "(none)"
echo "=== Claude JSON output (first 1000 chars) ==="
echo "${CLAUDE_OUTPUT:0:1000}"
echo "=== Output dir contents ==="
ls -la "$WORKSPACE_OUT" 2>/dev/null || echo "(empty)"
echo "=== End diagnostics ==="
