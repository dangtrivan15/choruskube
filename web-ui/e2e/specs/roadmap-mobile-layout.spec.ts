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
  progress: { totalTasks: 0, doneTasks: 0, startedTasks: 0 },
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

// The Roadmap Board header used to render seven undifferentiated controls in one non-wrapping
// row, so it overflowed a phone viewport by construction and took the whole document into
// horizontal scroll with it. This is the cheapest proof the redesign fixed something real:
// one ticket-type dropdown, one view button per view that exists, one Graph action, one filter —
// in a header that wraps. Pinned to a hermetic mock so it does not depend on seed data.
const BOARD_EPICS = [
  {
    id: "00000000-0000-0000-0000-0000000000b1",
    title: "an-epic-with-a-deliberately-long-unbreakable-title-token-for-the-narrow-viewport",
    description: "desc",
    motivation: null,
    stage: "backlog",
    priority: "high",
    targetDate: null,
    progress: { totalTasks: 3, doneTasks: 1, startedTasks: 1 },
    softwareProject: { id: "sp-b1", type: "git_repo", name: "backend-api" },
    repos: [],
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
    readyItemCount: 1,
    milestone: null,
  },
  {
    id: "00000000-0000-0000-0000-0000000000b2",
    title: "Second board epic",
    description: "desc",
    motivation: null,
    stage: "in_progress",
    priority: "low",
    targetDate: null,
    progress: { totalTasks: 2, doneTasks: 0, startedTasks: 0 },
    softwareProject: { id: "sp-b2", type: "git_repo", name: "web-ui" },
    repos: [],
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
    readyItemCount: 0,
    milestone: null,
  },
];

test.describe("Roadmap Board mobile layout", () => {
  test.beforeEach(async ({ page }) => {
    await page.route("**/api/v1/epics?**", async (route) => {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          content: BOARD_EPICS,
          totalElements: BOARD_EPICS.length,
          totalPages: 1,
          size: 200,
          number: 0,
          first: true,
          last: true,
          empty: false,
        }),
      });
    });
    await page.setViewportSize({ width: 375, height: 800 });
  });

  test("the header does not push the document into horizontal scroll at 375 px", async ({ page }) => {
    await page.goto("/roadmap/board");
    await expect(page.getByTestId("roadmap-board-heading")).toBeVisible();
    await expect(page.getByTestId("roadmap-view-controls")).toBeVisible();

    const docOverflow = await page.evaluate(() => {
      const el = document.documentElement;
      return el.scrollWidth - el.clientWidth;
    });
    expect(docOverflow).toBeLessThanOrEqual(1);
  });

  test("every header control stays inside the viewport, wrapping onto more rows as needed", async ({
    page,
  }) => {
    await page.goto("/roadmap/board");
    await expect(page.getByTestId("roadmap-view-controls")).toBeVisible();

    // Right edges, not widths: a control can be narrow and still overhang if the row it sits on
    // never wrapped. Measured on the controls themselves rather than their wrapper, whose width
    // is bounded by the header column anyway and would pass trivially.
    const rights = await page
      .locator(
        '[data-testid="roadmap-level-select"], [data-testid="roadmap-view-list"], [data-testid="roadmap-view-board"], [data-testid="roadmap-view-timeline"], [data-testid="roadmap-graph-action"], [data-testid="ready-to-start-toggle"]',
      )
      .evaluateAll((els) => els.map((el) => el.getBoundingClientRect().right));

    expect(rights.length).toBeGreaterThan(0);
    for (const right of rights) {
      expect(right).toBeLessThanOrEqual(375);
    }
  });
});
