import { describe, it, expect } from "vitest";
import { executionScopedOperations, expandPath, missingFloorRoutes, FLOOR_ROUTES } from "./agent-routes";

function apiDocs(paths: Record<string, Record<string, unknown>>) {
  return { openapi: "3.0.1", paths };
}

describe("executionScopedOperations", () => {
  it("keeps the bare execution path, and every sub-path under it, with each HTTP method", () => {
    const docs = apiDocs({
      "/internal/runs/{runId}/node-executions/{nodeExecId}": { get: {} },
      "/internal/runs/{runId}/node-executions/{nodeExecId}/status": { put: {} },
      "/internal/runs/{runId}/node-executions/{nodeExecId}/gate-finalize/{approvalId}/complete": { post: {} },
    });

    const ops = executionScopedOperations(docs);

    expect(ops).toEqual(
      expect.arrayContaining([
        { method: "GET", template: "/internal/runs/{runId}/node-executions/{nodeExecId}" },
        { method: "PUT", template: "/internal/runs/{runId}/node-executions/{nodeExecId}/status" },
        {
          method: "POST",
          template: "/internal/runs/{runId}/node-executions/{nodeExecId}/gate-finalize/{approvalId}/complete",
        },
      ]),
    );
    expect(ops).toHaveLength(3);
  });

  it("excludes run-level /internal/runs/{runId}/... routes", () => {
    const docs = apiDocs({
      "/internal/runs/{runId}/status": { put: {} },
      "/internal/runs/{runId}/pull-requests": { get: {} },
    });

    expect(executionScopedOperations(docs)).toEqual([]);
  });

  it("excludes /worker/runs/{runId}/node-executions/{nodeExecId}/... routes", () => {
    const docs = apiDocs({
      "/worker/runs/{runId}/node-executions/{nodeExecId}/status": { put: {} },
    });

    expect(executionScopedOperations(docs)).toEqual([]);
  });

  it("excludes /api/** routes", () => {
    const docs = apiDocs({
      "/api/v1/runs/{runId}/nodes/{nodeExecId}/signal": { post: {} },
    });

    expect(executionScopedOperations(docs)).toEqual([]);
  });

  it("excludes a path that merely contains node-executions elsewhere", () => {
    const docs = apiDocs({
      "/internal/runs/{runId}/other/node-executions/{nodeExecId}": { get: {} },
      "/internal/node-executions/{nodeExecId}/foo": { get: {} },
      "/internal/runs/{runId}/node-executions/{nodeExecId}-suffix": { get: {} },
    });

    expect(executionScopedOperations(docs)).toEqual([]);
  });

  it("emits one operation per HTTP method on a path with several", () => {
    const docs = apiDocs({
      "/internal/runs/{runId}/node-executions/{nodeExecId}/decision": { put: {}, get: {}, delete: {} },
    });

    const ops = executionScopedOperations(docs);
    expect(ops.map((o) => o.method).sort()).toEqual(["DELETE", "GET", "PUT"]);
  });

  it("skips a path item's non-operation keys", () => {
    const docs = apiDocs({
      "/internal/runs/{runId}/node-executions/{nodeExecId}/logs": {
        summary: "logs",
        parameters: [],
        post: {},
      },
    });

    expect(executionScopedOperations(docs)).toEqual([
      { method: "POST", template: "/internal/runs/{runId}/node-executions/{nodeExecId}/logs" },
    ]);
  });

  it("returns nothing for a malformed or missing paths map", () => {
    expect(executionScopedOperations(undefined)).toEqual([]);
    expect(executionScopedOperations({})).toEqual([]);
    expect(executionScopedOperations({ paths: null })).toEqual([]);
  });
});

describe("expandPath", () => {
  it("puts the given run and execution ids in place, and a fresh UUID in every other {var}, with no { left", () => {
    const runId = "11111111-1111-1111-1111-111111111111";
    const nodeExecId = "22222222-2222-2222-2222-222222222222";
    const expanded = expandPath(
      "/internal/runs/{runId}/node-executions/{nodeExecId}/gate-finalize/{approvalId}/complete",
      runId,
      nodeExecId,
    );

    expect(expanded).toContain(`/internal/runs/${runId}/node-executions/${nodeExecId}/gate-finalize/`);
    expect(expanded.endsWith("/complete")).toBe(true);
    expect(expanded).not.toContain("{");

    const approvalId = expanded.split("/gate-finalize/")[1].split("/complete")[0];
    expect(approvalId).not.toBe(runId);
    expect(approvalId).not.toBe(nodeExecId);
    expect(approvalId).toMatch(/^[0-9a-f-]{36}$/);
  });

  it("mints a different random id on each call for an unrelated variable", () => {
    const runId = "11111111-1111-1111-1111-111111111111";
    const nodeExecId = "22222222-2222-2222-2222-222222222222";
    const template = "/internal/runs/{runId}/node-executions/{nodeExecId}/tasks/{taskId}/status";

    const first = expandPath(template, runId, nodeExecId);
    const second = expandPath(template, runId, nodeExecId);

    expect(first).not.toBe(second);
  });
});

describe("missingFloorRoutes", () => {
  it("returns the absent floor entries", () => {
    const partial = FLOOR_ROUTES.slice(1);
    const missing = missingFloorRoutes(partial);

    expect(missing).toEqual([`${FLOOR_ROUTES[0].method} ${FLOOR_ROUTES[0].template}`]);
  });

  it("returns none when all floor routes are present", () => {
    expect(missingFloorRoutes(FLOOR_ROUTES)).toEqual([]);
  });

  it("is unaffected by extra, non-floor operations being present", () => {
    const extra = { method: "GET", template: "/internal/runs/{runId}/node-executions/{nodeExecId}/graph" };
    expect(missingFloorRoutes([...FLOOR_ROUTES, extra])).toEqual([]);
  });
});
