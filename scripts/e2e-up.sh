#!/usr/bin/env bash
# scripts/e2e-up.sh — build & start the auth-free e2e stack, then load wiremock stubs.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

echo "--- Building agent images (claude-code:latest → claude-code:e2e) ---"
docker build -t claude-code:latest "${REPO_ROOT}/agent-images/claude-code"
docker build --build-arg BASE_AGENT_IMAGE=claude-code:latest \
  -f "${REPO_ROOT}/agent-images/claude-code-e2e/Dockerfile" \
  -t claude-code:e2e "${REPO_ROOT}/agent-images/claude-code"

echo "--- Starting stack ---"
compose_e2e up -d --build

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
