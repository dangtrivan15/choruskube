import { test, expect } from "../fixtures";
import { RoadmapCandidateGatePage } from "../pages/roadmap-candidate-gate.page";
import { uniqueName } from "../helpers/api-client";
import { findWireMockRequests, headerOf } from "../helpers/wiremock";

/**
 * Proves the Worker, not the API server, closes a completed Task's linked GitHub issue. The
 * Task gets its issue through the roadmap-extension gate, then a person completes it. Under the
 * `control-plane` credential source the API server and the Worker send the same bearer token, so
 * only the Worker's fixed `User-Agent: choruskube-worker` (worker/github/client.go), read back
 * from WireMock's request journal, tells them apart. The request body is pinned by the Worker
 * client's unit test; the close stub answers only a `state: closed` body.
 */

/** Matches `RepoNameUtil.deriveOwnerRepoName`: the URL's last two path segments, minus `.git`. */
function ownerRepoOf(url: string): string {
  const segments = url.replace(/\/+$/, "").replace(/\.git$/, "").split("/");
  return segments.slice(-2).join("/");
}

function escapeRegex(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

test.describe("Task issue close", () => {
  test("completing a Task closes its linked GitHub issue from the Worker only", async ({
    page,
    api,
    workerRepo,
  }) => {
    test.setTimeout(240_000);

    const epic = await api.createEpic({
      title: uniqueName("E2E Issue Close Anchor"),
      description: "Anchor Epic for task-issue-close E2E coverage.",
      softwareProjectId: workerRepo.gitRepo.id,
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
        software_project_id: workerRepo.gitRepo.id,
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
    const epics = await api.listEpicsForProject(workerRepo.gitRepo.id);
    const newEpic = epics.find((e) => e.title === `Mock New Initiative Epic ${epic.id}`);
    expect(newEpic).toBeTruthy();
    const graph = await api.getGraph(newEpic!.id);
    const task = graph.tasks.find((t) => t.title === "Mock New Initiative Task");
    expect(task).toBeTruthy();

    const ownerRepo = ownerRepoOf(workerRepo.gitRepo.url);
    // Any issue number, not the filing stub's 4242, so a change to that stub keeps this passing.
    const thisRepoCloses = {
      method: "PATCH",
      urlPathPattern: `/repos/${escapeRegex(ownerRepo)}/issues/[0-9]+`,
    };
    const before = (await findWireMockRequests(thisRepoCloses)).length;

    // Cancel only once the run is "running": an earlier cancel is overwritten by the
    // orchestrator's own "running" report, and completeTask would then find the run still active.
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

    // No cleanup: the anchor Epic and what the gate materializes onto it stay in the shared
    // workerRepo project, as in roadmap-extension-gate.spec.ts — a started Task cannot be
    // cleanly deleted. uniqueName() keeps them from colliding with another test's fixtures.
  });
});
