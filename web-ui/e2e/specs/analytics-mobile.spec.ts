// Verifies that the Analytics page does not introduce horizontal scroll
// on a mobile viewport. The bug being guarded: the Templates and
// Bottlenecks cards live in a `lg:grid-cols-2` grid, and CSS grid items
// default to `min-width: auto`, which let the table's intrinsic width and
// Recharts' reported width inflate the grid track and push <main> sideways.
// Fix is `min-w-0` on those grid items — see AnalyticsPage.tsx.
//
// Pattern mirrors `pull-request-links.spec.ts`: 375 × 812 viewport,
// scrollWidth ≤ innerWidth + 1 px tolerance for sub-pixel rounding.
import { test, expect } from "../fixtures";

test.describe("Analytics page — mobile viewport", () => {
  test.use({ viewport: { width: 375, height: 812 } });

  test("does not introduce horizontal scroll on the page", async ({ page }) => {
    await page.goto("/analytics");
    await page.waitForLoadState("networkidle");
    await expect(
      page.getByRole("heading", { name: "Analytics" }),
    ).toBeVisible();

    // Assert no page-level horizontal overflow caused by Bottleneck/Template cards.
    const overflow = await page.evaluate(() => ({
      scrollWidth: document.documentElement.scrollWidth,
      innerWidth: window.innerWidth,
    }));
    expect(overflow.scrollWidth).toBeLessThanOrEqual(overflow.innerWidth + 1);
  });
});
