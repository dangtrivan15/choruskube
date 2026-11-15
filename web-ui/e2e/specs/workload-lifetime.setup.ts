import { test as setup } from "@playwright/test";
import { TestApiClient } from "../helpers/api-client";
import { saveWorkloadLifetimeRunId, startWorkloadLifetimeRun } from "../helpers/workload-lifetime";

/**
 * workload-lifetime.spec.ts waits out the Worker's retention of a finished DinD run, about five
 * minutes on this stack. Starting that run here, before any other spec, lets the wait pass while
 * the rest of the suite runs; the spec only asserts the outcome.
 */
setup("start the DinD run workload-lifetime.spec.ts watches being reclaimed", async () => {
  const run = await startWorkloadLifetimeRun(new TestApiClient());
  saveWorkloadLifetimeRunId(run.id);
});
