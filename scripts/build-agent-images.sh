#!/usr/bin/env bash
# scripts/build-agent-images.sh — Build the ChorusKube agent images locally.
#
# The agent images are NOT Docker Compose services — they are pulled by the
# api-server's workload executor at run time to launch agent pods. The published
# images are currently amd64-only, so on other architectures (e.g. arm64 dev
# hosts) we build them locally and natively here. The executor is local-first:
# once these images exist locally it uses them without re-pulling.
#
# Build order matters: choruskube-dev (the "dev" agent) extends claude-code
# (the base "agent"), so the base is built first and passed in via BASE_AGENT_IMAGE.
#
# Usage:
#   ./scripts/build-agent-images.sh        # build both images for the host arch
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"
IMAGES_DIR="$REPO_ROOT/agent-images"

# Image refs the executor/run config expect. Override via env if needed.
AGENT_IMAGE="${AGENT_IMAGE:-registry.choruskube.com/claude-code:latest}"
DEV_IMAGE="${DEV_IMAGE:-registry.choruskube.com/choruskube-dev:latest}"
DIND_IMAGE="${DIND_IMAGE:-docker:29-dind}"

echo "=== Building base agent image: $AGENT_IMAGE ==="
docker build -t "$AGENT_IMAGE" "$IMAGES_DIR/claude-code"

echo "=== Building dev agent image: $DEV_IMAGE (BASE_AGENT_IMAGE=$AGENT_IMAGE) ==="
docker build \
    --build-arg "BASE_AGENT_IMAGE=$AGENT_IMAGE" \
    -t "$DEV_IMAGE" \
    "$IMAGES_DIR/choruskube-dev"

# Prime the DinD sidecar image (multi-arch) so the first run is fully local and
# never blocks on a cold pull while the orchestrator's request is in flight.
echo "=== Priming DinD sidecar image: $DIND_IMAGE ==="
docker pull "$DIND_IMAGE"

echo "=== Agent images ready ==="
docker image ls --filter "reference=registry.choruskube.com/*" \
    --format 'table {{.Repository}}:{{.Tag}}\t{{.ID}}\t{{.Size}}'
