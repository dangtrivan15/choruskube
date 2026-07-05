#!/usr/bin/env bash
# scripts/e2e-smoke.sh — fail fast if any e2e service is unreachable.
set -euo pipefail
fail=0
check() { if curl -sf "$2" >/dev/null; then echo "ok: $1"; else echo "FAIL: $1 ($2)"; fail=1; fi; }
check "api-server health"   "http://localhost:28080/actuator/health"
check "api templates (open)" "http://localhost:28080/api/v1/graph-templates?size=1"
check "web-ui"              "http://localhost:23000"
check "orchestrator"       "http://localhost:29090/healthz"
check "wiremock admin"     "http://localhost:28085/__admin/health"
exit $fail
