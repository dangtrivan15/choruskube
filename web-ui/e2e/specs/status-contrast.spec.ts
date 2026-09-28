// Browser-side WCAG AA cross-check for status words, badges, counters and chart
// labels (docs/decisions/2026-09-27---01-status-ink-labels-and-contrast-gate.md):
// the unit gate (status-contrast.test.ts) proves the palette and recipes in the
// abstract; this spec proves the rendered DOM matches, in both themes.
import type { Locator, Page } from "@playwright/test";
import { test, expect } from "../fixtures";
import { uniqueName } from "../helpers/api-client";
import { toggleTheme } from "../helpers/colors";
import {
  textContrast,
  backgroundAlpha,
  pseudoBackground,
  pseudoWidth,
  resolveHex,
  resolveTint,
  toRgba,
  TEXT_CONTRAST_MIN,
} from "../helpers/contrast";
import { RoadmapTimelinePage } from "../pages/roadmap-timeline.page";
import type { RunTrendResponse, BottleneckResponse } from "../../src/lib/types";

type Theme = "light" | "dark";

/**
 * Runs `check` in light, then dark, then restores light. The theme is a cookie
 * that survives navigation, so the starting theme is forced rather than assumed.
 */
async function forBothThemes(page: Page, check: (theme: Theme) => Promise<void>) {
  const html = page.locator("html");
  if (await html.evaluate((el) => el.classList.contains("dark"))) {
    await toggleTheme(page);
  }
  await expect(html).not.toHaveClass(/dark/);
  await check("light");
  await toggleTheme(page);
  await expect(html).toHaveClass(/dark/);
  await check("dark");
  await toggleTheme(page);
  await expect(html).not.toHaveClass(/dark/);
}

// Every read below is polled: `Badge` carries `transition-all` and theme toggles
// are not transition-suppressed, so a one-shot read can sample a half-faded color.
async function expectReadable(locator: Locator) {
  await expect.poll(() => textContrast(locator)).toBeGreaterThanOrEqual(TEXT_CONTRAST_MIN);
}

async function expectOpaque(locator: Locator) {
  await expect.poll(() => backgroundAlpha(locator)).toBe(1);
}

/** The badge recipe's `::before` dot renders at a real size in the tone's color. */
async function expectToneDot(locator: Locator, tone: string) {
  const expected = await resolveHex(locator.page(), `var(--status-${tone})`);
  await expect.poll(() => pseudoBackground(locator, "::before")).toBe(expected);
  expect(await pseudoWidth(locator, "::before")).toBeGreaterThan(0);
}

test.describe("status-contrast: run surfaces", () => {
  test("run header and run-list badges, the DAG status word, and a failed node's error block clear 4.5:1", async ({
    runListPage,
    runMonitorPage,
    api,
  }) => {
    test.slow(); // the failure is driven by a node timeout
    const template = await api.getTemplateByName("e2e-failure-pipeline");
    const run = await api.startRun({
      graphTemplateId: template.id,
      name: uniqueName("e2e-status-contrast-run"),
    });

    try {
      // The failing node times out, so the node is "failed" (with an error
      // message) and the run parks in "awaiting_retry" — the warning tone.
      await api.waitForNodeStatus(run.id, "failing_step", ["failed"], 120_000);
      await api.waitForRunStatus(run.id, ["awaiting_retry"], 30_000);

      await runMonitorPage.goto(run.id);
      await runMonitorPage.selectNode("failing_step");
      await expect(runMonitorPage.detailNodeError).toBeVisible();
      const dagStatusWord = runMonitorPage.page.locator(
        '[data-testid="dag-node"][data-label="failing_step"] span.capitalize',
      );

      await forBothThemes(runMonitorPage.page, async () => {
        await expectReadable(runMonitorPage.runStatus);
        await expectOpaque(runMonitorPage.runStatus);
        await expectToneDot(runMonitorPage.runStatus, "warning");
        await expectReadable(dagStatusWord);
        await expectReadable(runMonitorPage.detailNodeError.locator("h4"));
        await expectReadable(runMonitorPage.detailNodeError.locator("pre"));
      });

      await runListPage.goto();
      await runListPage.waitForTableLoad();
      const listBadge = runListPage.page
        .locator(`[data-run-id="${run.id}"]`)
        .getByText("awaiting retry", { exact: true });
      await expect(listBadge).toBeVisible();

      await forBothThemes(runListPage.page, async (theme) => {
        await expectReadable(listBadge);
        await expectToneDot(listBadge, "warning");

        // Gate-vs-browser: the rendered tint equals the one the unit gate computes.
        const [er, eg, eb] = await toRgba(
          runListPage.page,
          resolveTint(theme === "dark" ? ".dark" : ":root", "status-warning", "tint"),
        );
        await expect
          .poll(async () => {
            const bg = await listBadge.evaluate((el) => getComputedStyle(el).backgroundColor);
            const [r, g, b, a] = await toRgba(runListPage.page, bg);
            return a === 1 ? Math.max(Math.abs(r - er), Math.abs(g - eg), Math.abs(b - eb)) : Infinity;
          })
          .toBeLessThanOrEqual(1);
      });
    } finally {
      await api.cancelRun(run.id).catch(() => {});
    }
  });
});

test.describe("status-contrast: roadmap surfaces", () => {
  test("priority, level, readiness and milestone chips, the timeline preview and the graph legend clear 4.5:1", async ({
    roadmapGraphPage,
    api,
    workerRepo,
    page,
  }) => {
    test.slow(); // three pages, each measured in both themes
    const epic = await api.createEpic({
      title: uniqueName("E2E Contrast Epic"),
      description: "desc",
      softwareProjectId: workerRepo.gitRepo.id,
      priority: "high",
    });
    const milestone = await api.createMilestone({
      name: uniqueName("E2E Contrast Milestone"),
      softwareProjectId: workerRepo.gitRepo.id,
    });

    try {
      await api.assignEpicToMilestone(epic.id, milestone.id);
      const blockingStory = await api.createStory(epic.id, {
        title: uniqueName("Contrast Blocking Story"),
        description: "desc",
      });
      const blockedStory = await api.createStory(epic.id, {
        title: uniqueName("Contrast Blocked Story"),
        description: "desc",
        priority: "medium",
      });
      await api.createDependency({
        blockingItemType: "story",
        blockingItemId: blockingStory.id,
        blockedItemType: "story",
        blockedItemId: blockedStory.id,
      });

      // Epic detail: level, priority and milestone chips.
      await page.goto(`/roadmap/epics/${epic.id}`);
      const milestoneBadge = page.getByTestId("epic-detail-milestone-badge");
      await expect(milestoneBadge).toBeVisible({ timeout: 15_000 });
      await forBothThemes(page, async () => {
        for (const chip of [
          page.getByTestId("level-badge-epic"),
          page.getByTestId("epic-detail-priority-badge"),
          milestoneBadge,
        ]) {
          await expectReadable(chip);
          await expectOpaque(chip);
        }
      });

      // Graph: the blocked Story's detail-panel chips, its node, and the legend.
      await roadmapGraphPage.goto(epic.id);
      await roadmapGraphPage.selectNode(blockedStory.title);
      const readinessBadge = roadmapGraphPage.detailPanel.getByTestId("roadmap-detail-readiness-badge");
      const node = roadmapGraphPage.nodeByLabel(blockedStory.title);
      const legendLabels = roadmapGraphPage.legend.locator("span");
      await expect(legendLabels).toHaveCount(4);
      await forBothThemes(roadmapGraphPage.page, async () => {
        await expectReadable(roadmapGraphPage.detailPanel.getByTestId("roadmap-detail-priority-badge"));
        await expectReadable(readinessBadge);
        await expectToneDot(readinessBadge, "warning");
        await expectReadable(node.locator("span.capitalize"));
        await expectReadable(node.getByTestId("roadmap-graph-node-blocked-badge"));
        for (let i = 0; i < 4; i++) {
          await expectReadable(legendLabels.nth(i));
        }
      });

      // Timeline hover preview: inverted tooltip, stage word and readiness chip.
      const timelinePage = new RoadmapTimelinePage(page);
      await timelinePage.goto();
      const marker = timelinePage.markerByLabel(blockedStory.title);
      await expect(marker).toBeVisible();
      await forBothThemes(page, async () => {
        // The theme toggle click moves the pointer off the marker, closing the preview. The
        // contrast reads run inside the same re-hover retry as the open check: an org-wide
        // refetch from a concurrent worker's mutation can unmount/remount the preview between
        // the open check and a later read, same as it can reposition the marker mid-hover.
        await timelinePage.hoverToRevealPreview(marker, [blockedStory.title], async () => {
          await expectReadable(timelinePage.itemPreview.getByTestId("roadmap-timeline-item-preview-stage"));
          await expectReadable(timelinePage.itemPreview.getByTitle("Blocked by an unfinished dependency"));
        });
      });
    } finally {
      await api.deleteEpic(epic.id).catch(() => {});
      await api.deleteMilestone(milestone.id).catch(() => {});
    }
  });
});

test.describe("status-contrast: analytics chart labels", () => {
  const TREND: RunTrendResponse = {
    points: [{ date: "2026-01-05", total: 4, completed: 4, failed: 0 }],
  };
  const BOTTLENECKS: BottleneckResponse = {
    bottlenecks: [{ label: "build", avgDurationSeconds: 45, p50DurationSeconds: 40, p95DurationSeconds: 120, sampleSize: 12 }],
  };

  test("axis ticks, legend labels, the tooltip and usage-quota chips clear 4.5:1", async ({ analyticsPage, page }) => {
    await page.route(/\/api\/v1\/analytics\/runs\?/, (route) =>
      route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(TREND) }),
    );
    await page.route(/\/api\/v1\/analytics\/bottlenecks\?/, (route) =>
      route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(BOTTLENECKS) }),
    );
    // 85% and 95% of their limits: one "Warning" and one "Critical" quota chip.
    await page.route(/\/organizations\/[^/]+\/usage/, (route) =>
      route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          organizationId: "system",
          concurrentRuns: { current: 1, limit: 10 },
          repos: { current: 1, limit: 10 },
          monthlyRuns: { current: 85, limit: 100, periodStart: "2026-01-01" },
          monthlyNodeExecutions: { current: 95, limit: 100, periodStart: "2026-01-01" },
        }),
      }),
    );

    await analyticsPage.goto();
    await expect(analyticsPage.runTrendChart.locator('[aria-label="Total legend icon"]')).toBeVisible({
      timeout: 15_000,
    });
    await expect(analyticsPage.quotaChips).toHaveCount(2);
    const tickCount = await analyticsPage.axisTickLabels.count();
    expect(tickCount).toBeGreaterThan(0);
    const legendTexts = analyticsPage.runTrendChart.locator(".recharts-legend-item-text");
    const legendCount = await legendTexts.count();
    expect(legendCount).toBeGreaterThan(0);

    await forBothThemes(page, async () => {
      for (let i = 0; i < tickCount; i++) {
        await expectReadable(analyticsPage.axisTickLabels.nth(i));
      }
      for (let i = 0; i < legendCount; i++) {
        await expectReadable(legendTexts.nth(i));
      }

      const tooltipWrapper = await analyticsPage.hoverDataPoint(analyticsPage.runTrendChart);
      const tooltip = tooltipWrapper.locator(".recharts-default-tooltip");
      await expectOpaque(tooltip);
      await expectReadable(tooltip.locator(".recharts-tooltip-label"));
      const items = tooltip.locator(".recharts-tooltip-item");
      expect(await items.count()).toBeGreaterThan(0);
      for (let i = 0; i < (await items.count()); i++) {
        await expectReadable(items.nth(i));
      }

      for (const [i, tone] of [[0, "warning"], [1, "error"]] as const) {
        const chip = analyticsPage.quotaChips.nth(i);
        await expectReadable(chip);
        await expectOpaque(chip);
        await expectToneDot(chip, tone);
      }
    });
  });
});

test.describe("status-contrast: layout counters", () => {
  test("sidebar approvals count and activity-feed unread count clear 4.5:1 on opaque tints", async ({
    navigationPage,
    api,
  }) => {
    test.slow(); // waits for a real gate to open
    const page = navigationPage.page;
    // The unread count tallies only events that arrive after the app mounts, so
    // load it before the gate opens and never navigate away.
    await navigationPage.goto("/runs");
    await expect(page.getByRole("button", { name: /^Activity feed/ })).toBeVisible({ timeout: 15_000 });

    const template = await api.getTemplateByName("e2e-human-gate");
    const run = await api.startRun({
      graphTemplateId: template.id,
      name: uniqueName("e2e-layout-counters"),
    });

    try {
      await api.waitForNodeStatus(run.id, "review_gate", ["awaiting_human"], 60_000);
      const unreadBadge = page.getByTestId("activity-feed-unread-count");
      await expect(navigationPage.approvalsBadge).toBeVisible({ timeout: 15_000 });
      await expect(unreadBadge).toBeVisible({ timeout: 15_000 });

      await forBothThemes(page, async () => {
        for (const badge of [navigationPage.approvalsBadge, unreadBadge]) {
          await expectReadable(badge);
          await expectOpaque(badge);
        }
      });
    } finally {
      await api.cancelRun(run.id).catch(() => {});
    }
  });
});
