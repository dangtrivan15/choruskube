// Verifies the first-visit theme default follows the OS color-scheme
// preference (no flash to the wrong theme before hydration), while a saved
// preference always wins over the OS — see src/lib/theme.ts and the
// pre-paint FOUC script in index.html, which must resolve identically.
import { test, expect } from "../fixtures";

function currentBaseURL(testInfo: { project: { use: { baseURL?: string } } }): string {
  return testInfo.project.use.baseURL ?? "http://localhost:23000";
}

// Clears only the "theme" cookie. A bare context().clearCookies() also wipes
// any session cookie a downstream deployment's storageState relies on,
// bouncing the next goto("/") to an identity provider's login page — an
// origin with no pre-paint theme script — instead of the app.
async function clearThemeCookie(page: import("@playwright/test").Page): Promise<void> {
  await page.context().clearCookies({ name: "theme" });
}

test.describe("first-visit theme — OS color-scheme", () => {
  test.use({ colorScheme: "dark" });

  test("no saved preference under OS-dark opens dark on the first frame", async ({
    page,
  }) => {
    await clearThemeCookie(page);
    await page.goto("/");
    await expect(page.locator("html")).toHaveClass(/dark/);
  });

  test("an invalid saved cookie under OS-dark resolves from the OS, not a flash to light", async ({
    page,
  }, testInfo) => {
    await clearThemeCookie(page);
    await page.context().addCookies([
      { name: "theme", value: "ocean", url: currentBaseURL(testInfo) },
    ]);
    await page.goto("/");
    await expect(page.locator("html")).toHaveClass(/dark/);
  });

  test("a saved light preference wins over an OS-dark setting", async ({
    page,
  }, testInfo) => {
    await clearThemeCookie(page);
    await page.context().addCookies([
      { name: "theme", value: "light", url: currentBaseURL(testInfo) },
    ]);
    await page.goto("/");
    await expect(page.locator("html")).not.toHaveClass(/dark/);
  });

  test("blocked cookie access still falls through to the OS check", async ({
    page,
  }) => {
    await clearThemeCookie(page);
    await page.addInitScript(() => {
      Object.defineProperty(document, "cookie", {
        get() {
          throw new Error("blocked");
        },
        set() {
          /* no-op */
        },
      });
    });
    await page.goto("/");
    await expect(page.locator("html")).toHaveClass(/dark/);
  });
});

test.describe("first-visit theme — OS color-scheme light", () => {
  test.use({ colorScheme: "light" });

  test("no saved preference under OS-light opens light", async ({ page }) => {
    await clearThemeCookie(page);
    await page.goto("/");
    await expect(page.locator("html")).not.toHaveClass(/dark/);
  });
});
