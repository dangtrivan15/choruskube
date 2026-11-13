/**
 * Live proof that a real agent's `JOB_SECRET` reaches nothing outside its own run -- the
 * in-process `RunScopeRouteAudit` fixture proves the same rules against mock requests, but no
 * other test presents a real secret, minted for a real execution, to the running stack. See
 * ARCHITECTURE.md's "Agent-route authentication" section for the layers this spec probes
 * (the auth filters and the run-scope guard) and docs/decisions/ for why this spec reads real
 * secrets from containers rather than fabricating one.
 */
import { test, expect } from "../fixtures";
import { uniqueName } from "../helpers/api-client";
import { agentWorkload } from "../helpers/docker";
import {
  executionScopedOperations,
  expandPath,
  missingFloorRoutes,
  EXECUTION_SCOPED_PREFIX,
} from "../helpers/agent-routes";

const API_BASE_URL = process.env.E2E_API_URL ?? "http://localhost:28080";

const WRITE_METHODS = new Set(["POST", "PUT", "PATCH", "DELETE"]);

interface ProbeResult {
  status: number;
}

/**
 * One bare `fetch`, never Playwright's `request` fixture -- that fixture traces every call it
 * makes into the test's report/HAR, which would print a real job secret there. A write method
 * always sends a body (`{}` unless the caller supplies one), matching what every write route
 * here actually expects.
 */
async function probe(method: string, path: string, bearer: string, body?: unknown): Promise<ProbeResult> {
  const headers: Record<string, string> = { Authorization: `Bearer ${bearer}` };
  let requestBody: string | undefined;
  if (WRITE_METHODS.has(method)) {
    requestBody = JSON.stringify(body ?? {});
    headers["Content-Type"] = "application/json";
  }
  const res = await fetch(`${API_BASE_URL}${path}`, { method, headers, body: requestBody });
  // Draining the body (without keeping it) is required for Node's fetch to release the
  // connection back to its pool; this spec never needs the response's content.
  await res.text();
  return { status: res.status };
}

/** `path`'s own run-storage prefix (its first segment) replaced, so it sits outside every run's tree. */
function outsideStoragePrefix(path: string): string {
  const segments = path.split("/");
  segments[0] = `${segments[0]}-outside`;
  return segments.join("/");
}

test.describe("Agent run-scope boundary", () => {
  test("a job secret from one run is refused on every agent route outside that run", async ({
    api,
    workerRepo,
  }) => {
    // Stays within branch_b's `slow --delay 120` (E2eTestDataSeeder#seedParallelFanout) so both
    // runs' agent containers are still alive for every probe below.
    test.setTimeout(120_000);

    const template = await api.getTemplateByName("e2e-parallel-fanout");
    const runA = await api.startRun({ graphTemplateId: template.id, name: uniqueName("run-scope-a") });
    const runB = await api.startRun({ graphTemplateId: template.id, name: uniqueName("run-scope-b") });

    try {
      const [branchBInA, branchBInB] = await Promise.all([
        api.waitForNodeStatus(runA.id, "branch_b", ["running"], 60_000),
        api.waitForNodeStatus(runB.id, "branch_b", ["running"], 60_000),
      ]);
      const execIdA = branchBInA.id;
      const execIdB = branchBInB.id;

      const [workloadA, workloadB] = await Promise.all([agentWorkload(execIdA), agentWorkload(execIdB)]);
      const secretA = workloadA.jobSecret;

      const apiDocsRes = await fetch(`${API_BASE_URL}/v3/api-docs`);
      expect(apiDocsRes.ok).toBe(true);
      const ops = executionScopedOperations(await apiDocsRes.json());

      // Non-vacuity floor: fail loudly if the enumeration ever misses a well-known agent route,
      // rather than silently probing fewer routes than this spec claims to.
      expect(missingFloorRoutes(ops)).toEqual([]);

      const mismatches: string[] = [];
      const check = async (label: string, expected: number, method: string, path: string, body?: unknown) => {
        const { status } = await probe(method, path, secretA, body);
        if (status !== expected) {
          mismatches.push(`${label}: expected ${expected}, got ${status}`);
        }
      };

      for (const op of ops) {
        const label = `${op.method} ${op.template}`;

        await check(
          `${label} [B's execution in B's path]`,
          401,
          op.method,
          expandPath(op.template, runB.id, execIdB),
        );
        await check(
          `${label} [B's execution in A's path]`,
          401,
          op.method,
          expandPath(op.template, runA.id, execIdB),
        );
        // The run-scope guard answers this one today (the filter doesn't yet compare runs); a
        // later Task that fixes the filter moves this expectation to 401.
        await check(
          `${label} [A's execution in B's path]`,
          404,
          op.method,
          expandPath(op.template, runB.id, execIdA),
        );
      }

      // Positive control: a broken secret (or a route expanded wrongly above) cannot pass every
      // refusal by accident if it also fails the one case that must succeed.
      await check(
        "GET positive control",
        200,
        "GET",
        expandPath(EXECUTION_SCOPED_PREFIX, runA.id, execIdA),
      );
      const presignControlPath =
        expandPath(`${EXECUTION_SCOPED_PREFIX}/presign`, runA.id, execIdA) +
        `?path=${encodeURIComponent(`${workloadA.outputPath}probe.txt`)}&method=GET`;
      await check("GET presign positive control", 200, "GET", presignControlPath);

      // Body-reference probes, all on run A's own path with run A's secret.
      const statusPath = expandPath(`${EXECUTION_SCOPED_PREFIX}/status`, runA.id, execIdA);
      await check("PUT status [artifactRefs in B's tree]", 400, "PUT", statusPath, {
        status: "running",
        artifactRefs: JSON.stringify({ out: `${workloadB.outputPath}x` }),
      });
      await check("PUT status [artifactRefs outside A's storage prefix]", 400, "PUT", statusPath, {
        status: "running",
        artifactRefs: JSON.stringify({ out: `${outsideStoragePrefix(workloadA.outputPath)}x` }),
      });

      const pullRequestsPath = expandPath(`${EXECUTION_SCOPED_PREFIX}/pull-requests`, runA.id, execIdA);
      await check("POST pull-requests [repo outside A]", 404, "POST", pullRequestsPath, {
        gitRepoId: workerRepo.gitRepo.id,
        prUrl: "https://example.invalid/e2e-run-scope/pull/1",
      });

      const repoBasesPath = expandPath(`${EXECUTION_SCOPED_PREFIX}/repo-bases`, runA.id, execIdA);
      await check("POST repo-bases [repo outside A]", 404, "POST", repoBasesPath, {
        repos: [{ gitRepoId: workerRepo.gitRepo.id, outcome: "unavailable" }],
      });

      const presignOutsidePath =
        expandPath(`${EXECUTION_SCOPED_PREFIX}/presign`, runA.id, execIdA) +
        `?path=${encodeURIComponent(`${workloadB.outputPath}x`)}&method=GET`;
      await check("GET presign [B's object]", 403, "GET", presignOutsidePath);

      expect(mismatches).toEqual([]);
    } finally {
      await api.cancelRun(runA.id).catch(() => {});
      await api.cancelRun(runB.id).catch(() => {});
    }
  });
});
