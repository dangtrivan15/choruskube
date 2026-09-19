// Regression-locks the chosen-preference half of the theme contract: a saved
// preference paints on the first frame of a fresh tab with no flash, survives
// a reload, and is shared by a second tab in the same context — see
// src/lib/themeStore.ts (shared store) and src/lib/theme.ts / index.html (the
// unchanged first-paint contract this spec re-proves still holds through the
// store). theme-first-visit.spec.ts covers the no-preference OS-default path;
// this spec starts from an already-chosen preference.
import { test, expect } from "../fixtures";

function currentBaseURL(testInfo: { project: { use: { baseURL?: string } } }): string {
  return testInfo.project.use.baseURL ?? "http://localhost:23000";
}

// Clears only the "theme" cookie — see theme-first-visit.spec.ts for why a
// bare clearCookies() is unsafe (it can also drop a downstream deployment's
// session cookie and bounce navigation to a login page with no theme script).
async function clearThemeCookie(page: import("@playwright/test").Page): Promise<void> {
  await page.context().clearCookies({ name: "theme" });
}

test.describe("remembered theme — chosen preference", () => {
  test("a saved dark preference paints dark on the first frame of a fresh tab (no flash)", async ({
    page,
  }, testInfo) => {
    await clearThemeCookie(page);
    await page.context().addCookies([
      { name: "theme", value: "dark", url: currentBaseURL(testInfo) },
    ]);
    await page.goto("/");
    await expect(page.locator("html")).toHaveClass(/dark/);
  });

  test("toggling in-app and reloading keeps the chosen variant", async ({ page }) => {
    await clearThemeCookie(page);
    await page.goto("/");
    await expect(page.locator("html")).not.toHaveClass(/dark/);

    await page.getByRole("button", { name: "Toggle theme" }).click();
    await expect(page.locator("html")).toHaveClass(/dark/);

    await page.reload();
    await expect(page.locator("html")).toHaveClass(/dark/);
  });

  test("a chosen preference is shared by a second tab in the same context", async ({
    page,
    context,
  }, testInfo) => {
    await clearThemeCookie(page);
    await page.context().addCookies([
      { name: "theme", value: "dark", url: currentBaseURL(testInfo) },
    ]);
    await page.goto("/");
    await expect(page.locator("html")).toHaveClass(/dark/);

    const second = await context.newPage();
    await second.goto("/");
    await expect(second.locator("html")).toHaveClass(/dark/);
    await second.close();
  });
});
