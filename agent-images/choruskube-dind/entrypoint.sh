#!/bin/sh
# agent-images/choruskube-dind/entrypoint.sh
# Sidecar wrapper around the stock dind daemon: starts dockerd, loads every baked
# warm-Docker-cache archive this image carries best-effort, then runs dockerd in
# the foreground. Readiness is generic daemon-up on 2375, gated externally by the
# k8s startupProbe / docker HEALTHCHECK — this script does not signal it itself.
# POSIX sh, not bash: the docker:*-dind base is Alpine-based and ships no bash, same
# as the base image's own dockerd-entrypoint.sh this script delegates to.
set -euo pipefail

# Shared contract with the image bake that copies archives here — both sides must use this same
# constant. Every *.tar loads, so a base image and one built FROM it drop archives under distinct
# names and both load, rather than one overwriting the other.
: "${PRELOAD_DIR:=/opt/choruskube/preload}"

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

# Waits for the (already-started) daemon, then loads the archives.
run_preload() {
  if ! wait_for_docker; then
    echo "preload: FATAL docker daemon not reachable" >&2
    return 1
  fi
  load_preload_archives || return 1
}

# --preload-only lets the test harness exercise run_preload in isolation via `source`, without
# launching a real dockerd first.
if [ "${1:-}" = "--preload-only" ]; then run_preload; return 0 2>/dev/null || exit 0; fi

# PID 1: without a trap the container's SIGTERM never reaches dockerd and teardown rides the full
# grace period to SIGKILL. dockerd-entrypoint.sh execs dockerd, so $DOCKERD_PID keeps naming it.
# The trap does its own `wait` because a trapped signal interrupts the `wait` below and returns
# before dockerd has exited; the trailing `exit 0` is load-bearing too — without it a signal during
# run_preload resumes that interrupted loop (~120s) instead of tearing down once dockerd is gone.
forward_term() {
  kill -TERM "$DOCKERD_PID" 2>/dev/null || true
  wait "$DOCKERD_PID" 2>/dev/null || true
  exit 0
}

# Delegate to the base image's own entrypoint (TLS cert generation + dockerd host defaults),
# backgrounded so this script can load the preload before taking over the foreground.
# Pass "$@" WITHOUT a literal `dockerd`: dockerd-entrypoint.sh runs its cert-gen and TLS setup
# only when its first arg starts with `-` (or there are none), so a literal `dockerd` silently
# skips TLS and the daemon comes up socket-only. Callers pass their dockerd flags directly (all
# `-`-prefixed), which keeps that setup running.
dockerd-entrypoint.sh "$@" &
DOCKERD_PID=$!
trap forward_term TERM INT

run_preload

wait "$DOCKERD_PID"
