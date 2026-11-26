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
import { execFile, type ChildProcess } from "node:child_process";
import { promisify } from "node:util";
import { parseProcNetListeners, type Listener } from "./socket-table";

const run = promisify(execFile);

// util.promisify's own type for execFile omits the `.child` property Node attaches to the
// returned promise (documented at nodejs.org/api/child_process.html#child_processexecfilefile-args-options-callback);
// this is the minimal cast to reach it without `any`.
function childOf(promise: ReturnType<typeof run>): ChildProcess {
  return (promise as unknown as { child: ChildProcess }).child;
}

// Reads the signed URL off stdin rather than argv (see `putFromWorkload`), then PUTs `$1` (the
// body) to it and prints only the HTTP status code.
const CURL_PUT_SCRIPT = 'url=$(cat); curl -s -o /dev/null -w "%{http_code}" -X PUT --data-binary "$1" "$url"';

// The signed URL comes off stdin, never argv. Headers go by an explicit pair count, an empty value as
// curl's `name;` (it drops `name: `), and the body by an explicit flag: a header or Content-Length: 0
// dropped on the way lets a refusal test pass vacuously.
const CURL_SEND_SCRIPT = `
url=$(cat)
method="$1"; shift
hasBody="$1"; shift
body="$1"; shift
count="$1"; shift
i=0
while [ "$i" -lt "$count" ]; do
  name="$1"; value="$2"; shift 2
  if [ -n "$value" ]; then set -- "$@" -H "$name: $value"; else set -- "$@" -H "$name;"; fi
  i=$((i + 1))
done
out="/tmp/ck-send-body-$$"
if [ "$hasBody" = "1" ]; then
  code=$(curl -s -o "$out" -w "%{http_code}" -X "$method" --data-binary "$body" "$@" "$url")
else
  code=$(curl -s -o "$out" -w "%{http_code}" -X "$method" "$@" "$url")
fi
echo "$code"
cat "$out"
rm -f "$out"
`;

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
 * Name of the one running container for a compose `service`, found by its
 * `com.docker.compose.service` label rather than a guessed container name -- compose prefixes
 * and suffixes that name in ways that vary by compose version and project name. Running only
 * (`docker ps`, no `-a`): a stopped one-shot service (`object-storage-setup`) must not be returned
 * as if it were still there to exec into.
 */
export async function composeServiceContainer(service: string): Promise<string> {
  const { stdout } = await run("docker", ["ps", "--filter", `label=com.docker.compose.service=${service}`, "--format", "{{.Names}}"]);
  const container = splitLines(stdout)[0];
  if (!container) {
    throw new Error(`no running container for compose service "${service}"`);
  }
  return container;
}

/**
 * Name of the compose stack's one `worker` service container, or null if it isn't running --
 * unlike {@link composeServiceContainer}, callers of this one treat "not there" as a normal case.
 */
async function findWorkerContainer(): Promise<string | null> {
  try {
    return await composeServiceContainer("worker");
  } catch {
    return null;
  }
}

/**
 * Every listening TCP socket and every bound UDP socket inside `container`'s network namespace,
 * read from the kernel's own socket table rather than a client-side port scan -- a scan cannot see
 * a listener bound to loopback, which is exactly what this is used to prove absent.
 */
export async function listenersOf(container: string): Promise<Listener[]> {
  const protos: Listener["proto"][] = ["tcp", "tcp6", "udp", "udp6"];
  const texts = await Promise.all(
    protos.map((proto) => run("docker", ["exec", container, "cat", `/proc/net/${proto}`]).then((r) => r.stdout)),
  );
  const files = Object.fromEntries(protos.map((proto, i) => [proto, texts[i]])) as Record<Listener["proto"], string>;
  return parseProcNetListeners(files);
}

/**
 * `container`'s `HostConfig.NetworkMode`. Compose's `network_mode: "service:<name>"` resolves to
 * `container:<id>` once the container exists, so a caller comparing this to another container's
 * namespace needs that container's id too -- see {@link containerIdOf}.
 */
export async function networkModeOf(container: string): Promise<string> {
  const { stdout } = await run("docker", ["inspect", "--format", "{{.HostConfig.NetworkMode}}", container]);
  return stdout.trim();
}

/** `container`'s full 64-character id, to compare against a `container:<id>` network mode. */
export async function containerIdOf(container: string): Promise<string> {
  const { stdout } = await run("docker", ["inspect", "--format", "{{.Id}}", container]);
  return stdout.trim();
}

/**
 * `container`'s own ports that carry a host binding, as `"<port>/<proto>"` -- what the compose
 * file's `ports:` list actually published, not what the image exposes.
 */
export async function publishedPortsOf(container: string): Promise<string[]> {
  const { stdout } = await run("docker", ["inspect", "--format", "{{json .NetworkSettings.Ports}}", container]);
  const ports = parseJsonQuietly(stdout, "NetworkSettings.Ports") as Record<string, unknown[] | null>;
  return Object.keys(ports).filter((port) => Array.isArray(ports[port]) && ports[port]!.length > 0);
}

export type ConnectResult = "connected" | "refused" | "timeout" | "other";

/**
 * Attempts a plain HTTP connection from inside `container` to `host:port`, the way an agent
 * container would reach a store port on the shared network -- never from this host, whose own
 * network path may differ. Maps curl's own exit code (never an HTTP status: without `-f`, curl
 * exits 0 on any HTTP response at all, including an error status) so a refusal before any request
 * is sent is distinguishable from one the store itself returned.
 */
export async function connectFromWorkload(container: string, host: string, port: number): Promise<ConnectResult> {
  try {
    await run("docker", [
      "exec",
      container,
      "curl",
      "-s",
      "-o",
      "/dev/null",
      "--connect-timeout",
      "5",
      `http://${host}:${port}/`,
    ]);
    return "connected";
  } catch (err) {
    const code = (err as { code?: number }).code;
    if (code === 7) return "refused";
    if (code === 28) return "timeout";
    // 52/56 are a connection that was made but got an empty or malformed reply (e.g. a bare gRPC
    // or binary-protocol port) -- the TCP connect itself still succeeded.
    if (code === 52 || code === 56) return "connected";
    return "other";
  }
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
  /** The agent container's own name, for a `docker exec` run inside it (e.g. {@link putFromWorkload}). */
  container: string;
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

    return { jobSecret, outputPath, container };
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

/**
 * PUTs `body` to `signedUrl` from inside `container` (the agent, not this host, so the request
 * goes through whatever network the workload actually has) and returns only the HTTP status
 * code. `signedUrl` reaches the container over the exec's stdin, never this host's argv: a failed
 * `execFile` rejects with the full command line, which a test report would then print.
 */
export async function putFromWorkload(container: string, signedUrl: string, body: string): Promise<number> {
  const promise = run("docker", ["exec", "-i", container, "sh", "-c", CURL_PUT_SCRIPT, "sh", body]);
  childOf(promise).stdin?.end(signedUrl);
  const { stdout } = await promise;
  return Number(stdout.trim());
}

export interface SendFromWorkloadOptions {
  method: "GET" | "PUT";
  body?: string;
  headers?: Record<string, string>;
}

/**
 * Sends `method` to `url` from inside `container`, with `headers` added, the way an agent adds a
 * header to a presigned request. `url` reaches the container over the exec's stdin, never this
 * host's argv (see `putFromWorkload`). On failure this raises a one-line error only, so the exec's
 * argv and stderr stay out of a report.
 */
export async function sendFromWorkload(
  container: string,
  url: string,
  opts: SendFromWorkloadOptions,
): Promise<{ status: number; body: string }> {
  const headerPairs = Object.entries(opts.headers ?? {}).flat();
  try {
    const promise = run("docker", [
      "exec",
      "-i",
      container,
      "sh",
      "-c",
      CURL_SEND_SCRIPT,
      "sh",
      opts.method,
      opts.body !== undefined ? "1" : "0",
      opts.body ?? "",
      String(headerPairs.length / 2),
      ...headerPairs,
    ]);
    childOf(promise).stdin?.end(url);
    const { stdout } = await promise;
    const newlineIndex = stdout.indexOf("\n");
    if (newlineIndex === -1) {
      return { status: Number(stdout.trim()), body: "" };
    }
    return {
      status: Number(stdout.slice(0, newlineIndex).trim()),
      body: stdout.slice(newlineIndex + 1),
    };
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err);
    throw new Error(`sendFromWorkload(${opts.method}): ${message.split("\n")[0]}`);
  }
}
