import { test, expect } from "../fixtures";
import { RoadmapCandidateGatePage } from "../pages/roadmap-candidate-gate.page";
import { uniqueName, type TestApiClient, type Epic, type Story, type Task } from "../helpers/api-client";
import type { WorkerRepoFixture } from "../fixtures";

// Exercises Feature Development's roadmap-extension gate: an Implement-equivalent node
// proposes new Stories/Tasks anchored under an existing Epic (plus, optionally, a wholly
// new top-level Epic) via the propose-roadmap CLI contract, and Final Approval
// materializes the (possibly reviewer-edited) proposal on approve.
//
// Assumes an "e2e-roadmap-extension-gate" template seeded by E2eTestDataSeeder, mirroring
// Feature Development v43's Final Approval configuration exactly (materialize:
// "roadmap_extension"): a "draft_extension" node running mock-agent.sh's
// "roadmap_extension" scenario (command "roadmap_extension --epic-id {run.anchor_epic_id}
// --new-epic {run.include_new_epic}"), feeding a "final_approval" human gate. The mock
// scenario's document shape (existing-task/follow-up/deferred-story/deferred-task keys,
// the "Mock Deferred Story" / "Mock Follow-up Task" / "Mock Deferred Task" titles, and the
// "Mock New Initiative Epic <anchor id>" wholly-new Epic when --new-epic=true) is pinned by
// agent-images/claude-code/mock-agent.sh — this spec's title assertions must match it
// verbatim.
//
// There is no browser-level E2E for the *task-triggered* extension path itself (starting a
// Task always launches the real AI Feature Development template, which the e2e stack
// cannot drive to Final Approval) — the scope rules for that path are covered by the
// api-server's integration tests against the real v43 gate. This spec covers the gate's
// display/approve/materialize mechanics, which are identical regardless of how the run
// that produced the proposal was started.

/** A fresh Epic → Story → Task anchor, unique per test so mock-agent.sh's --epic-id
 *  argument (and this spec's own graph assertions) never collide with another test's
 *  fixture in the same shared workerRepo project. */
async function seedAnchor(
  api: TestApiClient,
  workerRepo: WorkerRepoFixture,
): Promise<{ epic: Epic; story: Story; task: Task }> {
  const epic = await api.createEpic({
    title: uniqueName("E2E Extension Anchor"),
    description: "Anchor Epic for roadmap-extension-gate E2E coverage.",
    softwareProjectId: workerRepo.gitRepo.id,
  });
  const story = await api.createStory(epic.id, {
    title: uniqueName("E2E Extension Anchor Story"),
    description: "Anchor Story for roadmap-extension-gate E2E coverage.",
  });
  const task = await api.createTask(story.id, {
    title: "E2E Extension Anchor Task",
    description: "Anchor Task for roadmap-extension-gate E2E coverage.",
  });
  return { epic, story, task };
}

/** Starts the extension-gate run anchored to the given Epic and waits for the gate. */
async function startExtensionRun(
  api: TestApiClient,
  workerRepo: WorkerRepoFixture,
  anchorEpicId: string,
  includeNewEpic: "true" | "false",
  namePrefix: string,
) {
  const template = await api.getTemplateByName("e2e-roadmap-extension-gate");
  // Run names are capped at 30 chars server-side (RunService.RUN_NAME_MAX_LENGTH) — see
  // roadmap-candidate-gate.spec.ts's runName comment for why the uniqueName() prefix stays
  // short.
  const runName = uniqueName(namePrefix);
  const run = await api.startRun({
    graphTemplateId: template.id,
    name: runName,
    inputs: {
      software_project_id: workerRepo.gitRepo.id,
      anchor_epic_id: anchorEpicId,
      include_new_epic: includeNewEpic,
    },
  });

  await api.waitForNodeStatus(run.id, "final_approval", ["awaiting_human"], 60_000);
  return { run, runName };
}

test.describe("Roadmap extension gate (Feature Development's Final Approval, mirrored)", () => {
  test("approving materializes the extension's new Stories/Tasks and dependency edges under the anchor Epic", async ({
    page,
    api,
    workerRepo,
  }) => {
    const { epic, task } = await seedAnchor(api, workerRepo);
    const { run, runName } = await startExtensionRun(api, workerRepo, epic.id, "false", "rx-approve");

    const gatePage = new RoadmapCandidateGatePage(page);
    await gatePage.goto();
    const card = await gatePage.waitForGateCard(runName);

    // Anchors render read-only, under the "extension" (not "breakdown") heading.
    await expect(gatePage.breakdownHeading(card)).toContainText("Proposed Roadmap Extension");
    await expect(gatePage.existingBadges(card).first()).toBeVisible();
    await expect(gatePage.existingTitle(card, 0)).toHaveText(epic.title);

    const epicsBefore = await api.listEpicsForProject(workerRepo.gitRepo.id);

    await gatePage.approve(card);

    const finished = await api.waitForRunStatus(run.id, ["completed"], 60_000);
    expect(finished.status).toBe("completed");

    const graph = await api.getGraph(epic.id);
    const storyTitles = graph.stories.map((s) => s.title);
    const taskTitles = graph.tasks.map((t) => t.title);
    expect(storyTitles).toContain("Mock Deferred Story");
    expect(taskTitles).toContain("Mock Follow-up Task");
    expect(taskTitles).toContain("Mock Deferred Task");

    // The dependency mock-agent.sh declares as "existing-task" -> "follow-up" is the run's
    // own (pre-existing) anchor Task blocking the newly materialized "Mock Follow-up Task".
    const followUpTask = graph.tasks.find((t) => t.title === "Mock Follow-up Task");
    expect(followUpTask).toBeTruthy();
    expect(
      graph.dependencies.some(
        (d) => d.blockingItemId === task.id && d.blockedItemId === followUpTask!.id,
      ),
    ).toBe(true);

    // No stray Epic: the anchor is the only Epic this run's project touches.
    const epicsAfter = await api.listEpicsForProject(workerRepo.gitRepo.id);
    expect(epicsAfter.map((e) => e.id).sort()).toEqual(epicsBefore.map((e) => e.id).sort());
  });

  test("removing the anchor from the proposal before approving creates nothing", async ({
    page,
    api,
    workerRepo,
  }) => {
    const { epic, story, task } = await seedAnchor(api, workerRepo);
    const { run, runName } = await startExtensionRun(api, workerRepo, epic.id, "false", "rx-clear");

    const gatePage = new RoadmapCandidateGatePage(page);
    await gatePage.goto();
    const card = await gatePage.waitForGateCard(runName);
    await expect(gatePage.existingBadges(card).first()).toBeVisible();

    // Removing the anchor Epic entry also drops the dependency edges nested under it
    // (RoadmapCandidateBreakdown's pruneDependencies) — nothing is left to materialize.
    await gatePage.removeEpic(card, 0);
    await gatePage.approve(card);

    const finished = await api.waitForRunStatus(run.id, ["completed"], 60_000);
    expect(finished.status).toBe("completed");

    const graph = await api.getGraph(epic.id);
    expect(graph.stories.map((s) => s.id)).toEqual([story.id]);
    expect(graph.tasks.map((t) => t.id)).toEqual([task.id]);
    expect(graph.dependencies).toEqual([]);
  });

  test("an edit that violates the addressable invariant is rejected with the server's reason, then fixed and approved", async ({
    page,
    api,
    workerRepo,
  }) => {
    const { epic } = await seedAnchor(api, workerRepo);
    const { run, runName } = await startExtensionRun(api, workerRepo, epic.id, "false", "rx-invalid");

    const gatePage = new RoadmapCandidateGatePage(page);
    await gatePage.goto();
    const card = await gatePage.waitForGateCard(runName);

    // The anchor Epic's proposed stories are, in order: [0] the existing anchor Story
    // (existingId), [1] the mock agent's own new "Mock Deferred Story" — addStory appends
    // after both, so the story this test adds lands at index 2.
    await gatePage.addStory(card, 0);
    await gatePage.fillNewStoryTitle(card, uniqueName("Invalid No-Task Story"), 0, 2);
    await gatePage.approve(card);

    const errorToast = page
      .locator('[data-sonner-toast][data-type="error"]')
      .filter({ hasText: "a new story needs at least one task" });
    await expect(errorToast).toBeVisible();

    // Approve-time validation failure releases the gate's claim — nothing is materialized
    // and the node stays awaiting a decision.
    await api.waitForNodeStatus(run.id, "final_approval", ["awaiting_human"], 15_000, 1_000);

    await gatePage.removeStory(card, 0, 2);
    await gatePage.approve(card);

    const finished = await api.waitForRunStatus(run.id, ["completed"], 60_000);
    expect(finished.status).toBe("completed");
  });

  test("include_new_epic also materializes a wholly new top-level Epic alongside the anchor's own extension", async ({
    page,
    api,
    workerRepo,
  }) => {
    const { epic, task } = await seedAnchor(api, workerRepo);
    const { run, runName } = await startExtensionRun(api, workerRepo, epic.id, "true", "rx-newepic");

    const gatePage = new RoadmapCandidateGatePage(page);
    await gatePage.goto();
    const card = await gatePage.waitForGateCard(runName);

    const epicsBefore = await api.listEpicsForProject(workerRepo.gitRepo.id);

    await gatePage.approve(card);

    const finished = await api.waitForRunStatus(run.id, ["completed"], 60_000);
    expect(finished.status).toBe("completed");

    const epicsAfter = await api.listEpicsForProject(workerRepo.gitRepo.id);
    const beforeIds = new Set(epicsBefore.map((e) => e.id));
    const newEpics = epicsAfter.filter((e) => !beforeIds.has(e.id));
    expect(newEpics).toHaveLength(1);

    // mock-agent.sh titles the new Epic "Mock New Initiative Epic " + the anchor Epic's id.
    expect(newEpics[0].title).toBe(`Mock New Initiative Epic ${epic.id}`);

    const newEpicGraph = await api.getGraph(newEpics[0].id);
    expect(newEpicGraph.stories.map((s) => s.title)).toContain("Mock New Initiative Story");
    expect(newEpicGraph.tasks.map((t) => t.title)).toContain("Mock New Initiative Task");

    // The anchor Epic's own extension is unaffected by the wholly new Epic — same shape as
    // the plain-approve test above.
    const anchorGraph = await api.getGraph(epic.id);
    const anchorTaskTitles = anchorGraph.tasks.map((t) => t.title);
    expect(anchorGraph.stories.map((s) => s.title)).toContain("Mock Deferred Story");
    expect(anchorTaskTitles).toContain("Mock Follow-up Task");
    expect(anchorTaskTitles).toContain("Mock Deferred Task");
    const followUpTask = anchorGraph.tasks.find((t) => t.title === "Mock Follow-up Task");
    expect(followUpTask).toBeTruthy();
    expect(
      anchorGraph.dependencies.some(
        (d) => d.blockingItemId === task.id && d.blockedItemId === followUpTask!.id,
      ),
    ).toBe(true);
  });

  // No cleanup: every test's anchor Epic (plus whatever the gate materializes onto it) is
  // left behind in the shared workerRepo project, matching run-lifecycle.spec.ts's
  // "started Tasks/Epics can't be cleanly deleted anyway" convention. uniqueName() keeps
  // each test's fixtures from colliding with another's.
});
