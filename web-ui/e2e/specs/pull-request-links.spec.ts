// Verifies that the PR list on the Run page does not cause horizontal scroll
// on a mobile viewport. Uses page.route() to mock /api/v1/runs/<id> with
// long-titled PRs — the FIRST usage of route-mocking in this repo. Pattern
// chosen because the alternative (extending TestApiClient with internal-auth
// PR creation) is a much larger infra change for what is a CSS-only fix.
import { test, expect } from "../fixtures";

const FAKE_RUN_ID = "00000000-0000-0000-0000-000000000abc";

const LONG_TITLE =
  "feat(api-server): refactor the very long pull request title to verify wrapping behavior at narrow viewport widths and prevent horizontal overflow";

const FAKE_RUN = {
  id: FAKE_RUN_ID,
  graphTemplateId: "tpl-1",
  templateName: "demo",
  name: "Layout fixture",
  status: "running",
  externalRunId: "ext-1",
  graphVersion: 1,
  graphSnapshot: null,
  startedAt: null,
  completedAt: null,
  createdAt: new Date().toISOString(),
  nodeExecutions: [],
  pullRequests: [
    {
      id: "pr-1",
      workflowRunId: FAKE_RUN_ID,
      gitRepoId: "r1",
      nodeExecutionId: null,
      prUrl: "https://example.invalid/pr/1",
      prNumber: 1,
      title: LONG_TITLE,
      repoName: "backend-api",
      repoUrl: "https://example.invalid",
      createdAt: new Date().toISOString(),
    },
    {
      id: "pr-2",
      workflowRunId: FAKE_RUN_ID,
      gitRepoId: "r2",
      nodeExecutionId: null,
      prUrl: "https://example.invalid/pr/2",
      prNumber: 2,
      title: "feat: short",
      repoName: "frontend-app",
      repoUrl: "https://example.invalid",
      createdAt: new Date().toISOString(),
    },
    {
      id: "pr-3",
      workflowRunId: FAKE_RUN_ID,
      gitRepoId: "r3",
      nodeExecutionId: null,
      prUrl: "https://example.invalid/pr/3",
      prNumber: 3,
      title: "fix: deps",
      repoName: "very-long-org-platform-svc",
      repoUrl: "https://example.invalid",
      createdAt: new Date().toISOString(),
    },
  ],
};

test.describe("PullRequestLinks layout", () => {
  test.beforeEach(async ({ page }) => {
    await page.route(`**/api/v1/runs/${FAKE_RUN_ID}`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(FAKE_RUN),
      });
    });
    // Mute unrelated calls (logs, review-history, ws bootstrap) — let them pass.
  });

  test("no horizontal scroll on mobile viewport", async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 800 });
    await page.goto(`/runs/${FAKE_RUN_ID}`);

    const wrapper = page.getByTestId("pull-request-links");
    await expect(wrapper).toBeVisible();

    // Assert the document does not exceed the viewport horizontally.
    const overflow = await page.evaluate(() => {
      const el = document.documentElement;
      return el.scrollWidth - el.clientWidth;
    });
    // 1 px tolerance for sub-pixel rounding on some platforms.
    expect(overflow).toBeLessThanOrEqual(1);
  });

  test("pills stack vertically at 375 px; all visible in sidebar at 1280 px", async ({
    page,
  }) => {
    await page.setViewportSize({ width: 375, height: 800 });
    await page.goto(`/runs/${FAKE_RUN_ID}`);

    const links = page.getByTestId("pull-request-link");
    await expect(links).toHaveCount(3);

    const mobileBoxes = await links.evaluateAll((els) =>
      els.map((el) => el.getBoundingClientRect().top),
    );
    // Distinct tops → stacked vertically on mobile.
    expect(new Set(mobileBoxes).size).toBe(3);

    await page.setViewportSize({ width: 1280, height: 800 });
    // Brief settle to let CSS recalc after viewport change.
    await page.waitForTimeout(100);
    // At 1280 px, PR links move into the always-visible sidebar (RunMetaPanel).
    // The sidebar is 320 px wide, so pills may wrap — but all three must remain
    // accessible and visible.
    await expect(links).toHaveCount(3);
    for (const link of await links.all()) {
      await expect(link).toBeVisible();
    }
  });
});
