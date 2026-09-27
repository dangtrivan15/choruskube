// Runtime spot check for the text/control contrast contract: proves the
// tokens the unit gate (text-control-contrast.test.ts) measured are the ones
// the browser actually resolves, in both themes, on a real dialog. See
// docs/decisions/2026-09-26---01-aa-contrast-text-and-controls.md.
import { test, expect } from "../fixtures";
import { resolveColor, toggleTheme, alphaOf, contrastRatioRgb, ringColorOf } from "../helpers/colors";

function currentBaseURL(testInfo: { project: { use: { baseURL?: string } } }): string {
  return testInfo.project.use.baseURL ?? "http://localhost:23000";
}

// Seeds only the "theme" cookie — see theme-remembered.spec.ts for why a bare
// clearCookies() is unsafe (it can also drop a downstream deployment's session
// cookie and bounce navigation to a login page with no theme script).
async function seedTheme(
  page: import("@playwright/test").Page,
  testInfo: { project: { use: { baseURL?: string } } },
  theme: "light" | "dark",
): Promise<void> {
  await page.context().clearCookies({ name: "theme" });
  await page.context().addCookies([{ name: "theme", value: theme, url: currentBaseURL(testInfo) }]);
}

const MUTED_FOREGROUND_HEX = { light: "#656083", dark: "#908caa" } as const;

test.describe("text/control contrast — create-git-repo dialog", () => {
  for (const theme of ["light", "dark"] as const) {
    test(`${theme}: primary label, muted/destructive ink, input border and focus ring all clear AA`, async ({
      page,
    }, testInfo) => {
      await seedTheme(page, testInfo, theme);
      await page.goto("/runs");
      if (theme === "dark") {
        await expect(page.locator("html")).toHaveClass(/dark/);
      } else {
        await expect(page.locator("html")).not.toHaveClass(/dark/);
      }

      // 1. Open the git-repos surface and the create dialog. Its content is
      // `bg-background`, opaque, so its computed background-color is safe to
      // pass straight to contrastRatioRgb.
      await page.getByRole("link", { name: /software projects/i }).click();
      await page.waitForURL("**/git-repos");
      await page.getByRole("button", { name: /^repositories$/i }).click();
      await page.getByRole("button", { name: /^new repo$/i }).click();
      const heading = page.getByRole("heading", { name: /new git repo/i });
      await expect(heading).toBeVisible();

      const dialog = page.locator('[data-slot="dialog-content"]');
      const dialogBg = await dialog.evaluate((el) => getComputedStyle(el).backgroundColor);

      // The dialog auto-focuses its first field (Repository URL) on open, so
      // the resting `--input` border is read from the next, untouched field.
      // Inputs carry transition-colors: poll until the border has settled.
      const urlInput = page.getByLabel(/repository url/i);
      const branchInput = page.getByLabel(/default branch/i);
      const borderOf = (locator: typeof branchInput) =>
        locator.evaluate((el) => getComputedStyle(el).borderTopColor);

      // 2. Resting input border vs the dialog background.
      const inputColor = await resolveColor(page, "var(--input)");
      await expect.poll(() => borderOf(branchInput)).toBe(inputColor);
      expect(
        contrastRatioRgb(await borderOf(branchInput), dialogBg),
        `${theme}: resting input border vs dialog background`,
      ).toBeGreaterThanOrEqual(3);

      // 3. Fill the required field (never submit) so **Create** is enabled.
      // Nothing is created, so uniqueName() is not needed.
      await urlInput.fill("https://github.com/e2e-test/text-control-contrast");

      // 4. Tab to the next field, so the ring checked is a keyboard focus ring:
      // alpha 1 (no half-opacity regression) and ≥ 3:1 against the dialog.
      await page.keyboard.press("Tab");
      await expect(branchInput).toBeFocused();
      const ringTokenColor = await resolveColor(page, "var(--ring)");
      await expect.poll(() => borderOf(branchInput)).toBe(ringTokenColor);

      const boxShadow = await branchInput.evaluate((el) => getComputedStyle(el).boxShadow);
      const ringColor = ringColorOf(boxShadow);
      expect(alphaOf(ringColor), `${theme}: focus ring alpha`).toBe(1);
      expect(
        contrastRatioRgb(ringColor, dialogBg),
        `${theme}: focus ring vs dialog background`,
      ).toBeGreaterThanOrEqual(3);
      expect(
        contrastRatioRgb(await borderOf(branchInput), dialogBg),
        `${theme}: focused input border vs dialog background`,
      ).toBeGreaterThanOrEqual(3);

      // 5. Create button label on its own fill.
      const createButton = page.getByRole("button", { name: /^create$/i });
      await expect(createButton).toBeEnabled();
      const createColor = await createButton.evaluate((el) => getComputedStyle(el).color);
      const createBg = await createButton.evaluate((el) => getComputedStyle(el).backgroundColor);
      expect(
        contrastRatioRgb(createColor, createBg),
        `${theme}: Create button label vs its fill`,
      ).toBeGreaterThanOrEqual(4.5);

      // 6. Dialog description (muted helper text) vs the dialog background.
      const description = page.getByText(
        /register a repository with its build and test configuration/i,
      );
      const descriptionColor = await description.evaluate((el) => getComputedStyle(el).color);
      expect(
        contrastRatioRgb(descriptionColor, dialogBg),
        `${theme}: dialog description vs dialog background`,
      ).toBeGreaterThanOrEqual(4.5);

      // 7. Required-field asterisk (text-destructive) vs the dialog background.
      const asterisk = page.locator('label[for="repo-url"] span.text-destructive');
      const asteriskColor = await asterisk.evaluate((el) => getComputedStyle(el).color);
      expect(
        contrastRatioRgb(asteriskColor, dialogBg),
        `${theme}: required-field asterisk vs dialog background`,
      ).toBeGreaterThanOrEqual(4.5);

      // 8. The muted-foreground token resolves to exactly this theme's
      // AA-deepened (light) or unchanged (dark) value — proves the composed
      // stylesheet, not a stale cached one, is what the browser resolved.
      expect(await resolveColor(page, "var(--muted-foreground)")).toBe(
        await resolveColor(page, MUTED_FOREGROUND_HEX[theme]),
      );
    });
  }

  test("toggling theme with the dialog closed changes the resolved muted-foreground token", async ({
    page,
  }, testInfo) => {
    await seedTheme(page, testInfo, "light");
    await page.goto("/runs");
    await expect(page.locator("html")).not.toHaveClass(/dark/);

    const before = await resolveColor(page, "var(--muted-foreground)");
    await toggleTheme(page);
    await expect(page.locator("html")).toHaveClass(/dark/);
    const after = await resolveColor(page, "var(--muted-foreground)");

    expect(after).not.toBe(before);
  });
});
