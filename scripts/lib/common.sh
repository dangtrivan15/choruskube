#!/usr/bin/env bash
# scripts/lib/common.sh — shared helpers sourced by the scripts in scripts/.
#
# Source it with the minimal bootstrap line (before any $REPO_ROOT use):
#   source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"
#
# It exports SCRIPTS_DIR / REPO_ROOT (derived from THIS file's location, not the
# caller's) and provides the compose wrappers + health/auth probes that were
# previously copy-pasted across the OSS and e2e stacks. It deliberately does NOT
# set `set -euo pipefail` — that stays the caller's choice, so sourcing never
# changes a script's error-handling mode.

# Resolve paths from this library's own location: lib/ -> scripts/ -> repo root.
SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_ROOT="$(cd "$SCRIPTS_DIR/.." && pwd)"

# --- Compose wrappers ---------------------------------------------------------
# Thin wrappers over the two canonical compose files. Kept as functions (not
# `exec`) so callers can run several compose commands per script; the previous
# `exec docker compose ...` one-liners had nothing after them, so dropping exec
# is behaviour-preserving.
compose()     { docker compose -f "$REPO_ROOT/docker-compose.yaml"     "$@"; }
compose_e2e() { docker compose -f "$REPO_ROOT/docker-compose.e2e.yaml" "$@"; }

# --- Health / auth probes -----------------------------------------------------
# Poll a health URL until its body matches a marker string. Args: <url> [attempts] [delay] [match]
# <match> defaults to "UP" (Spring actuator's convention); pass e.g. "healthy" for the
# orchestrator's /healthz, which is plain Go JSON, not an actuator response.
# Returns 0 once matched, non-zero if it never matched within attempts*delay seconds.
wait_for_health() {
  local url="$1" attempts="${2:-60}" delay="${3:-2}" match="${4:-UP}"
  for _ in $(seq 1 "$attempts"); do
    if curl -sf "$url" 2>/dev/null | grep -q "$match"; then return 0; fi
    sleep "$delay"
  done
  return 1
}

# The "auth is open in single-tenant mode" probe: /api/v1/graph-templates must be
# reachable WITHOUT a token. Shared verbatim by both smoke scripts (OSS + e2e).
# Args: <api_base_url> (no trailing slash needed). Returns curl's exit status.
templates_open_probe() {
  curl -sf "${1%/}/api/v1/graph-templates?size=1" >/dev/null
}
