/**
 * agent-routes.ts — pure route-enumeration helpers for `specs/agent-run-scope.spec.ts`.
 *
 * No `@playwright/test` import: this module is Vitest-unit-tested on its own
 * (`agent-routes.test.ts`) against a hand-built `/v3/api-docs` shape, with no running stack.
 */
import { randomUUID } from "node:crypto";

export interface AgentOperation {
  method: string;
  template: string;
}

/**
 * Every agent route lives under this one path -- `InternalRunController` and
 * `InternalArtifactController`'s node-execution-scoped endpoints. A route elsewhere in
 * `/internal/**`, or under `/worker/runs/{runId}/node-executions/{nodeExecId}`, is reachable only
 * with the orchestrator secret or a Worker's Fleet credential, never an agent's `JOB_SECRET`, so
 * this spec has no probe for it.
 */
export const EXECUTION_SCOPED_PREFIX = "/internal/runs/{runId}/node-executions/{nodeExecId}";

const HTTP_METHODS = new Set(["get", "put", "post", "delete", "patch", "options", "head"]);

/**
 * The well-known agent routes the enumeration must never miss. If `/v3/api-docs` ever stops
 * listing one of these -- a springdoc config change, a route moved behind a different base path
 * -- the spec fails loudly instead of silently probing fewer routes than it claims to.
 */
export const FLOOR_ROUTES: AgentOperation[] = [
  { method: "PUT", template: `${EXECUTION_SCOPED_PREFIX}/status` },
  { method: "GET", template: `${EXECUTION_SCOPED_PREFIX}/presign` },
  { method: "PUT", template: `${EXECUTION_SCOPED_PREFIX}/decision` },
  { method: "GET", template: `${EXECUTION_SCOPED_PREFIX}/valid-decisions` },
  { method: "POST", template: `${EXECUTION_SCOPED_PREFIX}/logs` },
  { method: "POST", template: `${EXECUTION_SCOPED_PREFIX}/pull-requests` },
  { method: "POST", template: `${EXECUTION_SCOPED_PREFIX}/repo-bases` },
  { method: "POST", template: `${EXECUTION_SCOPED_PREFIX}/gate-finalize/{approvalId}/complete` },
];

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function isExecutionScoped(path: string): boolean {
  return path === EXECUTION_SCOPED_PREFIX || path.startsWith(`${EXECUTION_SCOPED_PREFIX}/`);
}

/**
 * Every operation from a `/v3/api-docs` document (OpenAPI's `paths` map) whose path is the
 * execution-scoped prefix itself, or sits under it -- a run-level `/internal/runs/{runId}/...`
 * route, a `/worker/...` route, an `/api/**` route, or a path that merely contains
 * `node-executions` elsewhere, are all excluded.
 */
export function executionScopedOperations(apiDocs: unknown): AgentOperation[] {
  const paths = isRecord(apiDocs) && isRecord(apiDocs.paths) ? apiDocs.paths : {};
  const ops: AgentOperation[] = [];
  for (const [path, methods] of Object.entries(paths)) {
    if (!isExecutionScoped(path) || !isRecord(methods)) {
      continue;
    }
    for (const method of Object.keys(methods)) {
      if (HTTP_METHODS.has(method.toLowerCase())) {
        ops.push({ method: method.toUpperCase(), template: path });
      }
    }
  }
  return ops;
}

/** `{runId}` and `{nodeExecId}` take the given ids; every other `{var}` gets a fresh random UUID. */
export function expandPath(template: string, runId: string, nodeExecId: string): string {
  return template.replace(/\{[^}]+\}/g, (segment) => {
    if (segment === "{runId}") return runId;
    if (segment === "{nodeExecId}") return nodeExecId;
    return randomUUID();
  });
}

/** The {@link FLOOR_ROUTES} entries missing from `ops`, as `"METHOD template"`; empty if none are. */
export function missingFloorRoutes(ops: AgentOperation[]): string[] {
  const present = new Set(ops.map((op) => `${op.method} ${op.template}`));
  return FLOOR_ROUTES.map((route) => `${route.method} ${route.template}`).filter(
    (label) => !present.has(label),
  );
}
