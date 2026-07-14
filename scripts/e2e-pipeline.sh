#!/usr/bin/env bash
# scripts/e2e-pipeline.sh — seed-verify + Playwright run against the live stack.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"
bash "${REPO_ROOT}/e2e/setup-test-data.sh"
cd "${REPO_ROOT}/web-ui"

# Playwright runs from the host against the composed stack, so the host needs
# the web-ui deps present. `.github/workflows/e2e.yml` installs these as a
# separate CI step before invoking this script; standalone/local callers
# (e.g. `./scripts/e2e.sh` run directly) don't get that step for free, so
# install here too. npm ci is idempotent — a no-op if deps are already
# present and current.
echo "--- Installing web-ui dependencies ---"
npm ci

CI="${CI:-}" npx playwright test
