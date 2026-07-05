#!/usr/bin/env bash
# scripts/up.sh — Build & start the ChorusKube stack, wait until healthy.
#
# Usage:
#   ./scripts/up.sh                 # build + up --wait
#   ./scripts/setup.sh              # configure tokens first (writes .env), then runs this
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Ensure the GitHub App PEM mount source exists and is user-owned before compose
# (Docker would otherwise auto-create it as root). Harmless when empty.
mkdir -p "$SCRIPT_DIR/../.secrets"

# Build the agent images first. They are not Compose services (the executor pulls
# them at run time), so `compose up --build` would not cover them. Docker layer
# caching makes this cheap on repeat runs — consistent with how `--build` rebuilds
# the Compose services below.
"$SCRIPT_DIR/build-agent-images.sh"

exec docker compose -f "$SCRIPT_DIR/../docker-compose.yaml" up --build --wait "$@"
