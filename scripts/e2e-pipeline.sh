#!/usr/bin/env bash
# scripts/e2e-pipeline.sh — seed-verify + Playwright run against the live stack.
#
# Reads E2E_WORKERS (optional) and forwards it to `npx playwright test` as
# --workers. Unset by default so a plain local run stays on
# playwright.config.ts's serial fallback; CI sets it (see
# .github/workflows/e2e.yml).
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

PLAYWRIGHT_ARGS=()
[ -n "${E2E_WORKERS:-}" ] && PLAYWRIGHT_ARGS+=("--workers=${E2E_WORKERS}")

# The `+` (not `:-`) form is required: under `set -u`, expanding an empty
# array via "${arr[@]}" on bash < 4.4 (e.g. macOS's stock /bin/bash 3.2, still
# the default for a plain local `E2E_WORKERS`-unset run) throws
# "unbound variable" even though the array itself is legitimately empty.
CI="${CI:-}" npx playwright test "${PLAYWRIGHT_ARGS[@]+"${PLAYWRIGHT_ARGS[@]}"}"
