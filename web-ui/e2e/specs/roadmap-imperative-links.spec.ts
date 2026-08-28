import { test, expect } from "../fixtures";

// Exercises the imperative agent write surface (the roadmap
// dependencies/priorities/milestones feature): an agent that creates roadmap
// items live — no roadmap_candidates.json artifact, no human gate — via
// create-proposal, create-story, create-task (--priority), create-dependency,
// and create-milestone (+ update-proposal --milestone-id). This is the "E2E
// (imperative)" case the feature's testing strategy calls for, complementing
// roadmap-candidate-gate.spec.ts's declarative/artifact path.
//
// Assumes an "e2e-roadmap-imperative-links" template is seeded by
// E2eTestDataSeeder (api-server): a single script-executor node running
// mock-agent.sh's "roadmap_imperative_links" scenario, which creates one Epic
// (priority high), one Story (priority medium), two Tasks (priority high and
// low respectively, the first blocking the second), one Milestone, and
// assigns the Epic to that Milestone — see mock-agent.sh for the exact shape.
test.describe("Roadmap imperative links (create-task --priority, create-dependency, create-milestone)", () => {
  test("an imperative agent's Task priority, dependency edge, and Milestone assignment are all visible after the run completes", async ({
    roadmapGraphPage,
    api,
    workerRepo,
  }) => {
    const template = await api.getTemplateByName("e2e-roadmap-imperative-links");

    // Scoped to this worker's own software project (workerRepo), so a concurrent
    // worker running the same fixed-title scenario can never land in this diff —
    // mirrors roadmap-candidate-gate.spec.ts's reject test's ID-diff pattern.
    const before = await api.listEpicsForProject(workerRepo.gitRepo.id);

    const run = await api.startRun({
      graphTemplateId: template.id,
      inputs: { software_project_id: workerRepo.gitRepo.id },
    });

    const finished = await api.waitForRunStatus(run.id, ["completed"], 60_000);
    expect(finished.status).toBe("completed");

    const after = await api.listEpicsForProject(workerRepo.gitRepo.id);
    const beforeIds = new Set(before.map((e) => e.id));
    const created = after.find((e) => !beforeIds.has(e.id));
    expect(created).toBeTruthy();
    const epic = created!;

    try {
      // The scenario creates the Epic via create-proposal --priority high, then
      // assigns it to a Milestone via update-proposal --milestone-id once
      // create-milestone has returned a real id (the imperative path has no
      // key-based find-or-create the declarative artifact gets for free).
      expect(epic.priority).toBe("high");
      expect(epic.milestone).not.toBeNull();
      expect(epic.milestone?.name).toBe("Mock Imperative Links Milestone");

      await roadmapGraphPage.goto(epic.id);

      const taskANode = roadmapGraphPage.nodeByLabel("Mock Imperative Links Task A (blocking)");
      const taskBNode = roadmapGraphPage.nodeByLabel("Mock Imperative Links Task B (blocked)");
      await expect(taskANode).toBeVisible();
      await expect(taskBNode).toBeVisible();

      // Task-level priority reaching the graph node badge is exactly
      // the bug H4 fixes — buildInternalNodes() used to hardcode `priority: null`
      // for every Task node regardless of TaskResponse.priority.
      await expect(taskANode.getByTestId("roadmap-graph-node-priority")).toHaveText(/High/);
      await expect(taskBNode.getByTestId("roadmap-graph-node-priority")).toHaveText(/Low/);

      // The imperative create-dependency call materialized a real edge, distinct
      // from the Epic->Story->Task hierarchy edges (roadmapDependencyEdgeId's
      // "dep:" prefix — same assertion pattern as roadmap-graph.spec.ts).
      const dependencyEdge = roadmapGraphPage.page.locator('.react-flow__edge[data-id^="dep:"]');
      await expect(dependencyEdge).toHaveCount(1);
    } finally {
      await api.deleteEpic(epic.id);
    }
  });
});
