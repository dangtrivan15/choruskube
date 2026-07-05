#!/usr/bin/env bash
# scripts/e2e-down.sh — stop the e2e stack. Pass --volumes to wipe data.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"
if [ "${1:-}" = "--volumes" ]; then compose_e2e down -v; else compose_e2e down; fi
# Reap any DooD-spawned agent containers left behind by a crashed run.
docker ps -aq --filter "ancestor=claude-code:e2e" | xargs -r docker rm -f
