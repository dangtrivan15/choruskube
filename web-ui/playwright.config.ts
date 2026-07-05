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
 * Run with: npx playwright test
 */
export default defineConfig({
  testDir: "./e2e/specs",
  outputDir: "./e2e/test-results",

  /* Serial execution to avoid DB state conflicts between tests */
  workers: 1,
  fullyParallel: false,

  /* Fail the build on any test.only left in source code */
  forbidOnly: !!process.env.CI,

  /* Retry failed tests once in CI */
  retries: process.env.CI ? 1 : 0,

  /* Reporter */
  reporter: process.env.CI
    ? [["html", { outputFolder: "./e2e/playwright-report" }], ["list"]]
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
