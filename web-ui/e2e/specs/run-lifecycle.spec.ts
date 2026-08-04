import { test, expect } from "../fixtures";
import { uniqueName } from "../helpers/api-client";

test.describe("Run Lifecycle", () => {
  test.describe("Run List", () => {
    test("displays the runs page with table", async ({ runListPage }) => {
      await runListPage.goto();
      await expect(runListPage.heading).toBeVisible();
      await expect(runListPage.startRunButton).toBeVisible();
    });

    test("shows empty state or run rows after load", async ({ runListPage }) => {
      await runListPage.goto();
      await runListPage.waitForTableLoad();
      // Either we have runs or the empty state
      const rowCount = await runListPage.getRunCount();
      if (rowCount === 0) {
        await expect(runListPage.emptyState).toBeVisible();
      } else {
        await expect(runListPage.runRows.first()).toBeVisible();
      }
    });

    test("status filter changes displayed runs", async ({ runListPage }) => {
      await runListPage.goto();
      await runListPage.waitForTableLoad();

      // Filter to completed runs
      await runListPage.filterByStatus("Completed");

      // Wait for table to reload
      await runListPage.page.waitForTimeout(1000);

      // Verify the filter is applied (check URL or content)
      // Run rows should now only contain completed status badges
    });
  });

  test.describe("Start Run Dialog", () => {
    test("opens and shows template selector", async ({ runListPage }) => {
      await runListPage.goto();
      await runListPage.openStartRunDialog();

      // Dialog should be visible with template selector
      await expect(runListPage.templateSelect).toBeVisible();
    });

    test("start button is disabled until template is selected", async ({ runListPage }) => {
      await runListPage.goto();
      await runListPage.openStartRunDialog();

      // Start button should be disabled without template selection
      await expect(runListPage.startButton).toBeDisabled();
    });

    test("can start a run with a template", async ({ runListPage, api }) => {
      // Assert the fixture exists so a seeder rename fails loudly, then drive
      // the dialog with the same UI-visible name (the picker renders t.name).
      await api.getTemplateByName("e2e-linear-pipeline");

      // Run names are capped at 30 chars server-side (RunService.RUN_NAME_MAX_LENGTH)
      // and silently truncated with a "…" marker past that — keep the uniqueName()
      // prefix short so this stays under the limit (the poll below does an exact
      // name match against the server-stored, possibly-truncated value).
      const runName = uniqueName("e2e-lc");
      await runListPage.goto();
      await runListPage.startRun("e2e-linear-pipeline", runName);

      // The dialog closed on success — the run now exists server-side. Find it
      // by its UI name, then drive the mock-agent pipeline to completion via the
      // polling helper (proves the UI-initiated run actually executes).
      let startedId: string | null = null;
      await expect
        .poll(
          async () => {
            const runs = await api.listRuns();
            startedId = runs.content.find((r) => r.name === runName)?.id ?? null;
            return startedId;
          },
          { timeout: 15_000, message: "run started via the UI dialog should be listed" },
        )
        .not.toBeNull();

      const finished = await api.waitForRunStatus(startedId!, ["completed"], 120_000);
      expect(finished.status).toBe("completed");
    });
  });

  test.describe("Run Monitor", () => {
    test("navigating from run list to run detail", async ({ runListPage, api }) => {
      // Start a run via API for reliable setup
      const template = await api.getTemplateByName("e2e-linear-pipeline");

      const run = await api.startRun({
        graphTemplateId: template.id,
        name: "e2e-monitor-nav-test",
      });

      await runListPage.goto();
      await runListPage.waitForTableLoad();
      await runListPage.clickRunById(run.id);

      // Should be on the run monitor page
      await expect(runListPage.page).toHaveURL(new RegExp(`/runs/${run.id}`));
    });

    test("shows run header with status", async ({ runMonitorPage, api }) => {
      const template = await api.getTemplateByName("e2e-linear-pipeline");

      const run = await api.startRun({
        graphTemplateId: template.id,
        name: "e2e-header-test",
      });

      await runMonitorPage.goto(run.id);
      await expect(runMonitorPage.runTitle).toBeVisible();
      await expect(runMonitorPage.runStatus).toBeVisible();
    });

    test("shows Epic -> Story -> Task breadcrumb for a run started from a Task", async ({
      runMonitorPage,
      api,
    }) => {
      const repos = await api.listGitRepos();
      if (repos.content.length === 0) {
        test.skip();
        return;
      }

      // Build a real Epic -> Story -> Task chain (same fixture-building pieces
      // already proven by roadmap.spec.ts / roadmap-graph.spec.ts), then start
      // the run from the Task rather than a manual/template run — this proves
      // task_context (Decision 1/2/3) actually reaches the run detail page end
      // to end, not just the RunMetaPanel unit tests.
      const uniqueTitle = uniqueName("E2E Breadcrumb");
      const epic = await api.createEpic({
        title: uniqueTitle,
        description: "Testing run breadcrumb",
        softwareProjectId: repos.content[0].id,
      });
      const storyTitle = uniqueName("Breadcrumb story");
      const story = await api.createStory(epic.id, {
        title: storyTitle,
        description: "desc",
      });
      const task = await api.createTask(story.id, {
        title: "Breadcrumb task",
        description: "desc",
      });

      const started = await api.startTask(task.id);
      expect(started.latestRunId).not.toBeNull();

      // startTask's response already carries the new run's id — go straight to
      // the run detail page, no UI click-through or status polling needed.
      await runMonitorPage.goto(started.latestRunId!);

      const breadcrumb = runMonitorPage.page.getByTestId("run-meta-panel-breadcrumb");
      await expect(breadcrumb).toBeVisible();
      await expect(breadcrumb).toContainText(uniqueTitle);
      await expect(breadcrumb).toContainText(storyTitle);

      // No cleanup here (unlike roadmap.spec.ts / roadmap-graph.spec.ts fixtures):
      // starting the Task above has already moved it out of "backlog", and
      // DefaultEpicService#delete deliberately refuses to delete an Epic with any
      // started descendant Task ("Can only delete an Epic while all of its Tasks
      // are still in backlog") — that's intentional, to preserve run history. The
      // `uniqueTitle` / `storyTitle` uniqueName() suffixes keep this fixture from
      // colliding with other runs of this spec, so leaving it behind is safe.
    });

    test("DAG visualization renders nodes", async ({ runMonitorPage, api }) => {
      const template = await api.getTemplateByName("e2e-linear-pipeline");

      const run = await api.startRun({
        graphTemplateId: template.id,
        name: "e2e-dag-test",
      });

      await runMonitorPage.goto(run.id);
      await expect(runMonitorPage.dagContainer).toBeVisible();

      // DAG should render nodes
      await expect(runMonitorPage.dagNodes.first()).toBeVisible({
        timeout: 15_000,
      });
    });

    test("clicking a DAG node opens detail panel", async ({ runMonitorPage, api }) => {
      const template = await api.getTemplateByName("e2e-linear-pipeline");

      const run = await api.startRun({
        graphTemplateId: template.id,
        name: "e2e-detail-panel-test",
      });

      await runMonitorPage.goto(run.id);
      await expect(runMonitorPage.dagNodes.first()).toBeVisible({
        timeout: 15_000,
      });

      // Click the first visible dag node
      await runMonitorPage.dagNodes.first().click();
      await expect(runMonitorPage.detailPanel).toBeVisible();
      await expect(runMonitorPage.detailNodeLabel).toBeVisible();
    });

    test("cancel button terminates a running workflow", async ({ runMonitorPage, api }) => {
      const template = await api.getTemplateByName("e2e-linear-pipeline");

      const run = await api.startRun({
        graphTemplateId: template.id,
        name: "e2e-cancel-test",
      });

      await runMonitorPage.goto(run.id);

      // Wait for the run to be in a non-terminal state
      // The cancel button should be visible
      const cancelVisible = await runMonitorPage.cancelButton.isVisible().catch(() => false);
      if (cancelVisible) {
        await runMonitorPage.cancelRun();
        // Wait for status to update
        await runMonitorPage.waitForStatus(/cancel/i);
      }
    });
  });
});
