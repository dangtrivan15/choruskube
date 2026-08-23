import { test, expect, seededRepos } from "../fixtures";
import { uniqueName } from "../helpers/api-client";
import type { RunPullRequestResponse } from "../../src/lib/types";

/**
 * Drives a real multi-repo run through the "e2e-check-prs-gate" template
 * (Part 2's E2E coverage note — see mock-agent.sh's check_prs_gate scenario)
 * to Final Approval, then asserts PullRequestLinks shows a PR link only for
 * the repo the mock agent actually changed — not for the repo it left
 * unchanged (pushed at parity with its default branch, so no PR is possible;
 * see check-prs's branch_adds_commits `ahead == 0` exemption).
 *
 * Uses the two already-seeded, genuinely clonable repos (E2eTestDataSeeder's
 * mock-repo/mock-frontend — `seededRepos(await api.listGitRepos())`), not the
 * `workerRepo` fixture: per its own doc comment and PARALLELISM.md, workerRepo
 * mints a single non-clonable `example.invalid` placeholder, unsuited to a
 * spec that needs the mock agent to actually clone/push two real repos. Mirrors
 * multi-repo-epic.spec.ts's `seededRepos()` + `createRepoGroup` precedent. The
 * run itself is named via `uniqueName()` for cross-worker collision-safety.
 *
 * No product UI code change is needed — PullRequestLinks already renders only
 * the sparse registered RunPullRequest rows with no per-repo placeholder.
 */
test.describe("PullRequestLinks — multi-repo run", () => {
  test("approved run shows a PR link only for the changed repo", async ({
    runMonitorPage,
    page,
    api,
  }) => {
    const reposPage = await api.listGitRepos();
    const seeded = seededRepos(reposPage.content);
    expect(
      seeded.length,
      "E2eTestDataSeeder must seed at least 2 git_repo rows for this spec",
    ).toBeGreaterThanOrEqual(2);
    // Order matters: mock-agent.sh's check_prs_gate scenario always treats the
    // FIRST repo in config.json's repos[] as the one left unchanged (pushed at
    // parity) — and repos[] preserves the RepoGroup's member order.
    const [unchangedRepo, changedRepo] = seeded;

    const groupName = uniqueName("e2e-check-prs-gate");
    const group = await api.createRepoGroup({
      name: groupName,
      memberRepoIds: [unchangedRepo.id, changedRepo.id],
    });

    const template = await api.getTemplateByName("e2e-check-prs-gate");
    const runName = uniqueName("check-prs-gate-run");

    try {
      const run = await api.startRun({
        graphTemplateId: template.id,
        inputs: { software_project_id: group.id },
        name: runName,
      });

      // The scenario clones + pushes two real repos before the gate node
      // reaches awaiting_human — allow more headroom than a same-process
      // mock-success node.
      await api.waitForNodeStatus(run.id, "final_approval", ["awaiting_human"], 120_000);

      await runMonitorPage.goto(run.id);
      await runMonitorPage.selectNode("final_approval");
      await runMonitorPage.approveGate("Approved — verifying PR links render correctly.");

      const finished = await api.waitForRunStatus(run.id, ["completed"], 60_000);
      expect(finished.status).toBe("completed");

      // Backend assertion: exactly one registered PR, and it's for the changed
      // repo — the unchanged (parity) repo must never have one.
      const finalRun = await api.getRun(run.id);
      const pullRequests = (finalRun as unknown as { pullRequests: RunPullRequestResponse[] })
        .pullRequests;
      expect(pullRequests).toHaveLength(1);
      expect(pullRequests[0].gitRepoId).toBe(changedRepo.id);
      expect(pullRequests.some((pr) => pr.gitRepoId === unchangedRepo.id)).toBe(false);

      // UI assertion: PullRequestLinks renders one link, for the changed repo,
      // with no placeholder row for the unchanged repo.
      await page.goto(`/runs/${run.id}`);
      const links = page.getByTestId("pull-request-link");
      await expect(links).toHaveCount(1);
      await expect(links.first()).toContainText(changedRepo.url.split("/").pop() ?? changedRepo.id);
    } finally {
      try {
        await api.deleteRepoGroup(group.id);
      } catch {
        // best-effort cleanup
      }
    }
  });
});
