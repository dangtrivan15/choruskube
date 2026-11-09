import { test, expect, seededRepos } from "../fixtures";
import { uniqueName } from "../helpers/api-client";
import { workloadObjectsForRun, stagingDirsFor } from "../helpers/docker";

// Both describe blocks below share one Worker process and one Docker daemon.
// `fullyParallel: true` would otherwise let the "Docker workload lifetime" test's DinD
// sidecars run concurrently with "Failure logs", starving FetchPodLogs's bounded retry
// budget (three 30s attempts, see worker/executor.LogGrace) of real Docker-daemon
// responsiveness and losing the captured output the latter asserts on. Serial keeps this
// file's tests on one worker (see PARALLELISM.md for the same mitigation elsewhere).
test.describe.configure({ mode: "serial" });

/**
 * Proves the Docker executor's own deadline/retention bounds, not the (now-deleted)
 * orchestrator reconciler, reclaim a run's agent containers, DinD sidecars, volumes and staging
 * directories — see docs/decisions/2026-11-09---01-launching-executor-bounds-workload-lifetime.md.
 *
 * The e2e stack's Worker sets a short `WORKLOAD_RETENTION` (30s), but `e2e-dind-isolation`'s two
 * nodes carry a 120s activity timeout, so each node's heartbeat-timeout floor
 * (`executor.Bounds.Retention`) stamps a ~4-minute retention regardless. The poll below budgets
 * for that floor plus the janitor's own 1-minute sweep interval.
 */
test.describe("Docker workload lifetime", () => {
  test("a completed run's agent containers, DinD sidecars, volumes and staging directories are all reclaimed", async ({
    api,
  }) => {
    test.slow();
    test.setTimeout(9 * 60_000);

    const repos = await api.listGitRepos();
    const dindRepo = seededRepos(repos.content).find((r) => r.url.endsWith("/dind-repo"));
    if (!dindRepo) {
      throw new Error("E2eTestDataSeeder must seed the DinD-enabled e2e-test/dind-repo for this spec");
    }

    const template = await api.getTemplateByName("e2e-dind-isolation");
    const run = await api.startRun({
      graphTemplateId: template.id,
      name: uniqueName("dind-lifetime"),
      inputs: { git_repo_id: dindRepo.id },
    });

    const finished = await api.waitForRunStatus(run.id, ["completed"], 120_000);
    expect(finished.status).toBe("completed");

    const execIds = finished.nodeExecutions.map((ne) => ne.id);

    await expect
      .poll(
        async () => {
          const objects = await workloadObjectsForRun(run.id);
          const stagingDirs = await stagingDirsFor(execIds);
          return objects.containers.length === 0 && objects.volumes.length === 0 && stagingDirs.length === 0;
        },
        {
          timeout: 7 * 60_000,
          intervals: [5_000],
        },
      )
      .toBe(true);
  });
});

test.describe("Failure logs", () => {
  test("a failed node's log still carries the mock agent's last line", async ({ api }) => {
    test.slow();

    const template = await api.getTemplateByName("e2e-failure-pipeline");
    const run = await api.startRun({
      graphTemplateId: template.id,
      name: uniqueName("dind-lifetime-failure-logs"),
    });

    const failingStep = await api.waitForNodeStatus(run.id, "failing_step", ["failed"], 120_000);

    const logs = await api.getNodeLogs(run.id, failingStep.id);
    const combined = logs.map((l) => l.message).join("\n");
    expect(combined).toContain("Mock agent: timeout scenario");
  });
});
