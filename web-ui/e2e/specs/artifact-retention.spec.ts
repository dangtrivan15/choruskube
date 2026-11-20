import { test, expect } from "../fixtures";
import { uniqueName } from "../helpers/api-client";

test.describe("Run artifact retention", () => {
  // e2e-many-artifacts is a single-node template whose entrypoint node (produce_files)
  // writes 40 small files — see artifact-viewer-layout.spec.ts. expireRunArtifacts drives
  // the api-server's real retention pass over this one run, through an e2e-profile route.

  test("an open run page swaps the node's files for the expiry notice once retention deletes them", async ({
    runMonitorPage,
    api,
  }) => {
    const template = await api.getTemplateByName("e2e-many-artifacts");
    // Run names are capped at 30 chars server-side and silently truncated past that —
    // keep the uniqueName() prefix short (see artifact-viewer-layout.spec.ts).
    const run = await api.startRun({
      graphTemplateId: template.id,
      name: uniqueName("ar-exp"),
    });

    await api.waitForRunStatus(run.id, ["completed"], 60_000);

    await runMonitorPage.goto(run.id);
    await runMonitorPage.selectNode("produce_files");

    // Closed but not yet expired: the node's files list exactly as before.
    await expect(runMonitorPage.artifactBrowserItems.first()).toBeVisible();
    await expect(runMonitorPage.artifactsExpired).toHaveCount(0);

    await api.expireRunArtifacts(run.id);

    // Same page instance, no reload: the run's refetch carries artifactsExpiredAt.
    await expect(runMonitorPage.artifactsExpired).toHaveText(
      /^Artifacts expired on [A-Z][a-z]{2} \d{1,2}, \d{4}$/,
    );
    await expect(runMonitorPage.artifactBrowserItems).toHaveCount(0);

    // A fresh load renders the notice from the run alone and never asks for the file list.
    // Counted only from here: the invalidation above also refetches the still-mounted list.
    const listRequests: string[] = [];
    runMonitorPage.page.on("request", (request) => {
      if (/\/node-executions\/[^/]+\/artifacts$/.test(new URL(request.url()).pathname)) {
        listRequests.push(request.url());
      }
    });

    await runMonitorPage.page.reload();
    await expect(runMonitorPage.runTitle).toBeVisible({ timeout: 15_000 });
    await runMonitorPage.selectNode("produce_files");

    await expect(runMonitorPage.artifactsExpired).toHaveText(/^Artifacts expired on /);
    await expect(runMonitorPage.artifactBrowserItems).toHaveCount(0);
    expect(listRequests).toEqual([]);
  });
});
