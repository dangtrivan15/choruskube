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

# JOB_SECRET is injected via K8s Secret as an environment variable
if [ -z "${JOB_SECRET:-}" ]; then
  echo "ERROR: JOB_SECRET environment variable not set"
  exit 1
fi

# --- BuildKit builder setup (DinD + cache registry only) ---
# Embedded BuildKit (dind 29 + containerd-snapshotter mode) does NOT honor
# dockerd's daemon.json `insecure-registries` for its cache import/export
# pipeline, and `/etc/containerd/certs.d/<host>/hosts.toml` is also unreliable
# for that pipeline. The result: cache fetches against our plain-HTTP
# in-cluster `cache-registry` default to HTTPS, fail the TLS handshake, and
# wedge the buildx CLI on a Solve gRPC call that never returns.
#
# Switch to a `docker-container` driver builder whose `buildkitd.toml` we own.
# That config IS the canonical one buildkitd reads, so HTTP trust is honored.
# The buildkitd container runs INSIDE dind, so it talks to the cache-registry
# over the same in-cluster network the embedded BuildKit was using.
#
# Side effect: the new buildkitd has its own registry config and does NOT
# inherit dockerd's daemon.json mirrors, so we replicate the docker.io
# pull-through mirror here too — otherwise build-time base-image pulls bypass
# the mirror and risk Docker Hub rate limiting.
if [ -n "${BUILD_CACHE_REGISTRY:-}" ] && [ -n "${DOCKER_HOST:-}" ]; then
  # The executor injects the upstream Docker registry mirror host as
  # REGISTRY_MIRROR_HOST. When set, trust it over plain HTTP and use it as the
  # docker.io pull-through mirror so base-image pulls go through the mirror
  # instead of hitting Docker Hub directly; when unset, BuildKit pulls from
  # docker.io directly (no mirror).
  MIRROR_HOST="${REGISTRY_MIRROR_HOST:-}"

  cat > /tmp/buildkitd.toml <<EOF
debug = false

[registry."${BUILD_CACHE_REGISTRY}"]
  http = true
EOF

  if [ -n "${MIRROR_HOST}" ]; then
    cat >> /tmp/buildkitd.toml <<EOF

[registry."${MIRROR_HOST}"]
  http = true

[registry."docker.io"]
  mirrors = ["${MIRROR_HOST}"]
EOF
  fi

  # The dind sidecar starts before this container but dockerd may still be
  # initializing TLS certs. Poll briefly before creating the builder.
  for _ in $(seq 1 30); do
    if docker version >/dev/null 2>&1; then break; fi
    sleep 1
  done

  if docker buildx create --use --bootstrap \
      --name choruskube-builder \
      --driver docker-container \
      --buildkitd-config /tmp/buildkitd.toml >/dev/null 2>&1; then
    echo "BuildKit builder ready: choruskube-builder (HTTP trust: ${BUILD_CACHE_REGISTRY}${MIRROR_HOST:+, ${MIRROR_HOST}})"
  else
    # Don't fail the agent — e2e-up.sh's bake invocation has a no-cache
    # fallback that still produces a working build, just slower.
    echo "WARNING: docker-container builder bootstrap failed; falling back to embedded BuildKit (cache registry will not work)"
  fi
fi

# Claude credentials are delivered via the CLAUDE_CODE_OAUTH_TOKEN env var (long-lived
# OAuth token from `claude setup-token`). The Claude CLI reads it natively as a bearer
# token — no credentials file to symlink, no hostpath to mount. For script-executor
# nodes the API server omits the token from the Secret on purpose, so this check
# only fails AI/human/both executors.
if [ "$EXECUTOR_TYPE" != "script" ] && [ -z "${CLAUDE_CODE_OAUTH_TOKEN:-}" ]; then
  echo "ERROR: CLAUDE_CODE_OAUTH_TOKEN env var is empty for executor_type=$EXECUTOR_TYPE"
  echo "       Generate one with \`claude setup-token\` and inject it into the api-server pod."
  exit 1
fi

# --- Step 1: Pull input artifacts via presigned URLs ---
mkdir -p "$WORKSPACE_IN"
INPUT_KEYS=$(jq -r '.input_artifacts // {} | keys[]' "$CONFIG_FILE" 2>/dev/null || true)
for key in $INPUT_KEYS; do
  MINIO_PATH=$(jq -r ".input_artifacts.\"$key\"" "$CONFIG_FILE")
  echo "Pulling input artifact: $key from $MINIO_PATH"
  artifact get "$MINIO_PATH" "${WORKSPACE_IN}/${key}"
done

# Pull run log (may not exist for first iteration, that's OK)
if [ -n "$RUN_LOG_PATH" ]; then
  artifact get "$RUN_LOG_PATH" "${WORKSPACE_IN}/run_log.md" 2>/dev/null || true
fi

# --- Step 2: Configure git credentials (before clone, so private repos work) ---
# Identity is required by `git rebase` (which rewrites commits and stamps
# them with the current committer) and by any in-repo commits the agent
# script makes. Set it unconditionally — it's harmless when unused, and
# without it the rebase-on-clone step (Step 3) silently falls back.
git config --global user.email "agent@choruskube.local"
git config --global user.name "ChorusKube Agent"

if [ -n "$GITHUB_TOKEN_URL" ]; then
  git config --global credential.helper \
    '!f() { echo "protocol=https"; echo "host=github.com"; echo "username=x-access-token"; echo "password=$(fetch-github-token)"; }; f'
  # Configure gh CLI
  TOKEN=$(fetch-github-token 2>/dev/null || true)
  if [ -n "$TOKEN" ] && [ "$TOKEN" != "null" ]; then
    echo "$TOKEN" | gh auth login --with-token 2>/dev/null || true
  fi
  echo "Git credentials configured"
fi

# --- Step 3: Clone repo(s) if configured ---
REPOS_JSON=$(jq -r '.repos // empty' /workspace/config.json)

if [ -n "$REPOS_JSON" ] && [ "$REPOS_JSON" != "null" ]; then
  # Multi-repo mode: clone each repo to /workspace/repo/{name}/
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
        # Best-effort rebase onto current origin/main so safety-net commits
        # (e.g. script timeouts) reach long-lived run branches cut from an
        # older base. Depth=200 covers typical branch lifetimes; on conflict,
        # abort and continue on the stale base rather than breaking the run.
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

  # Add multi-repo workspace description to system prompt. Repos are peers —
  # there is no primary. Each repo's CLAUDE.md, skills and subagents are loaded
  # by Claude Code itself from the --add-dir set built below.
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
export SYSTEM_PROMPT

# --- Dependency proxy (Gradle init script) ---
# When DEP_PROXY_BASE is injected, write the Gradle init script that routes Maven
# resolution through the in-cluster proxy. Runs AFTER the clone so the repo-shipped
# helper exists. GOPROXY/GOSUMDB/npm_config_registry are already injected as env by
# the executor; only the Gradle init script needs the helper. The helper ships at
# /workspace/repo/scripts/lib/dep-proxy.sh (single-repo) or a child dir (multi-repo);
# source the first match. No-op when DEP_PROXY_BASE is unset or no repo ships the
# helper — graceful degradation.
if [ -n "${DEP_PROXY_BASE:-}" ]; then
  for _dp in /workspace/repo/scripts/lib/dep-proxy.sh /workspace/repo/*/scripts/lib/dep-proxy.sh; do
    if [ -f "$_dp" ]; then
      source "$_dp"
      apply_dep_proxy "${GRADLE_USER_HOME:-$HOME/.gradle}"
      break
    fi
  done

  # npm bakes the registry it fetched from into package-lock.json `resolved`
  # URLs, so any dependency the agent adds records the proxy host and commits it.
  # A global pre-commit hook normalizes staged lockfiles back to the canonical
  # public registry (see normalize-lockfiles for why that keeps proxy caching).
  # core.hooksPath is global so it covers every clone in a multi-repo run; a repo
  # that sets its own hooksPath locally (husky) still wins, as local config beats
  # global.
  HOOKS_DIR="$HOME/.choruskube-git-hooks"
  mkdir -p "$HOOKS_DIR"
  cat > "$HOOKS_DIR/pre-commit" <<'HOOK'
#!/bin/bash
set -euo pipefail
# A read loop rather than `mapfile`, which needs bash 4+ and so would not run on
# a stock macOS shell if anyone exercises this hook outside the container.
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
# Claude Code discovers CLAUDE.md, .claude/skills/ and .claude/agents/ from its
# set of working directories: the cwd plus every --add-dir. It does NOT descend
# into subdirectories of the cwd on its own, so each repo must be named
# explicitly even though they all sit under /workspace/repo.
# CLAUDE_CODE_ADDITIONAL_DIRECTORIES_CLAUDE_MD (set in the Dockerfile) is what
# makes CLAUDE.md load from the added dirs rather than access alone.
ADD_DIR_ARGS=()
if [ -n "$REPOS_JSON" ] && [ "$REPOS_JSON" != "null" ]; then
  for repo_dir in /workspace/repo/*/; do
    [ -d "$repo_dir" ] && ADD_DIR_ARGS+=(--add-dir "$repo_dir")
  done
elif [ -d /workspace/repo ]; then
  ADD_DIR_ARGS+=(--add-dir /workspace/repo)
fi
echo "Workspace roots: ${ADD_DIR_ARGS[*]:-none}"

# --- Live Chat mode: delegate to dedicated loop (after workspace setup) ---
MODE=$(jq -r '.mode // empty' "$CONFIG_FILE")
if [ "$MODE" = "live_chat" ]; then
  SESSION_ID=$(jq -r '.session_id // empty' "$CONFIG_FILE")
  if [ -z "$SESSION_ID" ]; then
    echo "ERROR: session_id missing from config for live_chat mode"
    exit 1
  fi
  export SESSION_ID RUN_ID NODE_EXECUTION_ID API_SERVER_URL JOB_SECRET
  exec live-chat-loop
fi

# Derive heartbeat URL from callback URL (sibling endpoint on same server)
export HEARTBEAT_URL="${CALLBACK_URL%/callback}/heartbeat"

# Shared stream file that run_claude writes to and send-heartbeat reads from.
# Liveness is tied to the number of stream events (tool_use, tool_result, assistant,
# etc.) — send-heartbeat only POSTs when this count changes since the last tick,
# so a stuck Claude session stops heartbeating and Temporal's heartbeat timeout
# fires instead of silently running out the full activity deadline.
export CLAUDE_STREAM_FILE="/tmp/claude_stream_current.jsonl"

# --- Heartbeat loop (background) ---
# Reports liveness to Temporal via orchestrator every 60s, but only if the
# Claude stream has advanced. Killed on exit so it doesn't outlive the agent.
heartbeat_loop() {
    while true; do
        sleep 60
        send-heartbeat  # errors suppressed inside the script
    done
}
heartbeat_loop &
HEARTBEAT_PID=$!

# Ensure heartbeat loop is killed on any exit (success, failure, signal)
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

# --- Compose decisions suffix into the system prompt (AI nodes only) ---
# Query the api-server for the set of decisions this node may submit via
# report-result, then append a frame-setting suffix to SYSTEM_PROMPT so the
# agent knows up-front what conclusions are available. Knowing the set primes
# the agent's reasoning mode (e.g. presence/absence of alternative_proposal
# determines whether architectural critique is in scope).
#
# Failure to fetch is fatal for AI decision-emitting nodes: starting an agent
# that doesn't know its allowed decisions risks invented values that 400 at
# submit time, wasting a full run. Surface as a normal failure via callback.
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

Knowing this set up-front frames how to approach the work. The absence of a decision (e.g. \`need_human_decision:alternative_proposal\`) means this node is not authorized for that mode of conclusion — focus on the modes that are listed."
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

if [ "$SKIP_AGENT_INVOCATION" = "true" ]; then
  echo "Skipping agent invocation: $ERROR_MESSAGE"
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

  # Full output goes to artifact; result is a short reference to avoid ARG_MAX issues
  mkdir -p /workspace/out
  echo "$SCRIPT_OUTPUT" > /workspace/out/test_output.txt
  RESULT="Read test_output.txt for full script output"
else
  # AI path — run Claude Code with retry on missing result

  # Set up report-result URL for the helper script
  if [ -n "$API_SERVER_URL" ]; then
    export REPORT_RESULT_URL="$API_SERVER_URL/internal/runs/$RUN_ID/node-executions/$NODE_EXECUTION_ID/decision"
  fi

  # /workspace/repo is the repo itself in single-repo mode, and the parent of the
  # clones in multi-repo mode. Either way it is the root of the workspace; the
  # repos themselves are declared via ADD_DIR_ARGS.
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

  # Helper: log tool_use events from a stream-json line.
  # Writes to /dev/stderr directly to bypass subshell fd inheritance issues
  # (run_claude is invoked inside $(...), which captures stdout).
  # Redirection order matters: /dev/stderr is a symlink to /proc/self/fd/2, so
  # `2>/dev/null` before `>/dev/stderr` resolves the target to /dev/null and
  # discards every progress line. Point stdout at fd 2 first, silence jq second.
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
    # Stream JSON output: each line is a JSON event.
    # tee saves to file (for parse_claude_output), while the loop logs progress to stderr.
    # stdbuf -oL forces line-buffered stdout so stream-json events flow
    # through the tee|while pipeline in real-time instead of block-buffering.
    # The turn cap is a CLI flag, not a settings.json key: settings.json has no
    # maxTurns setting, and user settings files are validated strictly — one
    # unrecognized key rejects the whole file. Hitting the cap exits non-zero
    # and yields a result message with is_error set, which the caller gates on.
    set +e
    stdbuf -oL claude -p "$prompt" \
      --output-format stream-json \
      --verbose \
      --dangerously-skip-permissions \
      --disallowed-tools "AskUserQuestion" \
      --max-turns "$MAX_TURNS" \
      ${MODEL:+--model "$MODEL"} \
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
    # Session ID from the init event
    CLAUDE_SESSION_ID=$(echo "$output" | grep '"subtype":"init"' | head -1 | jq -r '.session_id // empty' 2>/dev/null || true)
    # Result and metadata from the result event
    local result_line
    result_line=$(echo "$output" | grep '"type":"result"' | tail -1)
    CLAUDE_RESULT=$(echo "$result_line" | jq -r '.result // empty' 2>/dev/null || true)
    CLAUDE_SUBTYPE=$(echo "$result_line" | jq -r '.subtype // empty' 2>/dev/null || true)
    CLAUDE_TURNS=$(echo "$result_line" | jq -r '.num_turns // empty' 2>/dev/null || true)
    # is_error is the failure flag to branch on. It is documented on the result
    # message across SDK surfaces, whereas the subtype enum is not stable: the
    # published TypeScript and Python references list different members. Treat
    # subtype/terminal_reason/errors as opaque strings for the message only.
    CLAUDE_IS_ERROR=$(echo "$result_line" | jq -r 'if .is_error == true then "true" else "false" end' 2>/dev/null || true)
    [ -n "$CLAUDE_IS_ERROR" ] || CLAUDE_IS_ERROR="false"
    CLAUDE_ERRORS=$(echo "$result_line" | jq -r '(.errors // []) | join("; ")' 2>/dev/null || true)
    CLAUDE_TERMINAL_REASON=$(echo "$result_line" | jq -r '.terminal_reason // empty' 2>/dev/null || true)
    # Text of the last assistant message, held separately from CLAUDE_RESULT.
    # An error result carries no .result field at all, so folding this into
    # CLAUDE_RESULT would make a truncated run look finished and skip the retry
    # loop below — the run would call back "completed" on a mid-task message.
    CLAUDE_PARTIAL_TEXT=$(echo "$output" | grep '"type":"assistant"' | tail -1 | \
      jq -r '[.message.content[]? | select(.type == "text") | .text] | join("\n")' 2>/dev/null || true)
  }

  MAX_RETRIES=3
  MAX_TURNS=100
  ATTEMPT=1
  CLAUDE_SESSION_ID=""
  CLAUDE_RESULT=""
  CLAUDE_PARTIAL_TEXT=""
  CLAUDE_IS_ERROR="false"

  # Pre-invocation diagnostic: surface Claude auth state so auth failures can be
  # distinguished from prompt/model issues. Safe: only reports presence and length
  # of the OAuth token, never its value.
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

  # Attempt 1: run the original prompt
  echo "=== AI attempt $ATTEMPT/$MAX_RETRIES ==="
  CLAUDE_OUTPUT=$(run_claude "$FULL_PROMPT" "" "$SYSTEM_PROMPT")
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

  if [ "$CLAUDE_IS_ERROR" = "true" ]; then
    # The run ended in a documented failure (turn cap, budget cap, execution
    # error, ...). Carry whatever text it produced so the work isn't discarded,
    # but never report it as completed — that is what let a truncated run look
    # like a finished one downstream.
    RESULT="${CLAUDE_RESULT:-$CLAUDE_PARTIAL_TEXT}"
    RESULT_STATUS="failed"
    ERROR_MESSAGE="Claude reported is_error after $ATTEMPT attempts (subtype=${CLAUDE_SUBTYPE:-unknown}${CLAUDE_TERMINAL_REASON:+, terminal_reason=$CLAUDE_TERMINAL_REASON})${CLAUDE_ERRORS:+: $CLAUDE_ERRORS}"
    echo "ERROR: $ERROR_MESSAGE"
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

  # NOTE: $ATTEMPT is shared across the main retry, artifact enforcement, and decision
  # verification loops to cap total retries at $MAX_RETRIES across all phases.
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

    while [ -n "$MISSING_FILES" ] && [ $ATTEMPT -lt $MAX_RETRIES ] && [ -n "$CLAUDE_SESSION_ID" ]; do
      ATTEMPT=$((ATTEMPT + 1))
      echo "=== Artifact retry $ATTEMPT/$MAX_RETRIES — missing:$MISSING_FILES ==="
      ARTIFACT_RETRY_PROMPT="You completed your task but did not produce required output files:$MISSING_FILES. Write these files to /workspace/out/ before finishing."
      CLAUDE_OUTPUT=$(run_claude "$ARTIFACT_RETRY_PROMPT" "--resume $CLAUDE_SESSION_ID")
      parse_claude_output "$CLAUDE_OUTPUT"
      MISSING_FILES=""
      while IFS= read -r fname; do
        [ -z "$fname" ] && continue
        if [ ! -f "$WORKSPACE_OUT/$fname" ]; then
          MISSING_FILES="$MISSING_FILES $fname"
        fi
      done <<< "$REQUIRED_FILES"
    done

    if [ -n "$MISSING_FILES" ]; then
      echo "ERROR: Required output files still missing after $ATTEMPT attempts:$MISSING_FILES"
      RESULT_STATUS="failed"
      ERROR_MESSAGE="Required output files not produced after $ATTEMPT attempts: $MISSING_FILES"
    fi
  fi

  # Decision verification: only for nodes that require a decision (conditional edges).
  if [ "$NEED_DECISION" = "true" ] && [ -n "$API_SERVER_URL" ] && [ -n "$CLAUDE_RESULT" ]; then
    DECISION=$(check-decision 2>/dev/null || echo "")
    if [ "$DECISION" = "(none)" ]; then DECISION=""; fi

    while [ -z "$DECISION" ] && [ $ATTEMPT -lt $MAX_RETRIES ] && [ -n "$CLAUDE_SESSION_ID" ]; do
      ATTEMPT=$((ATTEMPT + 1))
      echo "=== Decision retry $ATTEMPT/$MAX_RETRIES (resuming session $CLAUDE_SESSION_ID) ==="

      DECISION_RETRY_PROMPT="You completed your task but did not submit a decision. You MUST call report-result with your decision before finishing. Run: report-result <decision>"

      CLAUDE_OUTPUT=$(run_claude "$DECISION_RETRY_PROMPT" "--resume $CLAUDE_SESSION_ID")
      parse_claude_output "$CLAUDE_OUTPUT"

      DECISION=$(check-decision 2>/dev/null || echo "")
      if [ "$DECISION" = "(none)" ]; then DECISION=""; fi
    done

    if [ -z "$DECISION" ]; then
      echo "ERROR: Node requires a decision but none was submitted after $ATTEMPT attempts"
      RESULT_STATUS="failed"
      ERROR_MESSAGE="Node requires a decision but agent did not call report-result after $ATTEMPT attempts"
    else
      echo "Decision verified: $DECISION"
    fi
  fi
fi

# --- Step 5: Push output artifacts via presigned URLs ---
ARTIFACT_REFS="{}"
if [ -d "$WORKSPACE_OUT" ] && [ "$(ls -A "$WORKSPACE_OUT" 2>/dev/null)" ]; then
  # Upload each file individually, preserving relative paths
  (cd "$WORKSPACE_OUT" && find . -type f | while read -r file; do
    rel_path="${file#./}"
    artifact put "$WORKSPACE_OUT/$rel_path" "${OUTPUT_PATH}${rel_path}"
  done)
  ARTIFACT_REFS=$(jq -n --arg path "$OUTPUT_PATH" '{"output": $path}')
fi

# --- Step 6: POST callback to orchestrator ---
CALLBACK_BODY=$(jq -n \
  --arg id "$NODE_EXECUTION_ID" \
  --arg run_id "$RUN_ID" \
  --arg status "$RESULT_STATUS" \
  --arg result "$RESULT" \
  --argjson artifacts "$ARTIFACT_REFS" \
  --arg error "$ERROR_MESSAGE" \
  '{
    node_execution_id: $id,
    run_id: $run_id,
    status: $status,
    result: $result,
    artifact_refs: $artifacts,
    error_message: (if $error == "" then null else $error end)
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
