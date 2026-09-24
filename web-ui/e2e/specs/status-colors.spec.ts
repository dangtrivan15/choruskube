// Regression coverage for the run/node/roadmap status-color contract: the
// five named run states resolve to distinct on-brand colors, the mapping is
// theme-responsive (not a light-mode snapshot baked into the DOM), and the
// roadmap dependency legend's swatch is drawn from the exact color and dash
// pattern the edge it describes uses — see src/lib/statusColors.ts,
// src/lib/dagLayout.ts, and src/lib/roadmapEdgeStyles.ts. Full status-string
// coverage and legend/edge/marker token equality across all four dependency
// kinds are asserted at the unit/component level (statusColors.test.ts,
// theme-tokens.test.ts, roadmapEdgeStyles.test.ts, RoadmapGraphLegend.test.tsx,
// RoadmapCandidateGraph.test.tsx) — this spec proves the same contract holds
// in a real browser against a real run/roadmap render.
import { test, expect } from "../fixtures";
import { uniqueName } from "../helpers/api-client";

async function toggleTheme(page: import("@playwright/test").Page) {
  await page.getByRole("button", { name: "Toggle theme" }).click();
}

test.describe("Run Monitor status colors", () => {
  test("DAG node colors are pairwise distinct across completed / running / pending, and stay distinct and recolor after a theme toggle", async ({
    runMonitorPage,
    api,
  }) => {
    const template = await api.getTemplateByName("e2e-parallel-fanout");
    const run = await api.startRun({
      graphTemplateId: template.id,
      name: uniqueName("e2e-status-colors-fanout"),
    });

    // branch_b runs `mock-agent.sh slow --delay 5` — a 5s window in which
    // "start" and the other two branches have already completed, branch_b is
    // still running, and the fan-in "merge" node is still pending, all in one
    // snapshot (see E2eTestDataSeeder#seedParallelFanout).
    await api.waitForNodeStatus(run.id, "branch_b", ["running"], 15_000);

    await runMonitorPage.goto(run.id);
    await expect(runMonitorPage.dagNodes.first()).toBeVisible({ timeout: 15_000 });

    const completedColor = await runMonitorPage.nodeStatusColor("start");
    const runningColor = await runMonitorPage.nodeStatusColor("branch_b");
    const pendingColor = await runMonitorPage.nodeStatusColor("merge");

    expect(new Set([completedColor, runningColor, pendingColor]).size).toBe(3);

    await toggleTheme(runMonitorPage.page);
    await expect(runMonitorPage.page.locator("html")).toHaveClass(/dark/);

    const darkCompletedColor = await runMonitorPage.nodeStatusColor("start");
    const darkRunningColor = await runMonitorPage.nodeStatusColor("branch_b");
    const darkPendingColor = await runMonitorPage.nodeStatusColor("merge");

    expect(new Set([darkCompletedColor, darkRunningColor, darkPendingColor]).size).toBe(3);
    // Re-resolved from the new theme's tokens, not stale light-mode hexes.
    expect(darkCompletedColor).not.toBe(completedColor);
    expect(darkRunningColor).not.toBe(runningColor);
    expect(darkPendingColor).not.toBe(pendingColor);
  });

  test("awaiting_human renders a color distinct from a completed node's, in both themes", async ({
    runMonitorPage,
    api,
  }) => {
    const template = await api.getTemplateByName("e2e-human-gate");
    const run = await api.startRun({
      graphTemplateId: template.id,
      name: uniqueName("e2e-status-colors-gate"),
    });

    await api.waitForNodeStatus(run.id, "review_gate", ["awaiting_human"], 60_000);

    await runMonitorPage.goto(run.id);
    await expect(runMonitorPage.dagNodes.first()).toBeVisible({ timeout: 15_000 });

    expect(await runMonitorPage.nodeStatusColor("review_gate")).not.toBe(
      await runMonitorPage.nodeStatusColor("draft"),
    );

    await toggleTheme(runMonitorPage.page);
    await expect(runMonitorPage.page.locator("html")).toHaveClass(/dark/);

    expect(await runMonitorPage.nodeStatusColor("review_gate")).not.toBe(
      await runMonitorPage.nodeStatusColor("draft"),
    );
  });
});

test.describe("Roadmap Graph status colors and legend fidelity", () => {
  test("Task nodes render distinct colors for backlog / in_progress / done, in both themes", async ({
    roadmapGraphPage,
    api,
    workerRepo,
  }) => {
    const epic = await api.createEpic({
      title: uniqueName("E2E Status Colors Epic"),
      description: "desc",
      softwareProjectId: workerRepo.gitRepo.id,
    });
    const story = await api.createStory(epic.id, { title: "Status Colors Story", description: "desc" });
    const backlogTask = await api.createTask(story.id, {
      title: uniqueName("Backlog Task"),
      description: "desc",
    });
    const inProgressTask = await api.createTask(story.id, {
      title: uniqueName("In Progress Task"),
      description: "desc",
    });
    const doneTask = await api.createTask(story.id, { title: uniqueName("Done Task"), description: "desc" });

    await api.startTask(inProgressTask.id);

    // Get doneTask to "done" without depending on its Task-triggered run
    // actually completing (mirrors roadmap-graph.spec.ts's "creates a
    // blocking-chain..." test) — completeTask only requires the Task's
    // most recent linked run to be terminal, not specifically "completed".
    const started = await api.startTask(doneTask.id);
    await api.waitForRunStatus(started.latestRunId!, ["running"], 15_000);
    await api.cancelRun(started.latestRunId!);
    await api.completeTask(doneTask.id);

    await roadmapGraphPage.goto(epic.id);

    const backlogColor = await roadmapGraphPage.nodeStatusColor(backlogTask.title);
    const inProgressColor = await roadmapGraphPage.nodeStatusColor(inProgressTask.title);
    const doneColor = await roadmapGraphPage.nodeStatusColor(doneTask.title);

    expect(new Set([backlogColor, inProgressColor, doneColor]).size).toBe(3);

    await toggleTheme(roadmapGraphPage.page);
    await expect(roadmapGraphPage.page.locator("html")).toHaveClass(/dark/);

    const darkBacklogColor = await roadmapGraphPage.nodeStatusColor(backlogTask.title);
    const darkInProgressColor = await roadmapGraphPage.nodeStatusColor(inProgressTask.title);
    const darkDoneColor = await roadmapGraphPage.nodeStatusColor(doneTask.title);

    expect(new Set([darkBacklogColor, darkInProgressColor, darkDoneColor]).size).toBe(3);
    expect(darkBacklogColor).not.toBe(backlogColor);
    expect(darkInProgressColor).not.toBe(inProgressColor);
    expect(darkDoneColor).not.toBe(doneColor);

    // No cleanup: starting inProgressTask/doneTask has moved them out of
    // "backlog", and DefaultEpicService#delete refuses to delete an Epic with
    // any started descendant Task (see roadmap-graph.spec.ts's blocking-chain
    // test) — the uniqueName() titles keep this fixture from colliding with
    // concurrent runs.
  });

  test("the blocking-dependency legend swatch matches its edge's stroke color, in both themes", async ({
    roadmapGraphPage,
    api,
    workerRepo,
  }) => {
    const epic = await api.createEpic({
      title: uniqueName("E2E Legend Fidelity Epic"),
      description: "desc",
      softwareProjectId: workerRepo.gitRepo.id,
    });
    const story = await api.createStory(epic.id, { title: "Legend Fidelity Story", description: "desc" });
    const blockingTask = await api.createTask(story.id, {
      title: uniqueName("Legend Blocking Task"),
      description: "desc",
    });
    const blockedTask = await api.createTask(story.id, {
      title: uniqueName("Legend Blocked Task"),
      description: "desc",
    });

    try {
      await api.createDependency({
        blockingItemType: "task",
        blockingItemId: blockingTask.id,
        blockedItemType: "task",
        blockedItemId: blockedTask.id,
      });

      await roadmapGraphPage.goto(epic.id);
      await expect(roadmapGraphPage.page.locator('.react-flow__edge[data-id^="dep:"]')).toHaveCount(1);
      await expect(roadmapGraphPage.legend).toBeVisible();

      const lightSwatch = await roadmapGraphPage.legendSwatchColor("dependency");
      const lightEdge = await roadmapGraphPage.edgeStrokeColor("dep:");
      expect(lightSwatch).toBe(lightEdge);

      await toggleTheme(roadmapGraphPage.page);
      await expect(roadmapGraphPage.page.locator("html")).toHaveClass(/dark/);

      const darkSwatch = await roadmapGraphPage.legendSwatchColor("dependency");
      const darkEdge = await roadmapGraphPage.edgeStrokeColor("dep:");
      expect(darkSwatch).toBe(darkEdge);
      // Recolored, not a light-mode value that happens to still match itself.
      expect(darkSwatch).not.toBe(lightSwatch);
    } finally {
      await api.deleteEpic(epic.id);
    }
  });
});
