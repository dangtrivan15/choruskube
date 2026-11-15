import { test as setup } from "@playwright/test";
import { TestApiClient } from "../helpers/api-client";
import {
  clearWorkloadLifetimeRunId,
  saveWorkloadLifetimeRunId,
  startWorkloadLifetimeRun,
} from "../helpers/workload-lifetime";

/**
 * workload-lifetime.spec.ts waits out the Worker's retention of a finished DinD run, about five
 * minutes on this stack. Starting that run here, before any other spec, lets the wait pass while
 * the rest of the suite runs; the spec only asserts the outcome.
 */
setup("start the DinD run workload-lifetime.spec.ts watches being reclaimed", async () => {
  // A failed setup test skips every spec behind the setup project. This is only a head start, so a
  // failure leaves no run id behind, and the spec starts the run itself and reports the error.
  clearWorkloadLifetimeRunId();
  try {
    saveWorkloadLifetimeRunId((await startWorkloadLifetimeRun(new TestApiClient())).id);
  } catch (err) {
    console.warn(`workload-lifetime.spec.ts will start its own run: ${err}`);
  }
});
