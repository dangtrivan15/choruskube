#!/bin/sh
# agent-images/choruskube-dind/entrypoint.sh
# Sidecar wrapper around the stock dind daemon: starts dockerd, loads every baked
# warm-Docker-cache archive this image carries, drops a readiness marker the
# agent container can poll for, then runs dockerd in the foreground.
# POSIX sh, not bash: docker:29-dind is Alpine-based and ships no bash, same as
# the base image's own dockerd-entrypoint.sh this script delegates to.
set -euo pipefail

# The directory is a shared contract with the image bake that copies archives into
# it — both sides must reference this same constant. Every *.tar here loads, so a
# base image and an image built FROM it each drop their own archive under distinct
# filenames, letting an overlay bake only its delta instead of re-baking the base's.
: "${PRELOAD_DIR:=/opt/choruskube/preload}"
: "${READY_MARKER:=/tmp/preload-ready}"

wait_for_docker() {
  for _ in $(seq 1 60); do docker info >/dev/null 2>&1 && return 0; sleep 2; done
  return 1
}

load_preload_archives() {
  local loaded=0 arch t0 t1
  for arch in "$PRELOAD_DIR"/*.tar; do
    [ -e "$arch" ] || continue  # no match: the glob stays literal, so skip it
    t0=$(date +%s)
    if ! docker load -i "$arch" >/dev/null; then
      echo "preload: FATAL failed to load $arch" >&2; return 1
    fi
    t1=$(date +%s); echo "preload: loaded $arch in $((t1-t0))s"
    loaded=$((loaded+1))
  done
  [ "$loaded" -gt 0 ] || echo "preload: none ($PRELOAD_DIR/*.tar absent)"
}

# Waits for the (already-started) daemon, loads the archives, then drops the
# readiness marker the agent container's startupProbe polls for. Split out so
# --preload-only below can exercise it against an already-reachable stubbed
# `docker`, without this script itself launching a real dockerd.
run_preload() {
  if ! wait_for_docker; then
    echo "preload: FATAL docker daemon not reachable" >&2
    return 1
  fi
  load_preload_archives || return 1
  touch "$READY_MARKER"
}

# Lets the test harness exercise run_preload in isolation via `source`, without
# this script launching a real dockerd first (same technique as the claude-code
# agent entrypoint's --preload-only used before this step moved here).
if [ "${1:-}" = "--preload-only" ]; then run_preload; return 0 2>/dev/null || exit 0; fi

# This script is PID 1, so without a trap the container's SIGTERM never reaches
# dockerd and every teardown rides out the full grace period to SIGKILL. dockerd
# is a straight exec of dockerd-entrypoint.sh, so $DOCKERD_PID still names it.
# The trap does its own `wait`: POSIX has a trapped signal interrupt the `wait`
# below and return immediately, before dockerd has actually exited, so that one
# alone would let PID 1 exit while dockerd is still mid-shutdown. The trailing
# `exit 0` is just as load-bearing: without it, a signal arriving during
# run_preload resumes that interrupted loop (up to ~120s in wait_for_docker)
# instead of tearing down promptly once dockerd is already gone.
forward_term() {
  kill -TERM "$DOCKERD_PID" 2>/dev/null || true
  wait "$DOCKERD_PID" 2>/dev/null || true
  exit 0
}

# Delegate to the base image's own entrypoint for TLS cert setup and dockerd
# flags, backgrounded so this script can load the preload archive and drop the
# readiness marker before taking over as the container's foreground process.
dockerd-entrypoint.sh dockerd &
DOCKERD_PID=$!
trap forward_term TERM INT

run_preload

wait "$DOCKERD_PID"
