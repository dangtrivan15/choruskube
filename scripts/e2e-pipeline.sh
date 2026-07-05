#!/usr/bin/env bash
# scripts/e2e-pipeline.sh — seed-verify + Playwright run against the live stack.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"
bash "${REPO_ROOT}/e2e/setup-test-data.sh"
cd "${REPO_ROOT}/web-ui"
CI="${CI:-}" npx playwright test
