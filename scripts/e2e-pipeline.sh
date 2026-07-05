#!/usr/bin/env bash
# scripts/e2e-pipeline.sh — seed-verify + Playwright run against the live stack.
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
bash "${REPO_ROOT}/e2e/setup-test-data.sh"
cd "${REPO_ROOT}/web-ui"
CI="${CI:-}" npx playwright test
