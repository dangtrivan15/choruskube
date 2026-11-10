#!/usr/bin/env bash
# scripts/up.sh — Build & start the ChorusKube stack, wait until healthy.
#
# Usage:
#   ./scripts/up.sh                 # build + up --wait
#   ./scripts/setup.sh              # configure credentials first (writes .secrets/credentials/), then runs this
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

# The Worker's credentials-folder mount source must exist user-owned before compose: Docker
# would otherwise create it as root, and setup.sh could no longer write it. Harmless when empty.
mkdir -p "$REPO_ROOT/.secrets/credentials"

# Build the agent images first. They are not Compose services (the executor pulls
# them at run time), so `compose up --build` would not cover them. Docker layer
# caching makes this cheap on repeat runs — consistent with how `--build` rebuilds
# the Compose services below.
"$SCRIPTS_DIR/build-agent-images.sh"

compose up --build --wait "$@"
