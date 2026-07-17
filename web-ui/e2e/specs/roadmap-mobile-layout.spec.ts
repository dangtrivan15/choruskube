// Verifies that the Epic detail view does not cause horizontal scroll on a
// mobile viewport. Mirrors pull-request-links.spec.ts's page.route() pattern.
// Pinned to a hermetic mock so the test does not depend on seed data or login
// state.
//
// All three tests run at 375 px. There is intentionally NO desktop branch:
// with the deliberately-long mock data the chip + pills row sums to ~1055 px
// max-content, while the desktop detail content area is ~640 px — flex-wrap
// is unavoidable, so any "single row at 1280 px" assertion would fail
// deterministically. Tests 1 and 2 cover the actual bug (overflow at 375 px);
// Test 3 covers the reflow invariant. Desktop layout health is covered by the
// existing className-correctness Vitest assertions plus the existing
// EpicDetailPage desktop tests.
import { test, expect } from "../fixtures";

const FAKE_EPIC_ID = "00000000-0000-0000-0000-000000000001";

const LONG_TITLE =
  "feat(api-server): refactor-very-long-branch-name-with-no-spaces-and-an-unbreakable-token-to-verify-wrapping-at-narrow-viewport";
const LONG_PROJECT_NAME =
  "auto-epic-00000000-0000-0000-0000-000000000001-monorepo-backend-frontend";
const LONG_REPO_NAME =
  "an-extremely-long-repository-name-that-must-truncate-not-overflow-on-a-mobile-viewport";

const FAKE_EPIC = {
  id: FAKE_EPIC_ID,
  title: LONG_TITLE,
  description: "A short description.",
  motivation: null,
  status: "backlog",
  progress: { totalTasks: 0, doneTasks: 0 },
  softwareProject: {
    id: "sp-long",
    type: "repo_group",
    name: LONG_PROJECT_NAME,
  },
  repos: [
    { id: "r1", url: "https://example.invalid/repo1", name: LONG_REPO_NAME },
    { id: "r2", url: "https://example.invalid/repo2", name: "short-repo" },
  ],
  createdAt: new Date().toISOString(),
  updatedAt: new Date().toISOString(),
};

test.describe("Epic detail mobile layout", () => {
  test.beforeEach(async ({ page }) => {
    await page.route(`**/api/v1/epics/${FAKE_EPIC_ID}`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(FAKE_EPIC),
      });
    });
    await page.route(`**/api/v1/epics/${FAKE_EPIC_ID}/stories`, async (route) => {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify([]),
      });
    });
    await page.setViewportSize({ width: 375, height: 800 });
  });

  test("no horizontal scroll on mobile viewport", async ({ page }) => {
    await page.goto(`/roadmap/epics/${FAKE_EPIC_ID}`);
    await expect(page.getByTestId("epic-detail-title")).toBeVisible();

    // Document-level overflow must be ≤ 1.
    const docOverflow = await page.evaluate(() => {
      const el = document.documentElement;
      return el.scrollWidth - el.clientWidth;
    });
    expect(docOverflow).toBeLessThanOrEqual(1);
  });

  test("title wraps and chip stays within viewport at 375 px", async ({
    page,
  }) => {
    await page.goto(`/roadmap/epics/${FAKE_EPIC_ID}`);
    await expect(page.getByTestId("epic-detail-title")).toBeVisible();

    // Title must wrap to at least 2 lines for the long unbreakable token.
    const titleBox = await page
      .getByTestId("epic-detail-title")
      .evaluate((el) => {
        const r = el.getBoundingClientRect();
        const lh = parseFloat(getComputedStyle(el).lineHeight) || 24;
        return { width: r.width, height: r.height, lineHeight: lh };
      });
    expect(titleBox.width).toBeLessThanOrEqual(375);
    expect(titleBox.height).toBeGreaterThan(titleBox.lineHeight * 1.5);

    // Chip width is asserted on the chip itself (inner span, new test-id),
    // NOT on the wrapper div — the wrapper's width is bounded by the
    // header column anyway, so an assertion against it would pass
    // trivially regardless of internal overflow.
    const chipWidth = await page
      .getByTestId("epic-software-project-chip")
      .evaluate((el) => el.getBoundingClientRect().width);
    expect(chipWidth).toBeLessThanOrEqual(375);
  });

  test("chip and pills reflow onto multiple rows at 375 px", async ({
    page,
  }) => {
    await page.goto(`/roadmap/epics/${FAKE_EPIC_ID}`);
    await expect(page.getByTestId("epic-detail-title")).toBeVisible();

    const chip = page.getByTestId("epic-software-project-chip");
    const pills = page.getByTestId("epic-repo-pill");
    await expect(chip).toBeVisible();
    await expect(pills).toHaveCount(2);

    // Distinct top values across the chip and pills → flex-wrap actually
    // triggered, the items did not collapse on top of each other or
    // overflow horizontally. We assert size > 1, not == specific number,
    // because exactly which pill ends up on which row is implementation-
    // dependent on subpixel widths.
    //
    // No desktop branch. With the deliberately long mock
    // names, chip + pill1 + pill2 max-content sums to ~1055 px, well in
    // excess of the ~640 px detail content area at 1280 px viewport, so
    // any "single row at desktop" assertion would fail deterministically.
    const chipTop = await chip.evaluate(
      (el) => el.getBoundingClientRect().top,
    );
    const pillTops = await pills.evaluateAll((els) =>
      els.map((el) => el.getBoundingClientRect().top),
    );
    const allTops = [chipTop, ...pillTops];
    expect(new Set(allTops).size).toBeGreaterThan(1);
  });
});
