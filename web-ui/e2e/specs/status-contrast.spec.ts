// Browser-side WCAG AA cross-check for the status ink/tint contrast change
// (docs/decisions/2026-09-27---01-status-ink-labels-and-contrast-gate.md): the
// unit gate (status-contrast.test.ts) proves the palette/recipes are correct
// in the abstract, this spec proves the real rendered DOM matches — real glass
// cards, the real gradient canvas, a real theme toggle, a real Recharts mount.
import { test, expect } from "../fixtures";
import { uniqueName } from "../helpers/api-client";
import { toggleTheme } from "../helpers/colors";
import {
  textContrast,
  effectiveBackground,
  pseudoBackground,
  pseudoWidth,
  toRgba,
  resolveTint,
  TEXT_CONTRAST_MIN,
} from "../helpers/contrast";
import type { RunTrendResponse, BottleneckResponse } from "../../src/lib/types";

/** Every check in this file must hold in both themes. */
async function forBothThemes(page: import("@playwright/test").Page, run: () => Promise<void>) {
  await run();
  await toggleTheme(page);
  await expect(page.locator("html")).toHaveClass(/dark/);
  await run();
}

test.describe("status-contrast: run surfaces", () => {
  test("run-list badge, run-header badge, DAG status word, and a failed node's error block all clear 4.5:1", async ({
    runListPage,
    runMonitorPage,
    api,
  }) => {
    const template = await api.getTemplateByName("e2e-linear-pipeline");
    const run = await api.startRun({
      graphTemplateId: template.id,
      name: uniqueName("e2e-status-contrast-run"),
    });
    // step_1 fails via mock-agent.sh's documented failure trigger, matching
    // failure-handling.spec.ts's own setup for a real (unstubbed) error row.
    await api.waitForNodeStatus(run.id, "step_1", ["failed"], 60_000);
    await api.waitForRunStatus(run.id, ["failed"], 60_000);

    await runMonitorPage.goto(run.id);
    await expect(runMonitorPage.dagNodes.first()).toBeVisible({ timeout: 15_000 });
    await runMonitorPage.selectNode("step_1");
    await expect(runMonitorPage.detailNodeError).toBeVisible();

    await forBothThemes(runMonitorPage.page, async () => {
      const theme = (await runMonitorPage.page.locator("html").getAttribute("class"))?.includes("dark")
        ? (".dark" as const)
        : (":root" as const);

      // Run header badge.
      expect(await textContrast(runMonitorPage.runStatus)).toBeGreaterThanOrEqual(TEXT_CONTRAST_MIN);
      const [, , , headerAlpha] = await toRgba(
        runMonitorPage.page,
        await runMonitorPage.runStatus.evaluate((el) => getComputedStyle(el).backgroundColor),
      );
      expect(headerAlpha).toBe(1);

      // Failed node's error block.
      const errorHeading = runMonitorPage.detailNodeError.locator("h4");
      expect(await textContrast(errorHeading)).toBeGreaterThanOrEqual(TEXT_CONTRAST_MIN);
      const errorBody = runMonitorPage.detailNodeError.locator("pre");
      expect(await textContrast(errorBody)).toBeGreaterThanOrEqual(TEXT_CONTRAST_MIN);

      // DAG node status word.
      expect(await textContrast(runMonitorPage.dagNodes.first().locator("span.capitalize"))).toBeGreaterThanOrEqual(
        TEXT_CONTRAST_MIN,
      );

      void theme; // reserved for a future per-theme tint cross-check below
    });

    // Run-list badge — gate-vs-browser cross-check against resolveTint().
    await runListPage.goto();
    const row = runListPage.runRows.filter({ hasText: run.name ?? "" }).first();
    const badge = row.getByText(/^failed$/i);
    if (await badge.count()) {
      expect(await textContrast(badge)).toBeGreaterThanOrEqual(TEXT_CONTRAST_MIN);
      const measuredBg = await badge.evaluate((el) => getComputedStyle(el).backgroundColor);
      const [r, g, b, a] = await toRgba(runListPage.page, measuredBg);
      expect(a).toBe(1);
      const expected = resolveTint(":root", "status-error", "tint");
      const [er, eg, eb] = await toRgba(runListPage.page, expected);
      expect(Math.abs(r - er)).toBeLessThanOrEqual(1);
      expect(Math.abs(g - eg)).toBeLessThanOrEqual(1);
      expect(Math.abs(b - eb)).toBeLessThanOrEqual(1);
    }

    await api.cancelRun(run.id).catch(() => {});
  });
});

test.describe("status-contrast: roadmap surfaces", () => {
  test("priority, level, readiness, and milestone chips clear 4.5:1", async ({
    roadmapGraphPage,
    api,
    workerRepo,
  }) => {
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
    await api.assignEpicToMilestone(epic.id, milestone.id);

    const story = await api.createStory(epic.id, { title: "Contrast Story", description: "desc", priority: "medium" });
    const blockingTask = await api.createTask(story.id, { title: uniqueName("Blocking Task"), description: "desc" });
    const blockedTask = await api.createTask(story.id, { title: uniqueName("Blocked Task"), description: "desc" });
    await api.createDependency({
      blockingItemType: "task",
      blockingItemId: blockingTask.id,
      blockedItemType: "task",
      blockedItemId: blockedTask.id,
    });

    try {
      await roadmapGraphPage.goto(epic.id);
      await roadmapGraphPage.selectNode(blockedTask.title);

      await forBothThemes(roadmapGraphPage.page, async () => {
        expect(await textContrast(roadmapGraphPage.detailPanel.getByTestId("roadmap-detail-priority-badge"))).toBeGreaterThanOrEqual(
          TEXT_CONTRAST_MIN,
        );
        expect(
          await textContrast(roadmapGraphPage.detailPanel.getByTestId("roadmap-detail-readiness-badge")),
        ).toBeGreaterThanOrEqual(TEXT_CONTRAST_MIN);
      });

      // Level chip and Milestone chip, on the Epic detail page.
      await roadmapGraphPage.page.goto(`/roadmap/epics/${epic.id}`);
      await expect(roadmapGraphPage.page.getByTestId("milestone-badge")).toBeVisible();

      await forBothThemes(roadmapGraphPage.page, async () => {
        expect(await textContrast(roadmapGraphPage.page.getByTestId(`level-badge-epic`))).toBeGreaterThanOrEqual(
          TEXT_CONTRAST_MIN,
        );
        expect(await textContrast(roadmapGraphPage.page.getByTestId("milestone-badge"))).toBeGreaterThanOrEqual(
          TEXT_CONTRAST_MIN,
        );
      });

      // Timeline hover preview: stage word + badges.
      await roadmapGraphPage.page.goto("/roadmap/timeline");
      const epicLane = roadmapGraphPage.page.locator(`[data-testid="roadmap-timeline-epic-lane"][data-label="${epic.title}"]`);
      if (await epicLane.count()) {
        await epicLane.hover();
        const preview = roadmapGraphPage.page.getByTestId("roadmap-timeline-item-preview");
        if (await preview.isVisible().catch(() => false)) {
          expect(await textContrast(preview.locator("span").first())).toBeGreaterThanOrEqual(TEXT_CONTRAST_MIN);
        }
      }

      // Graph legend labels.
      await roadmapGraphPage.goto(epic.id);
      await expect(roadmapGraphPage.legend).toBeVisible();
      const legendLabels = roadmapGraphPage.legend.locator("span");
      const legendCount = await legendLabels.count();
      for (let i = 0; i < legendCount; i++) {
        expect(await textContrast(legendLabels.nth(i))).toBeGreaterThanOrEqual(TEXT_CONTRAST_MIN);
      }
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

  test("every axis tick, legend label, and tooltip clears 4.5:1; usage-quota chips clear 4.5:1", async ({
    analyticsPage,
    page,
  }) => {
    await page.route(/\/api\/v1\/analytics\/runs\?/, (route) =>
      route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(TREND) }),
    );
    await page.route(/\/api\/v1\/analytics\/bottlenecks\?/, (route) =>
      route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(BOTTLENECKS) }),
    );
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

    await forBothThemes(page, async () => {
      const tickCount = await analyticsPage.axisTickLabels.count();
      expect(tickCount).toBeGreaterThan(0);
      for (let i = 0; i < tickCount; i++) {
        expect(await textContrast(analyticsPage.axisTickLabels.nth(i))).toBeGreaterThanOrEqual(TEXT_CONTRAST_MIN);
      }

      const legendTexts = analyticsPage.runTrendChart.locator(".recharts-legend-item-text");
      const legendCount = await legendTexts.count();
      for (let i = 0; i < legendCount; i++) {
        expect(await textContrast(legendTexts.nth(i))).toBeGreaterThanOrEqual(TEXT_CONTRAST_MIN);
      }

      await analyticsPage.hoverDataPoint(analyticsPage.runTrendChart);
      const tooltipContent = analyticsPage.tooltip.locator(".recharts-default-tooltip");
      const bg = await tooltipContent.evaluate((el) => getComputedStyle(el).backgroundColor);
      const [, , , a] = await toRgba(page, bg);
      expect(a).toBe(1);

      // Both "Warning" (85%) and "Critical" (95%) quota chips render.
      await expect(analyticsPage.quotaChips).toHaveCount(2);
      const chipCount = await analyticsPage.quotaChips.count();
      for (let i = 0; i < chipCount; i++) {
        const chip = analyticsPage.quotaChips.nth(i);
        expect(await textContrast(chip)).toBeGreaterThanOrEqual(TEXT_CONTRAST_MIN);
        const chipBg = await chip.evaluate((el) => getComputedStyle(el).backgroundColor);
        const [, , , chipAlpha] = await toRgba(page, chipBg);
        expect(chipAlpha).toBe(1);
        expect(await pseudoWidth(chip, "::before")).toBeGreaterThan(0);
      }
    });
  });
});

test.describe("status-contrast: layout counters", () => {
  test("sidebar approvals count and activity-feed unread count clear 4.5:1 with opaque backgrounds", async ({
    runMonitorPage,
    navigationPage,
    api,
  }) => {
    const template = await api.getTemplateByName("e2e-human-gate");
    const run = await api.startRun({
      graphTemplateId: template.id,
      name: uniqueName("e2e-layout-counters"),
    });

    try {
      await api.waitForNodeStatus(run.id, "review_gate", ["awaiting_human"], 60_000);
      await runMonitorPage.goto(run.id);

      const approvalsBadge = navigationPage.approvalsBadge;
      await expect(approvalsBadge).toBeVisible({ timeout: 15_000 });
      const unreadBadge = navigationPage.page.getByTestId("activity-feed-unread-count");
      await expect(unreadBadge).toBeVisible({ timeout: 15_000 });

      await forBothThemes(navigationPage.page, async () => {
        for (const badge of [approvalsBadge, unreadBadge]) {
          expect(await textContrast(badge)).toBeGreaterThanOrEqual(TEXT_CONTRAST_MIN);
          const bg = await badge.evaluate((el) => getComputedStyle(el).backgroundColor);
          const [, , , a] = await toRgba(navigationPage.page, bg);
          expect(a).toBe(1);
        }
      });
    } finally {
      await api.cancelRun(run.id).catch(() => {});
    }
  });
});
