import { test as setup } from "@playwright/test";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);
const AUTH_FILE = join(__dirname, "../.auth/user.json");

/**
 * Single-tenant core has no OIDC provider — every request is scoped to the
 * seeded system org. There is nothing to log into, so "setup" just loads the
 * app once and snapshots the (auth-free) storage state for reuse.
 */
setup("establish auth-free session", async ({ page }) => {
  await page.goto("/");
  await page.waitForURL("**/runs");
  await page.context().storageState({ path: AUTH_FILE });
});
