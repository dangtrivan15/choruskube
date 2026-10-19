import { test, expect, seededRepos } from "../fixtures";
import { RoadmapCandidateGatePage } from "../pages/roadmap-candidate-gate.page";
import { uniqueName, type TestApiClient, type Epic, type Story, type Task } from "../helpers/api-client";
import { findWireMockRequests } from "../helpers/wiremock";

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

/** The owner/name GitHub resolves the seeded mock-repo under — matches seedProject()'s fixture. */
const MOCK_REPO_OWNER_NAME = "e2e-test/mock-repo";

/**
 * A fresh RepoGroup wrapping the seeded, github.com-hosted mock-repo, unique per call.
 *
 * Not the `workerRepo` fixture: "e2e-roadmap-extension-gate"'s input schema declares
 * software_project_id (GraphSnapshotBuilder#resolveGitRepos needs a schema field of that type to
 * find a run's repos for the Worker's scoped token mint — see task-issue-close.spec.ts), so
 * draft_extension, an AI node, now clones whatever that input resolves to. workerRepo is minted
 * under `https://example.invalid/...` on purpose (its own fixture comment) and is never meant to
 * be cloned — pointing this template at it would fail the clone, not exercise gate mechanics.
 */
async function seedProject(api: TestApiClient): Promise<string> {
  const reposPage = await api.listGitRepos();
  const mockRepo = seededRepos(reposPage.content).find((r) => r.url.endsWith("/mock-repo"));
  if (!mockRepo) {
    throw new Error("E2eTestDataSeeder must seed e2e-test/mock-repo for this spec");
  }
  const group = await api.createRepoGroup({
    name: uniqueName("e2e-roadmap-extension"),
    memberRepoIds: [mockRepo.id],
  });
  return group.id;
}

/** A fresh Epic → Story → Task anchor, unique per test so mock-agent.sh's --epic-id
 *  argument (and this spec's own graph assertions) never collide with another test's
 *  fixture in the same project. */
async function seedAnchor(
  api: TestApiClient,
  projectId: string,
): Promise<{ epic: Epic; story: Story; task: Task }> {
  const epic = await api.createEpic({
    title: uniqueName("E2E Extension Anchor"),
    description: "Anchor Epic for roadmap-extension-gate E2E coverage.",
    softwareProjectId: projectId,
  });
  const story = await api.createStory(epic.id, {
    title: uniqueName("E2E Extension Anchor Story"),
    description: "Anchor Story for roadmap-extension-gate E2E coverage.",
  });
  const task = await api.createTask(story.id, {
    title: uniqueName("E2E Extension Anchor Task"),
    description: "Anchor Task for roadmap-extension-gate E2E coverage.",
  });
  return { epic, story, task };
}

/** Starts the extension-gate run anchored to the given Epic and waits for the gate. */
async function startExtensionRun(
  api: TestApiClient,
  projectId: string,
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
      software_project_id: projectId,
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
  }) => {
    const projectId = await seedProject(api);
    const { epic, task } = await seedAnchor(api, projectId);
    const { run, runName } = await startExtensionRun(api, projectId, epic.id, "false", "rx-approve");

    const gatePage = new RoadmapCandidateGatePage(page);
    await gatePage.goto();
    const card = await gatePage.waitForGateCard(runName);

    // Anchors render read-only, under the "extension" (not "breakdown") heading.
    await expect(gatePage.breakdownHeading(card)).toContainText("Proposed Roadmap Extension");
    await expect(gatePage.existingBadges(card).first()).toBeVisible();
    await expect(gatePage.existingTitle(card, 0)).toHaveText(epic.title);

    const epicsBefore = await api.listEpicsForProject(projectId);

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
    const epicsAfter = await api.listEpicsForProject(projectId);
    expect(epicsAfter.map((e) => e.id).sort()).toEqual(epicsBefore.map((e) => e.id).sort());

    // The project resolves to a real github.com repo, so the Worker's FileGateIssues activity
    // actually files a GitHub issue for each new Task. IssueFiling's non-GitHub skip path is
    // covered by DefaultRoadmapCandidateMaterializerTest and GitHubTokenScopeResolverTest
    // instead — this template's software_project_id input must resolve to a repo
    // draft_extension (an AI node) can actually clone, which rules out the workerRepo fixture
    // (see seedProject()). The close half of this lifecycle is task-issue-close.spec.ts's job.
    await expect(async () => {
      const filed = await findWireMockRequests({
        method: "POST",
        urlPathPattern: `/repos/${MOCK_REPO_OWNER_NAME}/issues`,
        bodyContains: "choruskube-task:" + followUpTask!.id,
      });
      expect(filed.length).toBeGreaterThan(0);
    }).toPass({ timeout: 60_000 });
  });

  test("removing the anchor from the proposal before approving creates nothing", async ({ page, api }) => {
    const projectId = await seedProject(api);
    const { epic, story, task } = await seedAnchor(api, projectId);
    const { run, runName } = await startExtensionRun(api, projectId, epic.id, "false", "rx-clear");

    const gatePage = new RoadmapCandidateGatePage(page);
    await gatePage.goto();
    const card = await gatePage.waitForGateCard(runName);
    await expect(gatePage.existingBadges(card).first()).toBeVisible();

    // Removing the anchor Epic entry also drops the dependency edges nested under it
    // (RoadmapCandidateBreakdown's pruneDependencies) — nothing is left to materialize.
    // Asserted here because the server skips dangling edges at approve time, so a pruning
    // regression would not show up in the run's outcome.
    await expect(card.getByTestId("candidate-dependencies")).toBeVisible();
    await gatePage.removeEpic(card, 0);
    await expect(card.getByTestId("candidate-dependencies")).toHaveCount(0);
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
  }) => {
    test.setTimeout(120_000);

    const projectId = await seedProject(api);
    const { epic, story, task } = await seedAnchor(api, projectId);
    const { run, runName } = await startExtensionRun(api, projectId, epic.id, "false", "rx-invalid");

    const gatePage = new RoadmapCandidateGatePage(page);
    await gatePage.goto();
    const card = await gatePage.waitForGateCard(runName);

    // The anchor Epic's proposed stories are, in order: [0] the existing anchor Story
    // (existingId), [1] the mock agent's own new "Mock Deferred Story" — addStory appends
    // after both, so the story this test adds lands at index 2.
    const invalidTitle = uniqueName("Invalid No-Task Story");
    await gatePage.addStory(card, 0);
    await gatePage.fillNewStoryTitle(card, invalidTitle, 0, 2);
    await gatePage.approve(card);

    // The proposal is validated before the approval claims the gate, so the reason comes back
    // on the click and the reviewer's edits stay on the card to fix.
    const errorToast = page
      .locator('[data-sonner-toast][data-type="error"]')
      .filter({ hasText: "a new story needs at least one task" });
    await expect(errorToast).toBeVisible();
    await expect(gatePage.storyTitleInput(card, 0, 2)).toHaveValue(invalidTitle);

    // Nothing is claimed or materialized: the node stays awaiting a decision.
    await api.waitForNodeStatus(run.id, "final_approval", ["awaiting_human"], 15_000, 1_000);
    const untouched = await api.getGraph(epic.id);
    expect(untouched.stories.map((s) => s.id)).toEqual([story.id]);
    expect(untouched.tasks.map((t) => t.id)).toEqual([task.id]);

    await gatePage.removeStory(card, 0, 2);
    await gatePage.approve(card);

    await expect(
      page.locator("[data-sonner-toast]").filter({ hasText: "Finalizing your approval…" }),
    ).toBeVisible();
    const finished = await api.waitForRunStatus(run.id, ["completed"], 60_000);
    expect(finished.status).toBe("completed");

    // The corrected proposal is the one written: the mock agent's story, not the rejected one.
    const storyTitles = (await api.getGraph(epic.id)).stories.map((s) => s.title);
    expect(storyTitles).toContain("Mock Deferred Story");
    expect(storyTitles).not.toContain(invalidTitle);
  });

  test("include_new_epic also materializes a wholly new top-level Epic alongside the anchor's own extension", async ({
    page,
    api,
  }) => {
    const projectId = await seedProject(api);
    const { epic, task } = await seedAnchor(api, projectId);
    const { run, runName } = await startExtensionRun(api, projectId, epic.id, "true", "rx-newepic");

    const gatePage = new RoadmapCandidateGatePage(page);
    await gatePage.goto();
    const card = await gatePage.waitForGateCard(runName);

    const epicsBefore = await api.listEpicsForProject(projectId);

    await gatePage.approve(card);

    const finished = await api.waitForRunStatus(run.id, ["completed"], 60_000);
    expect(finished.status).toBe("completed");

    const epicsAfter = await api.listEpicsForProject(projectId);
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
  // left behind in its own seedProject() RepoGroup, matching run-lifecycle.spec.ts's
  // "started Tasks/Epics can't be cleanly deleted anyway" convention. uniqueName() keeps
  // each test's fixtures from colliding with another's.
});
