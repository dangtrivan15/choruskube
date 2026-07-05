#!/usr/bin/env bash
# e2e/lib.sh — auth-free API helpers for the single-tenant core e2e stack.
set -euo pipefail

API_URL="${API_URL:-http://localhost:28080}"

api_get()  { curl -sf "${API_URL}$1"; }
api_post() { curl -sf -X POST "${API_URL}$1" -H "Content-Type: application/json" -d "${2:-}"; }
extract_id() { jq -r "$1"; }

find_template() {
  # $1 = template name
  api_get "/api/v1/graph-templates?size=200" \
    | jq -e --arg n "$1" '.content[] | select(.name == $n) | .id' >/dev/null
}

find_node_def() {
  api_get "/api/v1/node-definitions?size=200" \
    | jq -e --arg n "$1" '.content[] | select(.name == $n) | .id' >/dev/null
}
