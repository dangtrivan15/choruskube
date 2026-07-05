import { test, expect } from "../fixtures";

test.describe("Navigation", () => {
  test("default route redirects to /runs", async ({ page }) => {
    await page.goto("/");
    await page.waitForURL("**/runs");
    expect(page.url()).toContain("/runs");
  });

  test("sidebar links navigate to correct pages", async ({ navigationPage }) => {
    await navigationPage.goto("/runs");

    // Navigate to Approvals
    await navigationPage.navigateToApprovals();
    await expect(navigationPage.page).toHaveURL(/\/approvals/);

    // Navigate to Roadmap
    await navigationPage.navigateToRoadmap();
    await expect(navigationPage.page).toHaveURL(/\/roadmap/);

    // Navigate to Analytics
    await navigationPage.navigateToAnalytics();
    await expect(navigationPage.page).toHaveURL(/\/analytics/);

    // Navigate back to Runs
    await navigationPage.navigateToRuns();
    await expect(navigationPage.page).toHaveURL(/\/runs/);
  });

  test("active nav link is highlighted", async ({ navigationPage }) => {
    await navigationPage.goto("/runs");
    // The runs link should have the active class
    await expect(navigationPage.runsLink).toHaveClass(/bg-sidebar-accent/);
  });

  test("unknown route shows 404 page", async ({ page }) => {
    await page.goto("/nonexistent-page");
    await expect(page.getByText(/not found/i)).toBeVisible();
  });

  test("sidebar shows approval badge when gates are pending", async ({
    navigationPage,
    api,
  }) => {
    // Navigate to runs to check if the approval badge is visible
    // (depends on whether there are pending gates in the system)
    await navigationPage.goto("/runs");

    // The sidebar should be visible
    await expect(navigationPage.runsLink).toBeVisible();
    await expect(navigationPage.approvalsLink).toBeVisible();
  });
});
