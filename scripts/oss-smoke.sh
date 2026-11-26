#!/usr/bin/env bash
# scripts/oss-smoke.sh — Smoke test for the OSS ChorusKube docker-compose stack.
#
# Proves the PUBLISHED images run under the canonical OSS wiring, with only a
# throwaway dummy Claude token (no real token, no real Claude call, no PR):
#   1. health-check the booted stack,
#   1a. assert the object store's network namespace has no listener off loopback other
#       than the forwarder's S3 port, and that neither container publishes any other port,
#   2. drive ONE seeded feature-dev run (no auth — AUTH_ENABLED=false),
#   3. assert the worker (EXECUTOR_TYPE=docker) pulls & spawns an agent
#      container FROM a published image (registry.choruskube.com/*).
#
# The Worker refuses to launch an AI node without a Claude token in its own
# credentials folder (.secrets/credentials/claude-oauth-token, mounted read-only).
# SOME token must therefore be there for the agent container to spawn at all. This
# smoke uses a THROWAWAY dummy: the published agent image is pulled & started (the
# guard), then the real agent fails auth harmlessly — no real Claude call, no PR.
# With MANAGE_STACK=true the script puts that dummy in the folder when no token is
# there yet (never overwriting one) and removes it again on exit. It also runs
# `setup.sh --worker-token-only` before booting and leaves the generated Worker Fleet
# token in .env, since it becomes the install's own token from then on.
#
# The spawned container is NOT auto-removed (cleanup is a separate later call),
# so `docker ps -a` reliably catches it via its choruskube/run-id label even
# after the agent exits.
#
# Assumes the stack is already up (run `up.sh` first). Set MANAGE_STACK=true
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
    CLAUDE_TOKEN_FILE="$REPO_ROOT/.secrets/credentials/claude-oauth-token"
    WROTE_TOKEN_FILE=false
    # Only a file this script wrote may be removed on exit: an existing one is the operator's real token.
    if [ ! -e "$CLAUDE_TOKEN_FILE" ]; then
        mkdir -p "$(dirname "$CLAUDE_TOKEN_FILE")"
        (umask 077; printf '%s\n' "${CLAUDE_CODE_OAUTH_TOKEN:-oss-smoke-dummy-token-not-real}" > "$CLAUDE_TOKEN_FILE")
        chmod 600 "$CLAUDE_TOKEN_FILE"
        WROTE_TOKEN_FILE=true
    fi
    cleanup() {
        echo "=== Tearing down ==="
        bash "$SCRIPTS_DIR/down.sh" || true
        if [ "$WROTE_TOKEN_FILE" = true ]; then rm -f "$CLAUDE_TOKEN_FILE"; fi
    }
    trap cleanup EXIT
    # Smoke runs must not emit anonymous telemetry (overrides docker-compose.yaml default-on).
    export CHORUSKUBE_TELEMETRY=off
    bash "$SCRIPTS_DIR/setup.sh" --worker-token-only
    bash "$SCRIPTS_DIR/up.sh"
fi

fail() { echo "FAIL: $*" >&2; exit 1; }

# --- 1. Health checks ---------------------------------------------------------
echo "=== OSS stack health ==="
grep -q UP <<< "$(curl -sf "$API_URL/actuator/health")" || fail "api-server not healthy"
echo "  PASS  api-server health"
# /api/v1/** is open in single-tenant mode (no 401).
templates_open_probe "$API_URL" || fail "api /graph-templates not reachable (auth open?)"
echo "  PASS  api /graph-templates reachable (no auth)"

# --- 1a. Object store reach (listener confinement) ----------------------------
# Proves the published-image stack holds the same contract the e2e Playwright spec
# (web-ui/e2e/specs/object-store-reach.spec.ts) checks against the built stack: the only
# listener off loopback anywhere in the store's network namespace is the forwarder's S3
# port, and neither container publishes any other port on the host.
echo "=== Object store reach (listener confinement) ==="
STORE_CONTAINER=$(docker ps -a --filter "label=com.docker.compose.project=choruskube" \
    --filter "label=com.docker.compose.service=object-storage-server" --format '{{.ID}}' | head -1)
[ -n "$STORE_CONTAINER" ] || fail "no object-storage-server container found"
FORWARDER_CONTAINER=$(docker ps -a --filter "label=com.docker.compose.project=choruskube" \
    --filter "label=com.docker.compose.service=object-storage" --format '{{.ID}}' | head -1)
[ -n "$FORWARDER_CONTAINER" ] || fail "no object-storage (forwarder) container found"

# Hex-decodes a /proc/net/{tcp,tcp6,udp,udp6} address field without relying on a
# strtonum() extension, so this runs under busybox awk as well as gawk. TCP's LISTEN
# state is 0A; UDP has no such state machine, so every bound UDP row counts.
AWK_DECODE_LISTENERS='
function hex2dec(h,    i,c,v,d) {
  v = 0
  for (i = 1; i <= length(h); i++) {
    c = toupper(substr(h, i, 1))
    if (c >= "0" && c <= "9") d = index("0123456789", c) - 1
    else d = index("ABCDEF", c) + 9
    v = v * 16 + d
  }
  return v
}
function first_octet(w) { return hex2dec(substr(w, 7, 2)) }
{
  if ($1 == "sl") next
  if ((proto == "tcp" || proto == "tcp6") && $4 != "0A") next
  split($2, parts, ":")
  addrhex = parts[1]
  loop = 0
  if (length(addrhex) == 8) {
    loop = (first_octet(addrhex) == 127)
  } else {
    w0 = substr(addrhex, 1, 8); w1 = substr(addrhex, 9, 8)
    w2 = substr(addrhex, 17, 8); w3 = substr(addrhex, 25, 8)
    if (w0 == "00000000" && w1 == "00000000" && w2 == "00000000" && w3 == "01000000") loop = 1
    else if (w0 == "00000000" && w1 == "00000000" && w2 == "FFFF0000" && first_octet(w3) == 127) loop = 1
  }
  if (!loop) print proto, addrhex, hex2dec(parts[2])
}
'
OFFLOOP=""
for proto in tcp tcp6 udp udp6; do
    table=$(docker exec "$STORE_CONTAINER" cat "/proc/net/$proto") \
        || fail "could not read /proc/net/$proto inside the object-storage-server container"
    OFFLOOP="$OFFLOOP$(awk -v proto="$proto" "$AWK_DECODE_LISTENERS" <<< "$table")
"
done
# Without the forwarder's own listener in the table, the store is not in its namespace and the
# check below would pass on a table that proves nothing.
grep -Eq '^tcp6? [0-9A-F]+ 9000$' <<< "$OFFLOOP" \
    || fail "the forwarder's TCP 9000 is not in the object store's namespace"
BAD=$(echo "$OFFLOOP" | awk '{ if (!(($1 == "tcp" || $1 == "tcp6") && $3 == 9000)) print }')
[ -z "$(echo "$BAD" | tr -d '[:space:]')" ] \
    || fail "object store namespace has a listener off loopback other than TCP 9000:
$BAD"
echo "  PASS  object store namespace: only TCP 9000 off loopback"

FWD_PORTS=$(docker port "$FORWARDER_CONTAINER" | awk '{print $1}' | sort -u | tr '\n' ' ')
[ "$(echo "$FWD_PORTS" | xargs)" = "9000/tcp" ] \
    || fail "object-storage (forwarder) publishes unexpected ports: $FWD_PORTS"
STORE_PORTS=$(docker port "$STORE_CONTAINER")
[ -z "$STORE_PORTS" ] || fail "object-storage-server has a published port (expected none): $STORE_PORTS"
echo "  PASS  only the forwarder publishes a port (9000/tcp)"

# --- 1b. Ensure the DinD sidecar image is present -----------------------------
# The seeded system-org GitRepo is enableDocker=true, so the executor starts a
# DinD sidecar (docker:29-dind) before the agent container. Its own pull of that
# image is best-effort and bounded at 120s, so pre-pull it on the host daemon
# (the one the worker spawns on) rather than race a cold download.
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
