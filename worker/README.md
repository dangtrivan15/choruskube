# Worker

**Autopilot** decides what to do. An **Agent** does it. A **Worker** runs the Agent. A **Fleet** is the set of Workers.

A Worker is a Go process that polls one Temporal task queue and runs the agent steps it
claims there. It is the only part of ChorusKube that has to sit next to the compute — it owns
a workload executor itself (Docker or Kubernetes) and launches agent containers directly, so
wherever the Worker runs is where the work runs. The api-server is off this hot path: it only
resolves per-run credentials before launch and records the result the Worker reports back.

## One Fleet

A Fleet is an addressable place work runs: a Temporal namespace plus a task queue. This
deployment has **exactly one**. Every Worker is told the same namespace and queue, which is
why nothing here needs a Fleet registry, per-Fleet credentials, or a way to move a Worker
between Fleets — there is nowhere to move it to.

That single Fleet is not a stripped-down mode; it is the whole concept at its smallest size.
Placing runs across several Fleets — separate queues per team, per customer, or per cluster,
with credentials issued and revoked per Fleet — is what the commercial control plane adds. It
does so by supplying its own implementation of two seams the api-server already declares
(`WorkerRegistrar` and `RunPlacementResolver`), not by replacing anything in this module. The
Worker binary is the same either way.

## Configuring which Fleet a Worker serves

Two paths, and a Worker picks whichever is configured. Registration wins if both are: a token
names a Fleet the server chooses, and locally set coordinates could name a different one.

### Register with the api-server

Set `FLEET_TOKEN`. The Worker POSTs to `/worker/register` at startup and is told its
namespace, queue, and Temporal credential; it re-registers periodically, which is also how a
credential is renewed and how a Fleet added after the process started gets picked up.

Registration also settles the credential the Worker presents on the api-server's own
`/worker/**` routes, so nothing separate has to be configured for it. A server that mints one
returns it; this one does not, and the Worker keeps the `FLEET_TOKEN` it authenticated with.

The api-server compares the token against `WORKER_REGISTRATION_TOKEN`, so the two must match.
Leaving the server's side unset admits no Worker at all — registration fails closed rather
than leaving the endpoint anonymous on a server whose operator never set it.

Every registration call also reports the Worker's resolved `CREDENTIAL_SOURCE`, so the
api-server can tell the two modes apart on a `/worker/**` call that cares. The field is
required: a missing, blank, or unrecognized value is refused with no default, so a Worker
binary built before this field existed cannot register until it is upgraded. This server has
exactly one Fleet, whose mode is a fact about the deployment rather than something to learn
from whichever Worker registers, so it keeps no record of what any one Worker declared.

Use this path when you want one place to change the answer, or when you intend to grow into a
deployment that has more than one Fleet.

### Configure the Fleet directly

Set `TEMPORAL_NAMESPACE`, `TEMPORAL_TASK_QUEUE` and `WORKER_INTERNAL_TOKEN`. The Worker serves
that one Fleet and never calls `/worker/register`, so it holds no Temporal credential. It
connects to Temporal without one, which suits a Temporal that runs no authorizer — the local
stack, or a self-hosted cluster where the frontend is not exposed.

`WORKER_INTERNAL_TOKEN` is the exception, and it is required: this path registers with nobody,
so nothing can hand the Worker a credential for the api-server's `/worker/**` routes. Without
it the process refuses to start, rather than presenting an empty bearer and failing one node
at a time.

Set it to the same value as the api-server's `WORKER_REGISTRATION_TOKEN`. Skipping registration
does not skip authorization: this server checks every `/worker/**` call against that one
configured secret, whether the presenter registered or not. A `WORKER_INTERNAL_TOKEN` that
differs satisfies the startup guard — which only asks that it is set — and then fails every node
with a 403, which is the failure the guard exists to prevent. Leaving the server's side unset
admits no Worker on either path.

This path never calls `/worker/register`, so `CREDENTIAL_SOURCE` is never reported anywhere;
`local` here reads `CREDENTIALS_DIR` exactly as it does on the registration path. Registration
takes priority over these static settings whenever both are configured.

Use this path when the answer is already known and constant, and a round-trip to ask for it
would only return what you configured.

## Environment

| Variable | Required | Purpose |
|----------|----------|---------|
| `TEMPORAL_ADDRESS` | yes | Temporal frontend to dial. A registered Fleet may override it. |
| `API_SERVER_URL` | yes | api-server base URL, for registration and the workload executor. |
| `CALLBACK_URL` | yes | Where a launched agent container reports its result. |
| `FLEET_TOKEN` | one of | Selects registration. Must match the server's `WORKER_REGISTRATION_TOKEN`. |
| `TEMPORAL_NAMESPACE`, `TEMPORAL_TASK_QUEUE` | one of | Select the static single Fleet. |
| `WORKER_INTERNAL_TOKEN` | static path | Authenticates this Worker on the api-server's `/worker/**` routes. Must equal the server's `WORKER_REGISTRATION_TOKEN`. Ignored when registering. |
| `TEMPORAL_TLS_DISABLED` | no | Set `true` for a Temporal that serves plaintext gRPC. |
| `WORKER_CAPABILITIES` | no | `key=value,key=value` pairs this Worker reports about its own infrastructure at registration. Default empty. A server that records required capabilities on a Fleet refuses a Worker that does not report them. |
| `EXECUTOR_TYPE` | no | `docker` (default) or `k8s` — which executor this process builds at startup. Any other value fails startup. |
| `WORKER_CALLBACK_PORT` | no | Port the callback server listens on for agent completion/heartbeat callbacks. Default `9090`. |
| `CREDENTIAL_SOURCE` | yes | `control-plane` or `local` — where GitHub and agent credentials come from; see [Credentials](#credentials). No default: a missing or unrecognized value fails startup, since guessing wrong would send a Worker meant to hold its own credentials to the API server for them, or the reverse. `local` is for a single tenant's own Fleet only. Reported at every registration call so the api-server can tell the two modes apart. |
| `CREDENTIALS_DIR` | no | Folder the `local` source reads. Default `/etc/choruskube/credentials`. |
| `GITHUB_API_URL` | no | GitHub API root for the Worker's own GitHub calls (gate compares and merges, run-branch cleanup, roadmap issue filing and closing). Default `https://api.github.com`. |

`TEMPORAL_TLS_DISABLED` is opt-in so a deployment against a TLS Temporal cannot lose TLS by
omission. It is only needed when a Fleet carries a credential: the Temporal SDK turns TLS on
whenever credentials are present, whatever they contain. A Worker with no credential — the
static path, or a registration that returned an empty token — presents none at all and dials
plaintext without it.

Every workload call a Worker makes to the api-server names its run in the path — registration is
the exception, and it precedes any run — so the server is in a position to decide per run whether
the presenting credential may act on it. That decision is the server's; the path shape only gives
it something to decide on. What a credential actually reaches depends on which one it is — and on
this deployment every Worker presents the same `WORKER_REGISTRATION_TOKEN`, whether registration
handed it back or the operator configured it directly, so it is one shared value, not a per-Worker
one. A server that mints a credential per registration returns it in the registration response and
the Worker presents that instead.

## Credentials

An agent pod fetches its GitHub token from the Worker that launched it
(`GET /api/v1/github-token` on the callback port, authenticated with the pod's own job secret and
refused unless the execution belongs to the run it names), so the Worker decides where the token
comes from.

- **`control-plane`** asks the api-server for each run's token. The api-server resolves
  it per organization, which is what a Worker serving several tenants needs.
- **`local`** reads one set of credentials from `CREDENTIALS_DIR` and mints GitHub App tokens
  itself. It has no notion of a tenant, so every run on the Worker gets the same credentials:
  use it only for a Worker that serves a single tenant's own Fleet.

Why the mode is declared rather than detected, and why `local` must never serve more than one tenant: [2026-10-13---02](../docs/decisions/2026-10-13---02-data-plane-credential-rule.md).

A `local` GitHub App token covers only the run's own GitHub repositories and the fixed run
permission list (`contents`, `pull_requests`, `issues` and `workflows` write; `actions`, `checks`,
`metadata` and `statuses` read) — never the whole installation. The Worker fetches the scope for
each token request from the api-server's scope route, carrying no credential itself, and never
hardcodes the permission list. The App's installation must be on the account that owns the run's
repositories, with each of them in its repository selection — one outside the selection fails the
whole mint for its run. See [2026-10-18---01](../docs/decisions/2026-10-18---01-minted-app-tokens-narrowed-to-the-run.md).

`local` reads these files on every use, so a rotated secret needs no restart. Anything the
folder does not hold falls back to the environment, and the folder wins: a GitHub credential of
either kind in the folder beats any in the environment, so a stray variable cannot override a
mounted secret.

The Worker also uses the run's own token itself, not only to hand to the agent pod: to merge a
gate's pull requests, to delete a completed run's branch once it is confirmed not ahead of its
repo's default branch, and to file the GitHub issues a gate's approved roadmap extension asks for
(an issue per new task, deduplicated by a hidden key so a retried step never files one twice). The
merge and branch-deletion calls need Contents read/write on the repository; filing issues needs
Issues read/write — a `local` credential must carry it too, since the Worker files with whatever
credential it was given regardless of mode. Every GitHub request the Worker makes identifies itself
with `User-Agent: choruskube-worker`.

| File under `CREDENTIALS_DIR` | Environment fallback | Purpose |
|------------------------------|----------------------|---------|
| `github-pat` | `GITHUB_PAT` | A personal access token, used as is. Wins over a GitHub App. Cannot be narrowed; prefer a fine-grained PAT limited to the repositories runs use. |
| `github-app/app-id`, `github-app/installation-id`, `github-app/private-key.pem` | `GITHUB_APP_ID`, `GITHUB_APP_INSTALLATION_ID`, `GITHUB_APP_PRIVATE_KEY_PATH` (a path to the PEM) | A GitHub App; the Worker mints installation tokens and reuses one until 5 minutes before it expires. All three are required. |
| `claude-oauth-token` | `CLAUDE_CODE_OAUTH_TOKEN` | Replaces the Claude token the api-server returns for the agent pod. |
| `registry/host`, `registry/username`, `registry/password` | `REGISTRY_HOST`, `REGISTRY_USERNAME`, `REGISTRY_PASSWORD` | Replaces the registry login the api-server returns. All three are required. |

If the folder or environment supplies either the Claude token or a registry login, the Worker
uses its values for both and drops whatever the api-server returned for the other. Supplying
neither leaves the api-server's credentials in place.

### GitHub work outside a run

`CloseTaskIssue` closes a done Task's linked GitHub issue. It runs on the Fleet's own task queue,
but for a workflow whose id names a Task (`choruskube-task-issue-close-<taskId>`) rather than a
run (`choruskube-run-<runId>`). It still fetches its GitHub token keyed by the Task's latest run
id, so every workload call still names its run in the path. Closing needs Issues write, as filing
does.

No deadline bounds how long the step waits for a live Worker to pick it up; retries are bounded
only once one runs it. GitHub's non-retryable answers — 404, 403, 410 or 422, for example — end the
step without retry, and the issue stays open.

## Executor configuration

`EXECUTOR_TYPE` selects the executor `cmd/worker` builds once at startup; the remaining
variables in each row below configure only that one. An executor never resolves a namespace,
service account, or credential itself — see
[the executor decision](../docs/decisions/2026-09-05---01-worker-owns-tenant-agnostic-executor.md)
— so every one of these is supplied by whoever runs the Worker, not inferred.

**Kubernetes (`EXECUTOR_TYPE=k8s`)** — always runs in-cluster; there is no kubeconfig fallback.

| Variable | Default | Purpose |
|----------|---------|---------|
| `K8S_NAMESPACE` | `choruskube` | The single namespace every agent Job launches into and is torn down within. |
| `K8S_AGENT_SERVICE_ACCOUNT` | `choruskube-agent` | Service account the agent Job's pod runs as. |
| `K8S_AGENT_POD_TEMPLATE_NAME` | `choruskube-agent-pod-template` | ConfigMap holding the operator's agent pod template — see [Kubernetes agent pod template](#kubernetes-agent-pod-template) for which fields apply. |
| `K8S_TEMPLATE_NAMESPACE` | `choruskube` | Namespace the pod template above is read from. |
| `K8S_AGENT_CPU_REQUEST` | `200m` | Default agent-container CPU request; a node execution's own sizing overrides it. |
| `K8S_AGENT_MEMORY_REQUEST` | `2Gi` | Default agent-container memory request; overridable per node. The memory limit is always 1.4× the request, and a capped project may not request more than this default. |
| `K8S_AGENT_CPU_LIMIT` | `1` | Default agent-container CPU limit; overridable per node. |
| `K8S_AGENT_MEMORY_LIMIT` | — | Removed: the limit is derived from the request. The Worker refuses to start while it is set. |

**Docker (`EXECUTOR_TYPE=docker`, the default)** — the local-stack and self-hosted-on-a-single-host path.

| Variable | Default | Purpose |
|----------|---------|---------|
| `DOCKER_HOST` | Docker SDK's own environment-based default | Daemon socket, e.g. `unix:///var/run/docker.sock`. |
| `DOCKER_NETWORK` | `choruskube` | Docker network agent containers and DinD sidecars attach to. |
| `DOCKER_STAGING_DIR` | `/tmp/choruskube-agent-staging` | Base directory for per-execution config/credential staging. In Docker-out-of-Docker deployments this path must be bind-mounted identically on both sides — the Worker's own filesystem and the host daemon it talks to. |

### Kubernetes agent pod template

The Kubernetes executor builds each agent pod itself and takes only named fields from the
operator's `PodTemplate` (stored under the wrapper ConfigMap's `template.yaml` key); everything
else in the template is ignored.

| Template fields | Applied to |
|-----------------|------------|
| `nodeSelector`, `affinity`, `tolerations`, `topologySpreadConstraints`, `priorityClassName` | Every agent pod. |
| `runtimeClassName`, `hostUsers`, the `dind` init container, the `agent` container's `env` and `volumeMounts`, the pod's `volumes` | Only pods for nodes that request Docker. |

Scheduling fields reach every pod because they decide which nodes may run an agent at all; the
Docker parts stay on Docker nodes because no other agent needs the sidecar or its runtime. A field
the template sets replaces the executor's own value outright rather than merging with it, so the
pod gets exactly what the template says. The one exception is the `dind` container's memory
limit: the executor sets it to 1.4× the container's memory request, so the template declares only the
request, and a template that declares the limit is rejected as malformed. The Worker refuses to start
when the template is missing or malformed, and reads it once per process — restart the Worker after
editing it.

## Build and test

```sh
go build ./...
go test -race ./...
```

See [CONTRIBUTING.md](../CONTRIBUTING.md) for the full test strategy and
[ARCHITECTURE.md](../ARCHITECTURE.md) for how the components fit together.
