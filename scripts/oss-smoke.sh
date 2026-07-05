#!/usr/bin/env bash
# scripts/oss-smoke.sh — Smoke test for the OSS ChorusKube docker-compose stack.
#
# Proves the PUBLISHED images run under the canonical OSS wiring, with only a
# throwaway dummy Claude token (no real token, no real Claude call, no PR):
#   1. health-check the booted stack,
#   2. drive ONE seeded feature-dev run (no auth — AUTH_ENABLED=false),
#   3. assert the api-server (EXECUTOR_TYPE=docker) pulls & spawns an agent
#      container FROM a published image (registry.choruskube.com/*).
#
# The api-server resolves the org's Claude token BEFORE spawning an AI node's
# container (WorkloadService → SingleTenantDockerExecutor), so SOME token must be
# configured at boot for the agent container to spawn at all. This smoke uses a
# THROWAWAY dummy: the published agent image is pulled & started (the guard),
# then the real agent fails auth harmlessly — no real Claude call, no PR. The
# stack must therefore be booted with CLAUDE_CODE_OAUTH_TOKEN set (any value);
# the CI job sets it at the job level, and MANAGE_STACK=true sets it below.
#
# The spawned container is NOT auto-removed (cleanup is a separate later call),
# so `docker ps -a` reliably catches it via its choruskube/run-id label even
# after the agent exits.
#
# Assumes the stack is already up (CI runs `up.sh` first). Set MANAGE_STACK=true
# to have this script bring it up (with a dummy token) / tear it down itself.
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

API_URL="${API_URL:-http://localhost:38080}"
# The spawned container's image is one of the published OSS images under
# registry.choruskube.com/* — the seeded system-org repo resolves to the
# dev image (built FROM agent), so accept either claude-code or choruskube-dev by prefix.
EXPECTED_IMAGE_PREFIX="${EXPECTED_IMAGE_PREFIX:-registry.choruskube.com/}"
SPAWN_TIMEOUT="${SPAWN_TIMEOUT:-300}"   # seconds; covers a cold pull of the agent image
MANAGE_STACK="${MANAGE_STACK:-false}"

if [ "$MANAGE_STACK" = true ]; then
    echo "=== Bringing up the OSS stack (dummy Claude token so AI nodes spawn) ==="
    export CLAUDE_CODE_OAUTH_TOKEN="${CLAUDE_CODE_OAUTH_TOKEN:-oss-smoke-dummy-token-not-real}"
    # Smoke runs must not emit anonymous telemetry (overrides docker-compose.yaml default-on).
    export CHORUSKUBE_TELEMETRY=off
    bash "$SCRIPTS_DIR/up.sh"
    cleanup() { echo "=== Tearing down ==="; bash "$SCRIPTS_DIR/down.sh" || true; }
    trap cleanup EXIT
fi

fail() { echo "FAIL: $*" >&2; exit 1; }

# --- 1. Health checks ---------------------------------------------------------
echo "=== OSS stack health ==="
curl -sf "$API_URL/actuator/health" | grep -q UP || fail "api-server not healthy"
echo "  PASS  api-server health"
# /api/v1/** is open in single-tenant mode (no 401).
templates_open_probe "$API_URL" || fail "api /graph-templates not reachable (auth open?)"
echo "  PASS  api /graph-templates reachable (no auth)"

# --- 1b. Ensure the DinD sidecar image is present -----------------------------
# The seeded system-org GitRepo is enableDocker=true, so the executor starts a
# DinD sidecar (docker:29-dind) before the agent container — and it does NOT
# pull that image (SingleTenantDockerExecutor.startDindSidecar createContainer's it
# directly). Pre-pull it on the host daemon (the one the api-server spawns on)
# so the sidecar — and then the published agent container — can start.
# NB: this is a real OSS first-run gap worth fixing in the executor later.
echo "=== Pre-pulling DinD sidecar image (docker:29-dind) ==="
docker pull docker:29-dind >/dev/null && echo "  ok" || echo "  WARN: dind pull failed (may already be present)"

# Prime the published agent/dev images so the executor's createContainer is
# immediate rather than racing its 120s best-effort pull window (the large dev
# image can exceed it on a cold runner). Best-effort: on an arm64 host these
# amd64-only images have no native manifest — pull them with
# `--platform linux/amd64` beforehand; createContainer then finds them locally.
echo "=== Priming published agent images ==="
for img in claude-code choruskube-dev; do
    docker pull "registry.choruskube.com/${img}:latest" >/dev/null 2>&1 \
        && echo "  primed ${img}" || echo "  WARN: ${img} not primed (executor will pull, or already local)"
done

# --- 2. Resolve the seeded feature-dev template + a software project ----------
echo "=== Resolving feature-dev template + software project ==="
TEMPLATE_ID=$(curl -sf "$API_URL/api/v1/graph-templates?size=100&latestOnly=true" \
    | jq -r '.content[] | select(.name | test("feature dev"; "i")) | .id' | head -1)
[ -n "$TEMPLATE_ID" ] && [ "$TEMPLATE_ID" != "null" ] || fail "feature-dev template not found"
echo "  template: $TEMPLATE_ID"

# GitRepo is a SoftwareProject subtype; the system-org repo seeded at boot is a
# valid software_project_id.
SOFTWARE_PROJECT_ID=$(curl -sf "$API_URL/api/v1/software-projects" | jq -r '.[0].id')
[ -n "$SOFTWARE_PROJECT_ID" ] && [ "$SOFTWARE_PROJECT_ID" != "null" ] \
    || fail "no software project seeded (expected the system-org GitRepo)"
echo "  software_project: $SOFTWARE_PROJECT_ID"

# --- 3. Drive a feature-dev run ----------------------------------------------
echo "=== Creating feature-dev run ==="
RUN_ID=$(curl -sf -X POST "$API_URL/api/v1/runs" \
    -H 'Content-Type: application/json' \
    -d "$(jq -nc --arg t "$TEMPLATE_ID" --arg sp "$SOFTWARE_PROJECT_ID" \
        '{graphTemplateId:$t, name:"oss-smoke", inputs:{software_project_id:$sp, feature_request:"OSS smoke: prove the published agent image spawns."}}')" \
    | jq -r '.id')
[ -n "$RUN_ID" ] && [ "$RUN_ID" != "null" ] || fail "run creation returned no id"
echo "  run: $RUN_ID"

# --- 4. Assert an agent container spawns from a published image ---------------
echo "=== Waiting for an agent container from ${EXPECTED_IMAGE_PREFIX}* (timeout ${SPAWN_TIMEOUT}s) ==="
deadline=$((SECONDS + SPAWN_TIMEOUT))
CONTAINER=""
while [ $SECONDS -lt $deadline ]; do
    CONTAINER=$(docker ps -a --filter "label=choruskube/run-id=$RUN_ID" --format '{{.ID}}' | head -1)
    [ -n "$CONTAINER" ] && break
    sleep 3
done
[ -n "$CONTAINER" ] || fail "no agent container spawned for run $RUN_ID within ${SPAWN_TIMEOUT}s
  run status: $(curl -sf "$API_URL/api/v1/runs/$RUN_ID" | jq -c '{status, nodes:[.nodeExecutions[]?|{name,status,errorMessage}]}' 2>/dev/null || echo '?')"

ACTUAL_IMAGE=$(docker inspect "$CONTAINER" --format '{{.Config.Image}}')
echo "  spawned container $CONTAINER from image: $ACTUAL_IMAGE"
case "$ACTUAL_IMAGE" in
    "$EXPECTED_IMAGE_PREFIX"*) : ;;
    *) fail "agent container image '$ACTUAL_IMAGE' is not a published image under '${EXPECTED_IMAGE_PREFIX}'" ;;
esac

echo ""
echo "PASS: published image '$ACTUAL_IMAGE' pulled & spawned under the OSS docker executor."
