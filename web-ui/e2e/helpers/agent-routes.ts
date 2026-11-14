/** agent-routes.ts — route enumeration for `specs/agent-run-scope.spec.ts`, unit-tested without a stack. */
import { randomUUID } from "node:crypto";

export interface AgentOperation {
  method: string;
  template: string;
}

/**
 * `InternalAuthFilter` accepts a `JOB_SECRET` only on a path naming its execution in this shape, so
 * every route an agent can reach sits under it. Narrowing it silently drops agent routes from the probe.
 */
export const EXECUTION_SCOPED_PREFIX = "/internal/runs/{runId}/node-executions/{nodeExecId}";

const HTTP_METHODS = new Set(["get", "put", "post", "delete", "patch", "options", "head"]);

/**
 * Agent routes the enumeration must never miss: without this floor, an API description that stops
 * listing them would leave the spec passing while it probes fewer routes than it claims to.
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

/** Every operation in an OpenAPI document whose path is {@link EXECUTION_SCOPED_PREFIX} or sits under it. */
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
