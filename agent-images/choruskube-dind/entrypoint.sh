#!/bin/sh
# agent-images/choruskube-dind/entrypoint.sh
# Sidecar wrapper around the stock dind daemon: starts dockerd, loads a baked
# warm-Docker-cache archive if this image has one, drops a readiness marker the
# agent container can poll for, then runs dockerd in the foreground.
# POSIX sh, not bash: docker:29-dind is Alpine-based and ships no bash, same as
# the base image's own dockerd-entrypoint.sh this script delegates to.
set -euo pipefail

# The path is a shared contract with the image bake that produces the archive —
# both sides must reference this same constant.
: "${PRELOAD_ARCHIVE:=/opt/choruskube/preload/stack.tar}"
: "${READY_MARKER:=/tmp/preload-ready}"

wait_for_docker() {
  for _ in $(seq 1 60); do docker info >/dev/null 2>&1 && return 0; sleep 2; done
  return 1
}

load_preload_archive() {
  if [ ! -f "$PRELOAD_ARCHIVE" ]; then echo "preload: none ($PRELOAD_ARCHIVE absent)"; return 0; fi
  local t0 t1; t0=$(date +%s)
  if ! docker load -i "$PRELOAD_ARCHIVE" >/dev/null; then
    echo "preload: FATAL failed to load $PRELOAD_ARCHIVE" >&2; return 1
  fi
  t1=$(date +%s); echo "preload: loaded $PRELOAD_ARCHIVE in $((t1-t0))s"
}

# Waits for the (already-started) daemon, loads the archive, then drops the
# readiness marker the agent container's startupProbe polls for. Split out so
# --preload-only below can exercise it against an already-reachable stubbed
# `docker`, without this script itself launching a real dockerd.
run_preload() {
  if ! wait_for_docker; then
    echo "preload: FATAL docker daemon not reachable" >&2
    return 1
  fi
  load_preload_archive || return 1
  touch "$READY_MARKER"
}

# Lets the test harness exercise run_preload in isolation via `source`, without
# this script launching a real dockerd first (same technique as the claude-code
# agent entrypoint's --preload-only used before this step moved here).
if [ "${1:-}" = "--preload-only" ]; then run_preload; return 0 2>/dev/null || exit 0; fi

# Delegate to the base image's own entrypoint for TLS cert setup and dockerd
# flags, backgrounded so this script can load the preload archive and drop the
# readiness marker before taking over as the container's foreground process.
dockerd-entrypoint.sh dockerd &
DOCKERD_PID=$!

run_preload

wait "$DOCKERD_PID"
