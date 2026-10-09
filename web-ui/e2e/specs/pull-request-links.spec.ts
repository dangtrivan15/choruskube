// Verifies PR link layout on the Run page: stacked inside the phone "Run info"
// sheet with no horizontal scroll, and visible inside the always-on desktop
// run summary. Uses page.route() to mock /api/v1/runs/<id> with long-titled
// PRs — the FIRST usage of route-mocking in this repo. Pattern chosen because
// the alternative (extending TestApiClient with internal-auth PR creation) is
// a much larger infra change for what is a CSS-only fix.
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

  test("pills stack inside the Run info sheet at 375 px, with no horizontal scroll", async ({
    page,
  }) => {
    await page.setViewportSize({ width: 375, height: 800 });
    await page.goto(`/runs/${FAKE_RUN_ID}`);

    // PR pills live only in the "Run info" sheet on phones — the one-line mobile
    // bar has no room for them.
    await page.getByTestId("run-info-open-button").click();
    const sheet = page.getByTestId("run-info-sheet");
    await expect(sheet).toBeVisible();

    const links = sheet.getByTestId("pull-request-link");
    await expect(links).toHaveCount(3);

    const tops = await links.evaluateAll((els) => els.map((el) => el.getBoundingClientRect().top));
    // Distinct tops → stacked vertically, not overlapping.
    expect(new Set(tops).size).toBe(3);

    // Assert the document does not exceed the viewport horizontally.
    // 1 px tolerance for sub-pixel rounding on some platforms.
    const overflow = await page.evaluate(() => {
      const el = document.documentElement;
      return el.scrollWidth - el.clientWidth;
    });
    expect(overflow).toBeLessThanOrEqual(1);
  });

  test("all PR links are visible in the run summary at 1280 px", async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    await page.goto(`/runs/${FAKE_RUN_ID}`);

    const summary = page.getByTestId("run-summary");
    await expect(summary).toBeVisible();

    const links = summary.getByTestId("pull-request-link");
    await expect(links).toHaveCount(3);
    for (const link of await links.all()) {
      await expect(link).toBeVisible();
    }
  });
});
