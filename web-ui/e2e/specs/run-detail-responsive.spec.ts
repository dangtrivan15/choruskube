// Responsive behaviour of the Run Monitor page across the three layout tiers
// (phone < 768px, tablet 768-1023px, desktop/docked >= 1024px). Each
// describe block pins its own viewport via test.use() before navigating, so
// a tier's assertions always see the tier they were written for.
import { test, expect } from "../fixtures";
import { uniqueName } from "../helpers/api-client";

test.describe("Run detail — desktop (docked)", () => {
  test.use({ viewport: { width: 1280, height: 800 } });

  test("opens on the waiting gate with no click, keeps it across a reload, and run info stays visible", async ({
    runMonitorPage,
    api,
  }) => {
    const template = await api.getTemplateByName("e2e-human-gate");
    const run = await api.startRun({
      graphTemplateId: template.id,
      name: uniqueName("e2e-responsive-desktop"),
    });

    await api.waitForNodeStatus(run.id, "review_gate", ["awaiting_human"], 60_000);

    await runMonitorPage.goto(run.id);

    // Desktop auto-focuses the attention node once, with no click — the awaiting gate.
    await expect(runMonitorPage.detailPanel).toBeVisible();
    await expect(runMonitorPage.detailNodeLabel).toHaveText("review_gate");
    await expect(runMonitorPage.page).toHaveURL(/[?&]node=[^&]+/);
    await expect(runMonitorPage.runSummary).toBeVisible();

    await runMonitorPage.page.reload();
    await expect(runMonitorPage.detailPanel).toBeVisible();
    await expect(runMonitorPage.detailNodeLabel).toHaveText("review_gate");
    await expect(runMonitorPage.page).toHaveURL(/[?&]node=[^&]+/);
    await expect(runMonitorPage.runSummary).toBeVisible();
  });
});

test.describe("Run detail — tablet", () => {
  test.use({ viewport: { width: 834, height: 1112 } });

  test("summary strip visible, no docked panel, node opens a sheet with a close control", async ({
    runMonitorPage,
    api,
  }) => {
    const template = await api.getTemplateByName("e2e-linear-pipeline");
    const run = await api.startRun({
      graphTemplateId: template.id,
      name: uniqueName("e2e-responsive-tablet"),
    });

    await runMonitorPage.goto(run.id);
    await expect(runMonitorPage.dagNodes.first()).toBeVisible({ timeout: 15_000 });

    await expect(runMonitorPage.runSummary).toBeVisible();
    await expect(runMonitorPage.sidebarCollapseButton).not.toBeAttached();

    await runMonitorPage.dagNodes.first().click();
    await expect(runMonitorPage.nodeSheet).toBeVisible();
    await expect(runMonitorPage.detailPanel).toBeVisible();

    await runMonitorPage.page.getByTestId("bottom-sheet-close").click();
    await expect(runMonitorPage.nodeSheet).not.toBeVisible();
  });
});

test.describe("Run detail — phone", () => {
  test.use({ viewport: { width: 390, height: 844 } });

  test("graph fills most of the viewport at a readable size, with no horizontal scroll", async ({
    runMonitorPage,
    api,
  }) => {
    const template = await api.getTemplateByName("e2e-linear-pipeline");
    const run = await api.startRun({
      graphTemplateId: template.id,
      name: uniqueName("e2e-responsive-phone"),
    });

    await runMonitorPage.goto(run.id);
    await expect(runMonitorPage.dagNodes.first()).toBeVisible({ timeout: 15_000 });
    await runMonitorPage.waitForViewportReady();

    const containerBox = await runMonitorPage.dagContainer.boundingBox();
    expect(containerBox).not.toBeNull();
    expect(containerBox!.height).toBeGreaterThanOrEqual(0.5 * 844);

    const widths = await runMonitorPage.dagNodes.evaluateAll((els) =>
      els.map((el) => el.getBoundingClientRect().width),
    );
    expect(widths.some((w) => w >= 120)).toBe(true);

    const overflow = await runMonitorPage.page.evaluate(() => {
      const el = document.documentElement;
      return el.scrollWidth - el.clientWidth;
    });
    expect(overflow).toBeLessThanOrEqual(1);

    await expect(runMonitorPage.runTitle).toBeVisible();
  });

  test("tapping a node opens a sheet; Escape dismisses it", async ({
    runMonitorPage,
    api,
  }) => {
    const template = await api.getTemplateByName("e2e-linear-pipeline");
    const run = await api.startRun({
      graphTemplateId: template.id,
      name: uniqueName("e2e-responsive-phone-sheet"),
    });

    await runMonitorPage.goto(run.id);
    await expect(runMonitorPage.dagNodes.first()).toBeVisible({ timeout: 15_000 });

    await runMonitorPage.dagNodes.first().click();
    await expect(runMonitorPage.nodeSheet).toBeVisible();
    await expect(runMonitorPage.detailPanel).toBeVisible();

    await runMonitorPage.page.keyboard.press("Escape");
    await expect(runMonitorPage.nodeSheet).not.toBeVisible();
  });

  test("Run info sheet shows the full feature request and the stacked, untruncated breadcrumb", async ({
    runMonitorPage,
    api,
    workerRepo,
  }) => {
    // ~120-char titles: long enough that the stacked view would visibly wrap/truncate
    // if it weren't the full-title rendering it's supposed to be.
    const epicTitle = uniqueName(
      "E2E Responsive Phone Epic With A Long Title That Exercises The Stacked Breadcrumb Rendering In The Run Info Sheet",
    );
    const storyTitle = uniqueName(
      "E2E Responsive Phone Story With An Equally Long Title For The Same Stacked Breadcrumb Check Here",
    );
    const taskTitle = "E2E responsive phone task";

    const epic = await api.createEpic({
      title: epicTitle,
      description: "Testing phone run-info sheet",
      softwareProjectId: workerRepo.gitRepo.id,
    });
    const story = await api.createStory(epic.id, {
      title: storyTitle,
      description: "desc",
    });
    const task = await api.createTask(story.id, {
      title: taskTitle,
      description: "desc",
    });

    const started = await api.startTask(task.id);
    expect(started.latestRunId).not.toBeNull();

    await runMonitorPage.goto(started.latestRunId!);

    await runMonitorPage.runInfoOpenButton.click();
    await expect(runMonitorPage.runInfoSheet).toBeVisible();
    await expect(runMonitorPage.runInfoSheet.getByTestId("run-summary-prompt")).toBeVisible();

    const breadcrumb = runMonitorPage.runInfoSheet.getByTestId("roadmap-breadcrumb");
    await expect(breadcrumb).toHaveAttribute("data-variant", "stacked");
    await expect(breadcrumb).toContainText(epicTitle);
    await expect(breadcrumb).toContainText(storyTitle);
    await expect(breadcrumb).toContainText(taskTitle);

    // No cleanup: same reasoning as run-lifecycle.spec.ts's breadcrumb fixtures —
    // the started Task has left "backlog", so the Epic can no longer be deleted.
  });

  test("approving a gate from the attention sheet keeps it open on the same node", async ({
    runMonitorPage,
    api,
  }) => {
    const template = await api.getTemplateByName("e2e-human-gate");
    const run = await api.startRun({
      graphTemplateId: template.id,
      name: uniqueName("e2e-responsive-phone-gate"),
    });

    await api.waitForNodeStatus(run.id, "review_gate", ["awaiting_human"], 60_000);

    await runMonitorPage.goto(run.id);
    await expect(runMonitorPage.attentionButton).toBeVisible();
    await runMonitorPage.attentionButton.click();

    await expect(runMonitorPage.nodeSheet).toBeVisible();
    await expect(runMonitorPage.detailNodeLabel).toHaveText("review_gate");

    const urlBefore = runMonitorPage.page.url();
    const nodeParamBefore = new URL(urlBefore).searchParams.get("node");
    expect(nodeParamBefore).toBeTruthy();

    await runMonitorPage.approveGate("Approving from the phone attention sheet.");

    // The sheet stays open on the same node; its live update shows the gate completed.
    await expect(runMonitorPage.nodeSheet).toBeVisible();
    await expect(runMonitorPage.detailStatus).toContainText("completed", { timeout: 30_000 });
    const urlAfter = runMonitorPage.page.url();
    expect(new URL(urlAfter).searchParams.get("node")).toBe(nodeParamBefore);
  });

  test("cancelling from the actions menu cancels the run", async ({
    runMonitorPage,
    api,
  }) => {
    const template = await api.getTemplateByName("e2e-human-gate");
    const run = await api.startRun({
      graphTemplateId: template.id,
      name: uniqueName("e2e-responsive-phone-cancel"),
    });

    await api.waitForNodeStatus(run.id, "review_gate", ["awaiting_human"], 60_000);

    await runMonitorPage.goto(run.id);
    await runMonitorPage.cancelRunFromMenu();
    await runMonitorPage.waitForStatus(/cancel/i);
  });
});
