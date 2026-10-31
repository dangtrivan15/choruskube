import { test, expect, seededRepos } from "../fixtures";
import { uniqueName } from "../helpers/api-client";
import { findWireMockRequests, headerOf } from "../helpers/wiremock";
import type { RunPullRequestResponse } from "../../src/lib/types";

/**
 * The "e2e-drift-recheck" template (E2eTestDataSeeder): open_pr's first pass reports a base the
 * WireMock compare stub calls behind main, so approving final_approval finalizes on the Worker,
 * is held and routed back to open_pr; its second pass is current, and the held approval
 * re-applies itself and merges PR 7001.
 */
test.describe("Drift re-check at approval", () => {
  test("a stale approval is held, re-checked and re-applied", async ({ runMonitorPage, page, api }) => {
    test.setTimeout(240_000);

    const reposPage = await api.listGitRepos();
    const mockRepo = seededRepos(reposPage.content).find((r) => r.url.endsWith("/mock-repo"));
    if (!mockRepo) {
      throw new Error("E2eTestDataSeeder must seed e2e-test/mock-repo for this spec");
    }

    const group = await api.createRepoGroup({
      name: uniqueName("e2e-drift-recheck"),
      memberRepoIds: [mockRepo.id],
    });

    const template = await api.getTemplateByName("e2e-drift-recheck");
    let runId: string | null = null;

    try {
      const run = await api.startRun({
        graphTemplateId: template.id,
        inputs: { software_project_id: group.id },
        name: uniqueName("drift-recheck-run"),
      });
      runId = run.id;

      await api.waitForNodeStatus(run.id, "final_approval", ["awaiting_human"], 60_000);

      await runMonitorPage.goto(run.id);
      await runMonitorPage.selectNode("final_approval");
      await runMonitorPage.approveGate("Approving — exercising the drift re-check path.");

      await expect(
        page.locator("[data-sonner-toast]").filter({ hasText: "Finalizing your approval…" }),
      ).toBeVisible();

      const finished = await api.waitForRunStatus(run.id, ["completed"], 150_000);
      expect(finished.status).toBe("completed");

      const finalRun = await api.getRun(run.id);
      const pullRequests = (finalRun as unknown as { pullRequests: RunPullRequestResponse[] }).pullRequests;
      expect(pullRequests).toHaveLength(1);
      expect(pullRequests[0].mergedAt).not.toBeNull();

      expect(finalRun.nodeExecutions.filter((ne) => ne.label === "open_pr")).toHaveLength(2);
      const approvals = finalRun.nodeExecutions
        .filter((ne) => ne.label === "final_approval")
        .sort((a, b) => a.iteration - b.iteration);
      expect(approvals).toHaveLength(2);
      expect(approvals[0].decision).toBe("rereview");

      const stamps = await api.getGateStamps(run.id);
      expect(stamps).toHaveLength(1);
      expect(stamps[0].status).toBe("consumed");
      expect(stamps[0].recheckRounds).toBe(1);

      // The Worker's compare call trails the run's own "completed" status write, so poll
      // rather than assume the journal already has it by the time waitForRunStatus resolves.
      // This User-Agent check holds for every compare in the suite — the Worker's
      // branch-cleanup activity (see run-branch-cleanup.spec.ts) uses the same fixed string —
      // not just the drift re-check's own compare.
      const comparePattern = "/repos/.+/compare/.+";
      await expect(async () => {
        const compares = await findWireMockRequests({ method: "GET", urlPattern: comparePattern });
        expect(compares.length).toBeGreaterThan(0);
      }).toPass({ timeout: 60_000 });

      const compares = await findWireMockRequests({ method: "GET", urlPattern: comparePattern });
      for (const req of compares) {
        expect(headerOf(req, "User-Agent")).toBe("choruskube-worker");
      }
    } finally {
      // A failure before completion would otherwise leave the run live for other workers' specs.
      try {
        if (runId) await api.cancelRun(runId);
      } catch {
        // best-effort cleanup — cancelling an already-completed run is refused
      }
      try {
        await api.deleteRepoGroup(group.id);
      } catch {
        // best-effort cleanup
      }
    }
  });
});
