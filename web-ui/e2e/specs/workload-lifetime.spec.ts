import { test, expect } from "../fixtures";
import { workloadObjectsForRun, stagingDirsFor } from "../helpers/docker";
import { loadWorkloadLifetimeRunId, startWorkloadLifetimeRun } from "../helpers/workload-lifetime";

// Serial, reclaim test first: the DinD run's sidecars must be gone before "Failure logs" starts a
// failed run on the same Docker daemon, or they starve FetchPodLogs's bounded retry budget (three
// 30s attempts, see worker/executor.LogGrace) and lose the captured output that test asserts on.
test.describe.configure({ mode: "serial" });

/**
 * Proves the Docker executor's own deadline/retention bounds, not the (now-deleted)
 * orchestrator reconciler, reclaim a run's agent containers, DinD sidecars, volumes and staging
 * directories — see docs/decisions/2026-11-09---02-launching-executor-bounds-workload-lifetime.md.
 *
 * The e2e stack's Worker sets a short `WORKLOAD_RETENTION` (30s), but `e2e-dind-isolation`'s two
 * nodes carry a 120s activity timeout, so each node's heartbeat-timeout floor
 * (`executor.Bounds.Retention`) stamps a ~4-minute retention regardless, which the janitor's own
 * 1-minute sweep interval extends. `workload-lifetime.setup.ts` starts the run before the rest of
 * the suite so that wait has usually passed by the time this test runs. Under a config without that
 * setup file, the test starts the run itself; the poll below budgets for the whole wait either way.
 */
test.describe("Docker workload lifetime", () => {
  test("a completed run's agent containers, DinD sidecars, volumes and staging directories are all reclaimed", async ({
    api,
  }) => {
    test.slow();
    test.setTimeout(9 * 60_000);

    const runId = loadWorkloadLifetimeRunId() ?? (await startWorkloadLifetimeRun(api)).id;

    const finished = await api.waitForRunStatus(runId, ["completed"], 120_000);
    expect(finished.status).toBe("completed");

    const execIds = finished.nodeExecutions.map((ne) => ne.id);

    await expect
      .poll(
        async () => {
          const objects = await workloadObjectsForRun(runId);
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
  test("a failed node's log still carries the mock agent's last line", async ({ api, failedRun }) => {
    const failingStep = failedRun.nodeExecutions.find((ne) => ne.label === "failing_step")!;

    // One read relies on failedRun being in awaiting_retry: the workflow writes a failed node's pod
    // logs before it parks the run there, not before it marks the node failed.
    const logs = await api.getNodeLogs(failedRun.id, failingStep.id);
    expect(logs.map((l) => l.message).join("\n")).toContain("Mock agent: timeout scenario");
  });
});
