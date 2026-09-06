#!/usr/bin/env bash
# scripts/e2e-smoke.sh — fail fast if any e2e service is unreachable.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"
fail=0
check() { if curl -sf "$2" >/dev/null; then echo "ok: $1"; else echo "FAIL: $1 ($2)"; fail=1; fi; }
check "api-server health"   "http://localhost:28080/actuator/health"
if templates_open_probe "http://localhost:28080"; then echo "ok: api templates (open)"; else echo "FAIL: api templates (open)"; fail=1; fi
check "web-ui"              "http://localhost:23000"
check "orchestrator"       "http://localhost:29080/healthz"
check "wiremock admin"     "http://localhost:28085/__admin/health"
exit $fail
