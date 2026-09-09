#!/usr/bin/env bash
# scripts/e2e-up.sh — build & start the auth-free e2e stack, then load wiremock stubs.
#
#   ./scripts/e2e-up.sh            everything, in order (unchanged behaviour)
#   ./scripts/e2e-up.sh --images   image builds only
#   ./scripts/e2e-up.sh --stack    compose up + health wait + wiremock stubs only
#
# The flags exist so the two halves can be run — and therefore timed and log-buffered —
# as separate steps by the Gradle e2e chain. Splitting is safe because the halves share no
# shell state. With no flag the script still does both, in the same order as before, because
# local callers and anything driving the stack by hand invoke it that way.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

DO_IMAGES=1
DO_STACK=1
case "${1:-}" in
  --images) DO_STACK=0 ;;
  --stack)  DO_IMAGES=0 ;;
  "")       ;;
  *)        echo "usage: $(basename "$0") [--images|--stack]" >&2; exit 2 ;;
esac

# build_image <local-tag> <context> <dockerfile>
# The Go app images build FROM a warm dev base when the compose build arg E2E_DEV_BASE is
# set (see docker-compose.e2e.yaml); this helper just builds the standalone agent image.
build_image() {
  local tag="$1" context="$2" dockerfile="$3"
  docker build -t "$tag" -f "$dockerfile" "$context"
}

if [ "$DO_IMAGES" = "1" ]; then
  echo "--- Building agent images (claude-code:latest → claude-code:e2e) ---"
  build_image claude-code:latest \
    "${REPO_ROOT}/agent-images/claude-code" "${REPO_ROOT}/agent-images/claude-code/Dockerfile"
  # The e2e derivative just layers the mock-agent onto claude-code:latest — a cheap local build.
  docker build --build-arg BASE_AGENT_IMAGE=claude-code:latest \
    -f "${REPO_ROOT}/agent-images/claude-code-e2e/Dockerfile" \
    -t claude-code:e2e "${REPO_ROOT}/agent-images/claude-code"
  # The hermetic git remote for e2e-test/* repos is baked into claude-code:e2e
  # itself (see that Dockerfile) — no separate image to build here.

  # The app images (api-server, orchestrator, web-ui, worker) build via `compose up --build`
  # in the stack half below, so their build time is attributed to the stack step. The Go
  # ones pick up the warm dev base through the E2E_DEV_BASE compose build arg.
fi

if [ "$DO_STACK" = "1" ]; then
  echo "--- Starting stack (building images) ---"
  compose_e2e up -d --build

  echo "--- Waiting for api-server health ---"
  if ! wait_for_health http://localhost:28080/actuator/health; then
    echo "ERROR: api-server did not become healthy within ~120s" >&2
    echo "       Inspect: docker compose -f docker-compose.e2e.yaml logs api-server" >&2
    exit 1
  fi

  # orchestrator's own compose healthcheck (docker-compose.e2e.yaml) isn't awaited by
  # `compose up -d` itself — only the *dependencies* it declares via `depends_on: condition:
  # service_healthy` block its start, not its own readiness. Nothing upstream of :e2eSmoke
  # otherwise waits for it, so a slow image build (e.g. cold layer cache) can leave the
  # container still dialing Temporal when :e2eSmoke's single unretried curl hits it.
  echo "--- Waiting for orchestrator health ---"
  if ! wait_for_health http://localhost:29080/healthz 60 2 healthy; then
    echo "ERROR: orchestrator did not become healthy within ~120s" >&2
    echo "       Inspect: docker compose -f docker-compose.e2e.yaml logs orchestrator" >&2
    exit 1
  fi

  echo "--- Loading WireMock stubs ---"
  WM=http://localhost:28085   # see Step note on port mapping
  for f in "${REPO_ROOT}"/e2e/wiremock-stubs/*.json; do
    curl -sf -X POST "${WM}/__admin/mappings/import" -H 'Content-Type: application/json' --data-binary "@${f}" >/dev/null
    echo "loaded stub: $(basename "$f")"
  done
  echo "e2e stack up. Web UI: http://localhost:23000  API: http://localhost:28080"
fi
