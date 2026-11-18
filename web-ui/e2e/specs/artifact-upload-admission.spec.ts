/**
 * Covers `GET .../presign?method=PUT&size=...` admission: a missing/invalid `size` (400), a
 * declaration past the run's upload budget (413, with budget fields in the body), a declaration
 * the store itself then enforces on the actual bytes (see
 * `ObjectStorePresignIntegrationTest#urlSignedForNBytes_acceptsExactlyN_refusesOffByOneAndEmpty`
 * for why only `>= 400` is asserted on the store's own refusal, not a specific code), and a
 * closed run (409) — while a GET presign stays unaffected by both `size` and run-closedness. See
 * `docs/decisions/2026-11-18---01-agent-uploads-are-sized-and-budgeted-per-run.md`.
 */
import { test, expect } from "../fixtures";
import { uniqueName } from "../helpers/api-client";
import { agentWorkload, putFromWorkload } from "../helpers/docker";
import { expandPath, EXECUTION_SCOPED_PREFIX } from "../helpers/agent-routes";

const API_BASE_URL = process.env.E2E_API_URL ?? "http://localhost:28080";

const ONE_TIB_BYTES = (1024 ** 4).toString();

interface PresignProbeResult {
  status: number;
  json: Record<string, unknown>;
}

/**
 * Plain `fetch`, never Playwright's `request` fixture, whose traces would record the secret.
 * `presignPath` is the already-expanded `.../presign` route; `objectPath` and `method` become
 * its `path`/`method` query params, with `size` added only when given.
 */
async function presignProbe(
  presignPath: string,
  objectPath: string,
  bearer: string,
  method: "GET" | "PUT",
  size?: string,
): Promise<PresignProbeResult> {
  const query = new URLSearchParams({ path: objectPath, method });
  if (size !== undefined) {
    query.set("size", size);
  }
  const res = await fetch(`${API_BASE_URL}${presignPath}?${query.toString()}`, {
    headers: { Authorization: `Bearer ${bearer}` },
  });
  const json = (await res.json().catch(() => ({}))) as Record<string, unknown>;
  return { status: res.status, json };
}

test.describe("Artifact upload admission", () => {
  test("size validation, budget admission, store enforcement, and run-closedness", async ({ api }) => {
    // agentWorkload's `docker exec` needs branch_b's container running; its `slow --delay 120`
    // (E2eTestDataSeeder#seedParallelFanout) outlasts this timeout.
    test.setTimeout(120_000);

    const template = await api.getTemplateByName("e2e-parallel-fanout");
    const run = await api.startRun({
      graphTemplateId: template.id,
      name: uniqueName("upload-admission"),
    });

    try {
      const branchB = await api.waitForNodeStatus(run.id, "branch_b", ["running"], 60_000);
      const execId = branchB.id;
      const workload = await agentWorkload(execId);
      const presignPath = expandPath(`${EXECUTION_SCOPED_PREFIX}/presign`, run.id, execId);
      const uploadPath = `${workload.outputPath}sized-upload.txt`;

      // Missing size on a PUT presign.
      const missingSize = await presignProbe(presignPath, uploadPath, workload.jobSecret, "PUT");
      expect(missingSize.status).toBe(400);

      // Declaring far more than the run's upload budget (default 512 MiB).
      const overBudget = await presignProbe(
        presignPath,
        uploadPath,
        workload.jobSecret,
        "PUT",
        ONE_TIB_BYTES,
      );
      expect(overBudget.status).toBe(413);
      expect(overBudget.json.budgetBytes).toBe(536_870_912);
      expect(typeof overBudget.json.bookedBytes).toBe("number");
      // A fresh run's budget is per-run, so nothing else could have booked against it yet.
      expect(overBudget.json.bookedBytes).toBe(0);
      expect(overBudget.json.declaredBytes).toBe(Number(ONE_TIB_BYTES));

      // A declaration the budget admits, signed for exactly 5 bytes.
      const admitted = await presignProbe(presignPath, uploadPath, workload.jobSecret, "PUT", "5");
      expect(admitted.status).toBe(200);
      const signedUrl = admitted.json.url as string;
      expect(typeof signedUrl).toBe("string");

      // The store itself refuses a body whose length doesn't match what was signed.
      const mismatchedStatus = await putFromWorkload(workload.container, signedUrl, "123456");
      expect(mismatchedStatus).toBeGreaterThanOrEqual(400);

      // The same URL still accepts a body at exactly the signed length.
      const matchedStatus = await putFromWorkload(workload.container, signedUrl, "12345");
      expect(matchedStatus).toBeGreaterThanOrEqual(200);
      expect(matchedStatus).toBeLessThan(300);

      await api.cancelRun(run.id);

      // A closed run refuses a PUT presign outright, before it would even look at size/budget.
      const afterCancelPut = await presignProbe(
        presignPath,
        `${workload.outputPath}sized-upload-after-cancel.txt`,
        workload.jobSecret,
        "PUT",
        "5",
      );
      expect(afterCancelPut.status).toBe(409);
      expect(afterCancelPut.json.error).toMatch(/Run is closed/);

      // A GET presign ignores both size and run-closedness.
      const afterCancelGet = await presignProbe(presignPath, uploadPath, workload.jobSecret, "GET");
      expect(afterCancelGet.status).toBe(200);
    } finally {
      await api.cancelRun(run.id).catch(() => {});
    }
  });
});
