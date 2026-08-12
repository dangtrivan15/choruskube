import { defineConfig, devices } from "@playwright/test";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const AUTH_FILE = join(__dirname, "e2e/.auth/user.json");

/**
 * Playwright E2E configuration for ChorusKube Web UI.
 *
 * Expects the full Docker Compose stack to be running in e2e mode:
 *   - Web UI on port 23000
 *   - API server on port 28080
 *   - Temporal, PostgreSQL, object storage
 *
 * Core runs single-tenant with no OIDC provider — the setup project
 * snapshots auth-free storage state for reuse by all tests.
 *
 * (Override base URL via E2E_BASE_URL if needed.)
 *
 * Every spec that creates a named resource (Run/Epic/Task title, GitRepo,
 * RepoGroup, ...) namespaces it via `uniqueName` (see e2e/helpers/api-client.ts)
 * so concurrent workers never collide over the one shared backend stack.
 *
 * Run with: npx playwright test
 * Parallel: E2E_WORKERS=4 npx playwright test
 */
export default defineConfig({
  testDir: "./e2e/specs",
  outputDir: "./e2e/test-results",

  /* Workers driven by E2E_WORKERS; omitting it reproduces today's serial
   * behavior exactly (local dev, and any caller that doesn't opt in). */
  workers: process.env.E2E_WORKERS ? Number(process.env.E2E_WORKERS) : 1,
  fullyParallel: true,

  /* Fail the build on any test.only left in source code */
  forbidOnly: !!process.env.CI,

  /* Retry failed tests once in CI */
  retries: process.env.CI ? 1 : 0,

  /* The JSON reporter is what run-all-tests reads to name failing specs in the test
   * report index; the HTML report is packed into an archive and is not parsed. Both are
   * CI-only, matching the existing split — a local run keeps the plain list reporter. */
  reporter: process.env.CI
    ? [
        ["html", { outputFolder: "./e2e/playwright-report" }],
        ["json", { outputFile: "./e2e/playwright-report/results.json" }],
        ["list"],
      ]
    : [["list"]],

  use: {
    /* Base URL for the web UI served by Docker Compose */
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:23000",

    /* Collect trace on first retry */
    trace: "on-first-retry",

    /* Screenshot on failure */
    screenshot: "only-on-failure",
  },

  projects: [
    { name: "setup", testMatch: /auth\.setup\.ts/ },
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"], storageState: AUTH_FILE },
      dependencies: ["setup"],
    },
  ],

  /* Global timeout per test */
  timeout: 60_000,

  /* Expect timeout */
  expect: {
    timeout: 10_000,
  },
});
