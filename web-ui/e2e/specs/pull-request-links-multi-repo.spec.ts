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
    // The internal waits below sum past playwright.config.ts's 60s default, so set
    // an explicit budget. verify_and_gate clones + pushes two repos to bare fixtures
    // baked into the claude-code:e2e agent image and reached over a local file://
    // redirect (agent-images/claude-code-e2e/Dockerfile) — not github.com, and not a
    // separate stack service — so it settles in seconds; the budget only needs
    // headroom for container startup and, under parallel workers, contention on the
    // shared stack. (Earlier revisions timed this out at ever-larger budgets not
    // because the node was slow but because the clone could never complete: the
    // stack had no clonable/pushable remote at all for the seeded e2e-test/* repos,
    // so verify_and_gate hung indefinitely and the run never reached this gate.
    // Raising the budget then only moved the failure later.)
    test.setTimeout(180_000);

    const reposPage = await api.listGitRepos();
    const seeded = seededRepos(reposPage.content);
    // Select the two repos by name, NOT by list position. `GET /git-repos` sorts
    // by URL ascending, and E2eTestDataSeeder seeds a THIRD repo
    // (`e2e-test/dind-repo`, docker-enabled) whose URL sorts ahead of both
    // `mock-*` rows — so `seeded[0]` is dind-repo, not mock-repo. Two things then
    // have to agree: mock-agent.sh's check_prs_gate scenario always leaves the
    // FIRST member of the run's config.json `repos[]` (which preserves the
    // RepoGroup member order) unchanged at parity with no PR and changes every
    // other member; and the `github-compare` WireMock stub keys its "ahead"
    // (kept-on-cleanup) response off the `mock-frontend` path. So mock-repo must
    // be the unchanged member and mock-frontend the changed one — pin them by
    // name rather than trusting a sort order a third repo already perturbs.
    const unchangedRepo = seeded.find((r) => r.url.endsWith("/mock-repo"));
    const changedRepo = seeded.find((r) => r.url.endsWith("/mock-frontend"));
    if (!unchangedRepo || !changedRepo) {
      throw new Error(
        "E2eTestDataSeeder must seed both e2e-test/mock-repo and e2e-test/mock-frontend for this spec",
      );
    }

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

      // verify_and_gate clones + pushes both repos to the local file:// git fixtures
      // baked into the agent image and runs check-prs before this gate opens; that
      // takes seconds, so 90s is ample headroom over container startup even under
      // parallel-worker contention.
      await api.waitForNodeStatus(run.id, "final_approval", ["awaiting_human"], 90_000);

      await runMonitorPage.goto(run.id);
      await runMonitorPage.selectNode("final_approval");
      await runMonitorPage.approveGate("Approved — verifying PR links render correctly.");

      const finished = await api.waitForRunStatus(run.id, ["completed"], 90_000);
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
