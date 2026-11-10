/**
 * docker.ts — Docker-CLI helpers for the workload-lifetime e2e spec
 * (`specs/workload-lifetime.spec.ts`).
 *
 * The REST API has no route for raw container/volume inspection -- the Worker's Docker executor
 * is the only thing that ever talks to the daemon directly (see
 * `worker/executor/docker/docker.go`) -- so these shell out to the `docker` CLI instead, reading
 * back the same `choruskube/*` labels that executor stamps on every object it creates.
 */
import { execFile } from "node:child_process";
import { promisify } from "node:util";

const run = promisify(execFile);

export interface WorkloadObjects {
  /** Names of containers (the agent container and/or its DinD sidecar) labelled for the run. */
  containers: string[];
  /** Names of volumes (`ck-dind-data-*` and/or `ck-workspace-*`) labelled for the run. */
  volumes: string[];
}

function splitLines(out: string): string[] {
  return out
    .split("\n")
    .map((line) => line.trim())
    .filter((line) => line.length > 0);
}

/**
 * Every container and volume carrying `choruskube/run-id=runId`, across every exec-id the run
 * ever launched -- stopped or running, so a workload the janitor hasn't swept yet still shows up.
 */
export async function workloadObjectsForRun(runId: string): Promise<WorkloadObjects> {
  const label = `label=choruskube/run-id=${runId}`;
  const [containersOut, volumesOut] = await Promise.all([
    run("docker", ["ps", "-a", "--filter", label, "--format", "{{.Names}}"]),
    run("docker", ["volume", "ls", "--filter", label, "--format", "{{.Name}}"]),
  ]);
  return {
    containers: splitLines(containersOut.stdout),
    volumes: splitLines(volumesOut.stdout),
  };
}

/**
 * Name of the compose stack's one `worker` service container, found by its
 * `com.docker.compose.service` label rather than a guessed container name -- compose prefixes
 * and suffixes that name in ways that vary by compose version and project name.
 */
async function findWorkerContainer(): Promise<string | null> {
  const { stdout } = await run("docker", [
    "ps",
    "-a",
    "--filter",
    "label=com.docker.compose.service=worker",
    "--format",
    "{{.Names}}",
  ]);
  return splitLines(stdout)[0] ?? null;
}

// Must match the DOCKER_STAGING_DIR the worker service is given in docker-compose.e2e.yaml
// (the `agent-staging-dir` anchor) -- both the core and cloud e2e stacks keep this identical so
// this spec runs unchanged against either.
const E2E_STAGING_BASE = "/tmp/choruskube-e2e-staging";

/**
 * Staging directories (`ck-config-<execIdShort>-*`) left inside the worker container's own
 * `DOCKER_STAGING_DIR` for any of execIds (full execution UUIDs -- only the executor's own
 * `execIDShort`, their first 8 characters, actually names a directory).
 *
 * Runs `find` inside the worker container via `docker exec`, rather than reading this host's
 * filesystem directly: DOCKER_STAGING_DIR is bind-mounted at an identical path on both sides
 * for a local Docker daemon, but nothing about this spec guarantees it is run on the same host
 * as the daemon.
 */
export async function stagingDirsFor(execIds: string[]): Promise<string[]> {
  const workerContainer = await findWorkerContainer();
  if (!workerContainer || execIds.length === 0) {
    return [];
  }

  const patterns = execIds.map((id) => `ck-config-${id.slice(0, 8)}-*`);
  const findExpr = patterns.flatMap((p) => ["-o", "-name", p]).slice(1); // drop the leading -o
  try {
    const { stdout } = await run("docker", [
      "exec",
      workerContainer,
      "find",
      E2E_STAGING_BASE,
      "-maxdepth",
      "1",
      "-mindepth",
      "1",
      "-type",
      "d",
      "(",
      ...findExpr,
      ")",
    ]);
    return splitLines(stdout);
  } catch {
    // The base directory not existing yet (nothing has launched there) is the only expected
    // failure here; the spec only ever asserts this list is empty, so either way the answer is
    // the same to it.
    return [];
  }
}
