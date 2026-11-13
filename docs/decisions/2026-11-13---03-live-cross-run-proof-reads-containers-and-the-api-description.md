# The live cross-run proof reads real secrets from containers and its route list from `/v3/api-docs`

**Status:** current

## Context

`RunScopeRouteAudit` proves the run-scope guard and the auth filters refuse a cross-run pair, but
only against mock requests built inside the same process. Nothing presented a real agent's
`JOB_SECRET` — minted by the Docker executor for a real, running execution — to the deployed stack
and watched every agent route refuse it outside that execution's own run. Two questions had to be
settled before `web-ui/e2e/specs/agent-run-scope.spec.ts` could do that: where the secret it
presents comes from, and which routes it probes.

## Decision

**The secret and its execution's `output_path` are read from the agent container the Docker
executor actually launched**, via `agentWorkload()` (`web-ui/e2e/helpers/docker.ts`): `docker ps`
filtered on `choruskube/exec-id`, excluding the DinD sidecar (`choruskube/dind=true`), then `docker
inspect` for `JOB_SECRET` and `docker exec ... cat /workspace/config.json` for `output_path`. This
is the secret a real agent pod actually received, with no new product code and no path that writes
authentication state. Rejected: overwriting an execution's `job_secret_hash` with the orchestrator
secret works under any executor, but it would exercise a fabricated secret, not the one actually
minted, and it mutates node state the spec doesn't own. An e2e-profile endpoint that issues a secret
was rejected too: it is executor-independent, but it adds a new code path whose only purpose is to
hand out authentication material, which is itself a thing worth not building.

**The probed route list comes from the running server's own `/v3/api-docs`**, kept to the
operations whose path is `/internal/runs/{runId}/node-executions/{nodeExecId}` or sits under it
(`executionScopedOperations`, `web-ui/e2e/helpers/agent-routes.ts`). A route added later is probed
without editing this spec, the same way `RunScopeRouteAudit` follows the live route table rather
than a kept list. Because the spec's correctness then depends on `/v3/api-docs` actually listing
every agent route, `missingFloorRoutes` fails the spec if the enumeration misses any of eight
well-known routes (status, presign, decision, valid-decisions, logs, pull-requests, repo-bases,
gate-finalize completion) — a config change that stopped springdoc from listing them, or moved them
behind a different base path, fails loudly instead of silently probing fewer routes than the spec
claims to. A list kept inside the spec was rejected: it would drift silently as routes are added,
the way the per-route checks did before the run-scope guard existed.

## Consequences

- The spec is tied to the Docker executor: `agentWorkload()` has nothing to read on a stack running
  a different executor. Both e2e stacks run the Docker executor today, and the in-process audit
  still covers every route regardless of executor.
- Every probe goes through a plain `fetch` (`web-ui/e2e/specs/agent-run-scope.spec.ts`'s `probe()`
  helper), never Playwright's `request` fixture, and `agentWorkload()`'s own errors never carry
  `docker`'s stdout/stderr — so a real job secret never lands in a trace, HAR, or report.
