#!/usr/bin/env bash
# e2e/setup-test-data.sh — verifies E2eTestDataSeeder provisioned the e2e
# templates. The seeder (api-server @Profile("e2e")) does the actual seeding at
# boot; this is a fail-loud guard so a seeder rename surfaces here, not mid-spec.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=/dev/null
source "${SCRIPT_DIR}/lib.sh"

TEMPLATES=(e2e-linear-pipeline e2e-parallel-fanout e2e-human-gate e2e-conditional-routing e2e-retry-loop)
for t in "${TEMPLATES[@]}"; do
  if find_template "$t"; then
    echo "ok: template $t present"
  else
    echo "ERROR: template $t not seeded — check E2eTestDataSeeder.java" >&2
    exit 1
  fi
done
echo "All e2e templates verified."
