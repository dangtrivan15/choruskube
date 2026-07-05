#!/usr/bin/env bash
# scripts/e2e-down.sh — stop the e2e stack. Pass --volumes to wipe data.
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE="docker compose -f ${REPO_ROOT}/docker-compose.e2e.yaml"
if [ "${1:-}" = "--volumes" ]; then ${COMPOSE} down -v; else ${COMPOSE} down; fi
# Reap any DooD-spawned agent containers left behind by a crashed run.
docker ps -aq --filter "ancestor=claude-code:e2e" | xargs -r docker rm -f
