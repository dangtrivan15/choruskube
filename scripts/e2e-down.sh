#!/usr/bin/env bash
# scripts/e2e-down.sh — stop the e2e stack. Pass --volumes to wipe data.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"
if [ "${1:-}" = "--volumes" ]; then compose_e2e down -v; else compose_e2e down; fi

# Reap any DooD-spawned agent (and DinD sidecar) containers left behind by a
# crashed run. Scoped to this stack's compose project instead of a host-wide
# image/ancestor filter, so it can't reap an unrelated container that merely
# shares the same image name (a different process, a leftover manual `docker
# run`, ...). SingleTenantDockerExecutor doesn't create these containers
# through `docker compose`, so they never get Compose's own project label —
# but docker-compose.e2e.yaml's fixed `name:`/network are the same literal
# ("choruskube-e2e", see DOCKER_NETWORK on the api-server service), and every
# container this executor creates joins that network, so filtering by network
# membership scopes the reaper to this compose project.
#
# This does NOT (and, per the fixed project name, cannot) distinguish two
# concurrently-running instances of this same repo's E2E stack on one Docker
# daemon — that is an accepted local-dev-only gap, not something this scoping
# fix is meant to solve.
COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-choruskube-e2e}"
docker ps -aq --filter "network=${COMPOSE_PROJECT_NAME}" --filter "label=app=choruskube-agent" \
  | xargs -r docker rm -f
