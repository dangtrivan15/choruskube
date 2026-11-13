#!/usr/bin/env bash
# e2e/check-api-server-made-no-stub-call.sh — after the Playwright suite, prove that no request
# from the api-server's JDK HTTP client ever reached WireMock, and that the Worker's did. The
# api-server is given no stub URL at all in this stack (it has no GitHub client to point at one);
# core's source-only guard (ControlPlaneGitHubCallsTest) is what proves that in source, at build
# time — this check instead proves it held at runtime, against the one journal both processes share.
#
# Reads WireMock's request journal. The api-server's JDK client sends `User-Agent:
# Java-http-client/<version>`; the Worker always sends `User-Agent: choruskube-worker`
# (worker/github/client.go, worker/anthropic/client.go).
set -euo pipefail

WIREMOCK_URL="${E2E_WIREMOCK_URL:-http://localhost:28085}"

find_requests() {
  # $1 = WireMock header match for User-Agent, as JSON
  curl -sf -X POST "${WIREMOCK_URL}/__admin/requests/find" \
    -H 'Content-Type: application/json' \
    -d "{\"method\":\"ANY\",\"headers\":{\"User-Agent\":$1}}"
}

api_server_requests="$(find_requests '{"matches":"Java-http-client.*"}')" \
  || { echo "FAIL: could not query WireMock's request journal at ${WIREMOCK_URL}" >&2; exit 1; }
count="$(jq '.requests | length' <<< "$api_server_requests")"
if [[ "$count" -ne 0 ]]; then
  echo "FAIL: the api-server sent ${count} request(s) to the GitHub/Anthropic stub; it must hold no credential:" >&2
  jq -r '.requests[] | "  \(.method) \(.url)"' <<< "$api_server_requests" >&2
  exit 1
fi

# Without this the check would pass vacuously on a journal that recorded nothing at all.
worker_requests="$(find_requests '{"equalTo":"choruskube-worker"}')" \
  || { echo "FAIL: could not query WireMock's request journal at ${WIREMOCK_URL}" >&2; exit 1; }
worker_count="$(jq '.requests | length' <<< "$worker_requests")"
if [[ "$worker_count" -eq 0 ]]; then
  echo "FAIL: WireMock journaled no request from the Worker (User-Agent: choruskube-worker); the check cannot tell who called the stub" >&2
  exit 1
fi

echo "PASS: no stub request from the api-server; ${worker_count} from the Worker"
