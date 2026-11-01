import { test, expect, seededRepos } from "../fixtures";
import { RoadmapCandidateGatePage } from "../pages/roadmap-candidate-gate.page";
import { uniqueName } from "../helpers/api-client";
import { findWireMockRequests, headerOf } from "../helpers/wiremock";

/**
 * Proves the Worker, not the API server, closes a completed Task's linked GitHub issue. The
 * Task gets its issue through the roadmap-extension gate, then a person completes it. Under the
 * `control-plane` credential source the API server and the Worker send the same bearer token, so
 * only the Worker's fixed `User-Agent: choruskube-worker` (worker/github/client.go), read back
 * from WireMock's request journal, tells them apart. The journal query counts only closes whose
 * body sets `state: closed`, the one body the close stub answers.
 *
 * Uses the seeded `mock-repo` (a real `github.com` URL), not the `workerRepo` fixture: the
 * roadmap-extension gate's issue filing now skips a Task whose project resolves to a non-GitHub
 * repository (`RepoNameUtil.githubOwnerRepo`), and `workerRepo` is minted under
 * `https://example.invalid/...` on purpose (see its own fixture comment) — a Task there never
 * gets an issue to close.
 */

/** Matches `RepoNameUtil.githubOwnerRepo`: the URL's last two path segments, minus `.git`. */
function ownerRepoOf(url: string): string {
  const segments = url.replace(/\/+$/, "").replace(/\.git$/, "").split("/");
  return segments.slice(-2).join("/");
}

function escapeRegex(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

test.describe("Task issue close", () => {
  test("completing a Task closes its linked GitHub issue from the Worker only", async ({ page, api }) => {
    test.setTimeout(420_000);

    const reposPage = await api.listGitRepos();
    const mockRepo = seededRepos(reposPage.content).find((r) => r.url.endsWith("/mock-repo"));
    if (!mockRepo) {
      throw new Error("E2eTestDataSeeder must seed e2e-test/mock-repo for this spec");
    }
    const group = await api.createRepoGroup({
      name: uniqueName("e2e-issue-close"),
      memberRepoIds: [mockRepo.id],
    });

    const epic = await api.createEpic({
      title: uniqueName("E2E Issue Close Anchor"),
      description: "Anchor Epic for task-issue-close E2E coverage.",
      softwareProjectId: group.id,
    });
    const story = await api.createStory(epic.id, {
      title: uniqueName("E2E Issue Close Anchor Story"),
      description: "Anchor Story for task-issue-close E2E coverage.",
    });
    await api.createTask(story.id, {
      title: uniqueName("E2E Issue Close Anchor Task"),
      description: "Anchor Task for task-issue-close E2E coverage.",
    });

    // Run names are capped at 30 chars server-side, so the uniqueName() prefix stays short.
    const runName = uniqueName("issue-close");
    const template = await api.getTemplateByName("e2e-roadmap-extension-gate");
    const run = await api.startRun({
      graphTemplateId: template.id,
      name: runName,
      inputs: {
        software_project_id: group.id,
        anchor_epic_id: epic.id,
        include_new_epic: "true",
      },
    });
    await api.waitForNodeStatus(run.id, "final_approval", ["awaiting_human"], 60_000);

    const gatePage = new RoadmapCandidateGatePage(page);
    await gatePage.goto();
    const card = await gatePage.waitForGateCard(runName);
    await gatePage.approve(card);

    const finished = await api.waitForRunStatus(run.id, ["completed"], 60_000);
    expect(finished.status).toBe("completed");

    // mock-agent.sh titles the new Epic "Mock New Initiative Epic " + the anchor Epic's id. Its
    // Task has no dependency edge, so it is startable straight away.
    const epics = await api.listEpicsForProject(group.id);
    const newEpic = epics.find((e) => e.title === `Mock New Initiative Epic ${epic.id}`);
    expect(newEpic).toBeTruthy();
    const graph = await api.getGraph(newEpic!.id);
    const task = graph.tasks.find((t) => t.title === "Mock New Initiative Task");
    expect(task).toBeTruthy();

    const ownerRepo = ownerRepoOf(mockRepo.url);
    // Any issue number, not the filing stub's 4242, so a change to that stub keeps this passing.
    const thisRepoCloses = {
      method: "PATCH",
      urlPathPattern: `/repos/${escapeRegex(ownerRepo)}/issues/[0-9]+`,
      bodyContains: '"state":"closed"',
    };
    const before = (await findWireMockRequests(thisRepoCloses)).length;

    // Cancel only once the run is "running": an earlier cancel is overwritten by the
    // orchestrator's own "running" report, and completeTask would then find the run still active.
    await api.approveStorySpec(task!.storyId);
    const started = await api.startTask(task!.id);
    const latestRunId = started.latestRunId!;
    await api.waitForRunStatus(latestRunId, ["running"], 15_000);
    await api.cancelRun(latestRunId);

    const completed = await api.completeTask(task!.id);
    expect(completed.status).toBe("done");

    // The close is a workflow started after the completion commits, so it lands asynchronously.
    await expect(async () => {
      const after = await findWireMockRequests(thisRepoCloses);
      expect(after.length).toBeGreaterThan(before);
    }).toPass({ timeout: 60_000 });

    const closes = await findWireMockRequests(thisRepoCloses);
    for (const req of closes) {
      expect(headerOf(req, "User-Agent")).toBe("choruskube-worker");
    }

    // No issue close on any repo carries another User-Agent: the API server made none of them.
    const allCloses = await findWireMockRequests({
      method: "PATCH",
      urlPathPattern: "/repos/.+/issues/[0-9]+",
    });
    for (const req of allCloses) {
      expect(headerOf(req, "User-Agent")).toBe("choruskube-worker");
    }

    // No cleanup: the anchor Epic and what the gate materializes onto it stay in the project,
    // and the started/completed Task means the RepoGroup itself can't be cleanly deleted either —
    // same reasoning as roadmap-extension-gate.spec.ts's shared workerRepo project. uniqueName()
    // keeps this test's fixtures from colliding with another test's.
  });
});
