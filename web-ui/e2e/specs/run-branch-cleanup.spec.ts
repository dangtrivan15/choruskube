import { test, expect, seededRepos } from "../fixtures";
import { uniqueName } from "../helpers/api-client";
import { findWireMockRequests, headerOf } from "../helpers/wiremock";

/**
 * Proves the Worker, not the API server, deletes a completed run's stale branches. The API
 * server and the Worker send the same bearer token under the `control-plane` credential source,
 * so the token alone cannot tell them apart — only the Worker's requests carry its fixed
 * `User-Agent: choruskube-worker` (worker/github/client.go), which this spec reads back from
 * WireMock's own request journal.
 *
 * Reuses the two already-seeded, genuinely clonable repos (mock-repo, mock-frontend) and the
 * `e2e-check-prs-gate` template, the same shape as pull-request-links-multi-repo.spec.ts.
 * mock-repo must be the group's FIRST member: the check_prs_gate mock scenario leaves the first
 * member at parity with its default branch, so its run branch compares at ahead_by 0 and is
 * deleted; mock-frontend's own WireMock stub answers ahead_by 3, so its run branch is kept.
 */
test.describe("run-branch cleanup", () => {
  test("a completed run's branches are cleaned up by the Worker only", async ({
    runMonitorPage,
    api,
  }) => {
    // 90s for the gate wait, 90s for the completion wait, 60s for the journal poll: 180s of
    // budget on its own, so 240s leaves headroom for the test's own setup and assertions rather
    // than hitting the test timeout before the poll's own failure message can fire.
    test.setTimeout(240_000);

    const reposPage = await api.listGitRepos();
    const seeded = seededRepos(reposPage.content);
    const mockRepo = seeded.find((r) => r.url.endsWith("/mock-repo"));
    const mockFrontend = seeded.find((r) => r.url.endsWith("/mock-frontend"));
    if (!mockRepo || !mockFrontend) {
      throw new Error("E2eTestDataSeeder must seed both e2e-test/mock-repo and e2e-test/mock-frontend for this spec");
    }

    const groupName = uniqueName("e2e-branch-cleanup");
    const group = await api.createRepoGroup({
      name: groupName,
      // mock-repo first: see the class doc comment above for why ordering matters here.
      memberRepoIds: [mockRepo.id, mockFrontend.id],
    });

    const template = await api.getTemplateByName("e2e-check-prs-gate");
    const runName = uniqueName("branch-cleanup-run");

    try {
      const run = await api.startRun({
        graphTemplateId: template.id,
        inputs: { software_project_id: group.id },
        name: runName,
      });

      await api.waitForNodeStatus(run.id, "final_approval", ["awaiting_human"], 90_000);

      await runMonitorPage.goto(run.id);
      await runMonitorPage.selectNode("final_approval");
      await runMonitorPage.approveGate("Approved — verifying the Worker cleans up run branches.");

      const finished = await api.waitForRunStatus(run.id, ["completed"], 90_000);
      expect(finished.status).toBe("completed");

      const runBranch = `choruskube-run-${run.id}`;
      const deletePath = `/repos/e2e-test/mock-repo/git/refs/heads/${runBranch}`;
      const comparePattern = `/repos/e2e-test/mock-frontend/compare/.+\\.\\.\\.${runBranch}`;

      // Both calls are made fire-and-forget from Step 6 after the run is already "completed",
      // so poll for them rather than assuming they have already landed by the time the run's
      // own status flips.
      await expect(async () => {
        const deletes = await findWireMockRequests({ method: "DELETE", url: deletePath });
        const compares = await findWireMockRequests({ method: "GET", urlPattern: comparePattern });
        expect(deletes.length).toBeGreaterThan(0);
        expect(compares.length).toBeGreaterThan(0);
      }).toPass({ timeout: 60_000 });

      // One more poll interval, then read the journal a final time: nothing later contradicts
      // what was already proven true above.
      await new Promise((resolve) => setTimeout(resolve, 1_000));

      const deletes = await findWireMockRequests({ method: "DELETE", url: deletePath });
      const compares = await findWireMockRequests({ method: "GET", urlPattern: comparePattern });
      for (const req of [...deletes, ...compares]) {
        expect(headerOf(req, "User-Agent")).toBe("choruskube-worker");
      }

      const mockFrontendDeletePath = `/repos/e2e-test/mock-frontend/git/refs/heads/${runBranch}`;
      const frontendDeletes = await findWireMockRequests({ method: "DELETE", url: mockFrontendDeletePath });
      expect(frontendDeletes).toHaveLength(0);

      // No cleanup call for this run's branch, on either repo, carries any other User-Agent —
      // i.e. the API server made none of them under the control-plane credential source.
      const anyRepoPattern = `/repos/.+/(compare/.+\\.\\.\\.${runBranch}|git/refs/heads/${runBranch})`;
      const allCleanupRequests = [
        ...(await findWireMockRequests({ method: "GET", urlPattern: anyRepoPattern })),
        ...(await findWireMockRequests({ method: "DELETE", urlPattern: anyRepoPattern })),
      ];
      for (const req of allCleanupRequests) {
        expect(headerOf(req, "User-Agent")).toBe("choruskube-worker");
      }
    } finally {
      try {
        await api.deleteRepoGroup(group.id);
      } catch {
        // best-effort cleanup
      }
    }
  });
});
