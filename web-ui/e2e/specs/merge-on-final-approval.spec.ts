import { test, expect, seededRepos } from "../fixtures";
import { uniqueName } from "../helpers/api-client";
import type { RunPullRequestResponse } from "../../src/lib/types";

/**
 * Exercises Feature Development v45's Final Approval merge-on-approval behavior, mirrored by
 * the "e2e-merge-on-approval" / "e2e-merge-on-approval-blocked" templates
 * (E2eTestDataSeeder): an "open_prs" node runs mock-agent.sh's "multi_repo_pr" scenario pinned
 * to a fixed PR number via --pr-number, so the gate always merges a known WireMock PR-stub
 * fixture (github-pulls.json / github-merge-pr.json) rather than a random one the stubs don't
 * recognise. PR 7001 is WireMock's always-merges-for-mock-repo fixture (already-merged for
 * mock-frontend); PR 7405 is WireMock's always-refused fixture.
 */
test.describe("Merge on Final Approval", () => {
  test("approving merges open PRs and skips already-merged ones", async ({
    runMonitorPage,
    page,
    api,
  }) => {
    test.setTimeout(180_000);

    const reposPage = await api.listGitRepos();
    const seeded = seededRepos(reposPage.content);
    const mockRepo = seeded.find((r) => r.url.endsWith("/mock-repo"));
    const mockFrontend = seeded.find((r) => r.url.endsWith("/mock-frontend"));
    if (!mockRepo || !mockFrontend) {
      throw new Error(
        "E2eTestDataSeeder must seed both e2e-test/mock-repo and e2e-test/mock-frontend for this spec",
      );
    }

    const groupName = uniqueName("e2e-merge-on-approval");
    const group = await api.createRepoGroup({
      name: groupName,
      memberRepoIds: [mockRepo.id, mockFrontend.id],
    });

    const template = await api.getTemplateByName("e2e-merge-on-approval");
    const runName = uniqueName("merge-on-approval-run");
    let runId: string | null = null;

    try {
      const run = await api.startRun({
        graphTemplateId: template.id,
        inputs: { software_project_id: group.id },
        name: runName,
      });
      runId = run.id;

      await api.waitForNodeStatus(run.id, "final_approval", ["awaiting_human"], 60_000);

      await runMonitorPage.goto(run.id);
      await runMonitorPage.selectNode("final_approval");
      await expect(runMonitorPage.mergeNotice).toBeVisible();
      // One list item per registered PR (mock-repo#7001 open, mock-frontend#7001 already merged).
      await expect(runMonitorPage.mergeNotice.locator("li")).toHaveCount(2);

      await runMonitorPage.approveGate("Approving — exercising the merge-on-approval path.");

      const finished = await api.waitForRunStatus(run.id, ["completed"], 90_000);
      expect(finished.status).toBe("completed");

      const finalRun = await api.getRun(run.id);
      const pullRequests = (
        finalRun as unknown as { pullRequests: RunPullRequestResponse[] }
      ).pullRequests;
      expect(pullRequests).toHaveLength(2);
      expect(pullRequests.every((pr) => pr.mergedAt != null)).toBe(true);

      const finalApproval = finalRun.nodeExecutions.find((ne) => ne.label === "final_approval");
      expect(finalApproval?.result ?? "").toContain("Pull requests merged on approval");

      // UI: the run's PR list renders in the always-visible run summary now, independent
      // of node selection — revisit fresh here to read it from a clean load.
      await page.goto(`/runs/${run.id}`);
      await expect(runMonitorPage.pullRequestStates).toHaveCount(2);
      const texts = await runMonitorPage.pullRequestStates.allTextContents();
      expect(texts.every((t) => t.includes("Merged"))).toBe(true);
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

  test("a refused merge keeps the gate open with GitHub's reason", async ({
    runMonitorPage,
    page,
    api,
  }) => {
    test.setTimeout(120_000);

    const reposPage = await api.listGitRepos();
    const seeded = seededRepos(reposPage.content);
    const mockRepo = seeded.find((r) => r.url.endsWith("/mock-repo"));
    if (!mockRepo) {
      throw new Error("E2eTestDataSeeder must seed e2e-test/mock-repo for this spec");
    }

    const groupName = uniqueName("e2e-merge-on-approval-blocked");
    const group = await api.createRepoGroup({
      name: groupName,
      memberRepoIds: [mockRepo.id],
    });

    const template = await api.getTemplateByName("e2e-merge-on-approval-blocked");
    const runName = uniqueName("merge-blocked-run");
    let runId: string | null = null;

    try {
      const run = await api.startRun({
        graphTemplateId: template.id,
        inputs: { software_project_id: group.id },
        name: runName,
      });
      runId = run.id;

      await api.waitForNodeStatus(run.id, "final_approval", ["awaiting_human"], 60_000);

      await runMonitorPage.goto(run.id);
      await runMonitorPage.selectNode("final_approval");
      await runMonitorPage.approveGate("Approving — exercising the refused-merge path.");

      const errorToast = page
        .locator('[data-sonner-toast][data-type="error"]')
        .filter({ hasText: "At least 1 approving review is required by reviewers with write access." });
      await expect(errorToast).toBeVisible();

      // Approve-time merge failure releases the claim: nothing is persisted and the
      // node stays awaiting a decision. Poll briefly rather than a single read, since
      // the release happens asynchronously to the toast rendering.
      await api.waitForNodeStatus(run.id, "final_approval", ["awaiting_human"], 5_000, 500);
      const stillRunning = await api.getRun(run.id);
      expect(stillRunning.status).not.toBe("completed");
      const pullRequests = (
        stillRunning as unknown as { pullRequests: RunPullRequestResponse[] }
      ).pullRequests;
      expect(pullRequests).toHaveLength(1);
      expect(pullRequests[0].mergedAt).toBeNull();

      await expect(runMonitorPage.gateApproveButton).toBeVisible();
    } finally {
      try {
        if (runId) await api.cancelRun(runId);
      } catch {
        // best-effort cleanup
      }
      try {
        await api.deleteRepoGroup(group.id);
      } catch {
        // best-effort cleanup
      }
    }
  });
});
