import { test, expect } from "../fixtures";
import { uniqueName } from "../helpers/api-client";

test.describe("Artifact Viewer Layout", () => {
  // e2e-many-artifacts is a single-node template whose entrypoint node
  // (produce_files) runs mock-agent.sh's "many_artifacts" scenario, writing 40
  // small distinct files. This is a direct regression guard for the bug where
  // the file-switcher pill row grows unbounded and squeezes the content pane
  // toward zero once a node produces many output files (ArtifactViewerDialog.tsx).

  test("content pane stays visible when a node produces many files", async ({
    runMonitorPage,
    api,
  }) => {
    const template = await api.getTemplateByName("e2e-many-artifacts");
    // Run names are capped at 30 chars server-side (RunService.RUN_NAME_MAX_LENGTH)
    // and silently truncated past that — keep the uniqueName() prefix short so this
    // stays under the limit even with a 2-digit shard/worker index and a multi-digit
    // monotonic counter (see run-lifecycle.spec.ts / roadmap-candidate-gate.spec.ts).
    const run = await api.startRun({
      graphTemplateId: template.id,
      name: uniqueName("av-many"),
    });

    await api.waitForRunStatus(run.id, ["completed"], 60_000);

    await runMonitorPage.goto(run.id);
    await runMonitorPage.selectNode("produce_files");

    // Normal (non-gate) node artifacts render via ArtifactBrowser, not ArtifactList —
    // artifactListItems targets the gate-only ArtifactList component and would resolve
    // to zero elements here.
    await expect(runMonitorPage.artifactBrowserItems.first()).toBeVisible();
    await runMonitorPage.artifactBrowserItems.first().click();

    await expect(runMonitorPage.artifactViewerDialog).toBeVisible();

    // The direct regression guard: the content pane must not collapse to zero
    // (or near-zero) height just because the switcher above it lists many files.
    const box = await runMonitorPage.artifactViewerContent.boundingBox();
    expect(box).not.toBeNull();
    expect(box!.height).toBeGreaterThan(100);
  });

  test("file switcher scrolls independently of the content pane", async ({
    runMonitorPage,
    api,
  }) => {
    const template = await api.getTemplateByName("e2e-many-artifacts");
    const run = await api.startRun({
      graphTemplateId: template.id,
      name: uniqueName("av-scroll"),
    });

    await api.waitForRunStatus(run.id, ["completed"], 60_000);

    await runMonitorPage.goto(run.id);
    await runMonitorPage.selectNode("produce_files");
    await runMonitorPage.artifactBrowserItems.first().click();

    await expect(runMonitorPage.artifactViewerDialog).toBeVisible();
    await expect(runMonitorPage.artifactFileSwitcher).toBeVisible();

    // Clicking a pill further down the (internally scrollable) switcher still
    // updates the content pane — the bounded/scrollable switcher doesn't break
    // file selection.
    const pills = runMonitorPage.artifactFileSwitcher.locator("button");
    const lastPill = pills.last();
    await lastPill.scrollIntoViewIfNeeded();
    const lastPillName = (await lastPill.textContent())?.trim();
    await lastPill.click();

    // Scoped to the dialog: the Run Monitor page also renders "Run Info"
    // (RunMetaPanel) and the selected node's label (DetailPanel) as their own
    // <h2>s, so an unscoped page-wide heading locator would match 3 elements
    // and trip Playwright's strict-mode check.
    await expect(
      runMonitorPage.artifactViewerDialog.getByRole("heading", { level: 2 }),
    ).toContainText(lastPillName ?? "");
    await expect(runMonitorPage.artifactViewerContent).toBeVisible();
  });
});
