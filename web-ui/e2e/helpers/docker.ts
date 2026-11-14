/**
 * docker.ts — Docker-CLI helpers for e2e specs that need to see what the Docker executor
 * actually launched: `specs/workload-lifetime.spec.ts` (container/volume/staging-dir reclaim)
 * and `specs/agent-run-scope.spec.ts` (an agent's real `JOB_SECRET` and `output_path`).
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

export interface AgentWorkload {
  /** The real `JOB_SECRET` the Docker executor launched this execution's container with. */
  jobSecret: string;
  /** The `output_path` the Worker wrote into the container's own `/workspace/config.json`. */
  outputPath: string;
}

/**
 * The `JOB_SECRET` and `output_path` execId's agent container was launched with. The DinD sidecar
 * carries the same `choruskube/exec-id` but neither value, so it is skipped by `choruskube/dind`.
 */
export async function agentWorkload(execId: string): Promise<AgentWorkload> {
  try {
    const { stdout } = await run("docker", [
      "ps",
      "-a",
      "--filter",
      `label=choruskube/exec-id=${execId}`,
      "--format",
      '{{.Names}}\t{{.Label "choruskube/dind"}}',
    ]);
    const container = splitLines(stdout)
      .map((line) => line.split("\t"))
      .find(([, dind]) => dind !== "true")?.[0];
    if (!container) {
      throw new Error(`no agent container found for exec id ${execId}`);
    }

    const { stdout: envOut } = await run("docker", ["inspect", "--format", "{{json .Config.Env}}", container]);
    const env = parseJsonQuietly(envOut, "container environment") as string[];
    const jobSecretEntry = env.find((entry) => entry.startsWith("JOB_SECRET="));
    if (!jobSecretEntry) {
      throw new Error(`container for exec id ${execId} has no JOB_SECRET in its environment`);
    }
    const jobSecret = jobSecretEntry.slice("JOB_SECRET=".length);

    const { stdout: configOut } = await run("docker", ["exec", container, "cat", "/workspace/config.json"]);
    const config = parseJsonQuietly(configOut, "config.json") as { output_path?: unknown };
    const outputPath = config.output_path;
    if (typeof outputPath !== "string" || outputPath.length === 0) {
      throw new Error(`container for exec id ${execId} has no output_path in its config.json`);
    }

    return { jobSecret, outputPath };
  } catch (err) {
    // A fresh error holding one line: the original carries docker's stdout and stderr, so a report
    // that prints it can carry the container environment, secret included.
    const message = err instanceof Error ? err.message : String(err);
    throw new Error(`agentWorkload(${execId}): ${message.split("\n")[0]}`);
  }
}

// JSON.parse's own message quotes the text around the fault, which here is the container
// environment holding JOB_SECRET.
function parseJsonQuietly(text: string, what: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    throw new Error(`${what} is not valid JSON`);
  }
}
