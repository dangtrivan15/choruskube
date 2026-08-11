#!/usr/bin/env bash
# scripts/e2e-up.sh — build & start the auth-free e2e stack, then load wiremock stubs.
#
#   ./scripts/e2e-up.sh            everything, in order (unchanged behaviour)
#   ./scripts/e2e-up.sh --images   image builds only
#   ./scripts/e2e-up.sh --stack    compose up + health wait + wiremock stubs only
#
# The flags exist so the two halves can be run — and therefore timed and log-buffered —
# as separate steps by the Gradle e2e chain. Splitting is safe because the halves share no
# shell state: the only thing the stack half needs to know about the image half is whether
# BUILD_CACHE_REGISTRY was set, and that is read from the environment in both invocations.
# With no flag the script still does both, in the same order as before, because local
# callers and anything driving the stack by hand invoke it that way.
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

# Optional build-layer cache. When BUILD_CACHE_REGISTRY is set (a CI runner supplies
# it), each image imports its layer cache from ${BUILD_CACHE_REGISTRY}/<name>:buildcache
# so unchanged build steps (dependency downloads, compiles) are reused across runs.
# Import only — the cache is produced by the image-publishing pipeline; e2e never writes
# it. Unset (local dev, forks) => plain `docker build` + `compose up --build`, unchanged.
CACHE_REGISTRY="${BUILD_CACHE_REGISTRY:-}"

# build_image <cache-name> <local-tag> <context> <dockerfile>
# cache-name is the published image name whose :buildcache to import; local-tag is what
# the resulting image is loaded as.
build_image() {
  local cache_name="$1" tag="$2" context="$3" dockerfile="$4"
  if [ -n "$CACHE_REGISTRY" ]; then
    local from_args=(--cache-from "type=registry,ref=${CACHE_REGISTRY}/${cache_name}:buildcache")
    local args=("${from_args[@]}")
    # BUILD_CACHE_PUSH=1 (dogfood pod): also write the cache back so the next run is warm.
    # Requires a docker-container builder (the agent entrypoint creates 'choruskube-builder').
    if [ "${BUILD_CACHE_PUSH:-0}" = "1" ]; then
      args+=(--cache-to "type=registry,ref=${CACHE_REGISTRY}/${cache_name}:buildcache,mode=max")
    fi
    # Cache export is a warm-next-run optimization, not correctness — if the registry
    # rejects/mishandles the push (e.g. a builder that doesn't honor the in-cluster
    # registry's plain-HTTP config), don't let that fail the whole e2e run. Retry once
    # with cache-from only so the build still succeeds, just without writing cache back.
    if ! docker buildx build "${args[@]}" --load -t "$tag" -f "$dockerfile" "$context"; then
      if [ "${BUILD_CACHE_PUSH:-0}" = "1" ]; then
        echo "WARNING: buildx build with cache export failed for ${cache_name}; retrying without --cache-to" >&2
        docker buildx build "${from_args[@]}" --load -t "$tag" -f "$dockerfile" "$context"
      else
        return 1
      fi
    fi
  else
    docker build -t "$tag" -f "$dockerfile" "$context"
  fi
}

if [ "$DO_IMAGES" = "1" ]; then
  echo "--- Building agent images (claude-code:latest → claude-code:e2e) ---"
  build_image claude-code claude-code:latest \
    "${REPO_ROOT}/agent-images/claude-code" "${REPO_ROOT}/agent-images/claude-code/Dockerfile"
  # The e2e derivative just layers the mock-agent onto claude-code:latest; it has no
  # published :buildcache of its own, so it always builds locally (cheap).
  docker build --build-arg BASE_AGENT_IMAGE=claude-code:latest \
    -f "${REPO_ROOT}/agent-images/claude-code-e2e/Dockerfile" \
    -t claude-code:e2e "${REPO_ROOT}/agent-images/claude-code"

  if [ -n "$CACHE_REGISTRY" ]; then
    # Pre-build the app images with the registry layer cache, tagged as the compose
    # services expect, so `compose up` (no --build) consumes them.
    echo "--- Pre-building app images with registry layer cache ---"
    build_image api-server   choruskube-e2e-api-server:local \
      "${REPO_ROOT}/api-server"   "${REPO_ROOT}/api-server/Dockerfile"
    build_image orchestrator choruskube-e2e-orchestrator:local \
      "${REPO_ROOT}/orchestrator" "${REPO_ROOT}/orchestrator/Dockerfile"
    build_image web-ui       choruskube-e2e-web-ui:local \
      "${REPO_ROOT}/web-ui"       "${REPO_ROOT}/web-ui/Dockerfile"
  fi
  # Without a cache registry the app images are built by `compose up --build` in the stack
  # half below, so on that path the app image build time is attributed to the stack step.
  # That is unchanged from before the split — it was one step then too.
fi

if [ "$DO_STACK" = "1" ]; then
  if [ -n "$CACHE_REGISTRY" ]; then
    echo "--- Starting stack ---"
    compose_e2e up -d
  else
    echo "--- Starting stack (building images) ---"
    compose_e2e up -d --build
  fi

  echo "--- Waiting for api-server health ---"
  if ! wait_for_health http://localhost:28080/actuator/health; then
    echo "ERROR: api-server did not become healthy within ~120s" >&2
    echo "       Inspect: docker compose -f docker-compose.e2e.yaml logs api-server" >&2
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
