# Worker

**Autopilot** decides what to do. An **Agent** does it. A **Worker** runs the Agent. A **Fleet** is the set of Workers.

A Worker is a Go process that polls one Temporal task queue and runs the agent steps it
claims there. It is the only part of ChorusKube that has to sit next to the compute — it
launches agent containers through the api-server's workload executor, so wherever the Worker
runs is where the work runs.

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

The api-server compares the token against `WORKER_REGISTRATION_TOKEN`, so the two must match.
Leaving the server's side unset admits no Worker at all — registration fails closed rather
than leaving the endpoint anonymous on a server whose operator never set it.

Use this path when you want one place to change the answer, or when you intend to grow into a
deployment that has more than one Fleet.

### Configure the Fleet directly

Set `TEMPORAL_NAMESPACE` and `TEMPORAL_TASK_QUEUE`. The Worker serves that one Fleet and never
calls `/worker/register`, so the api-server needs no registration token and the Worker holds
no credential. It connects to Temporal without one, which suits a Temporal that runs no
authorizer — the local stack, or a self-hosted cluster where the frontend is not exposed.

Use this path when the answer is already known and constant, and a round-trip to ask for it
would only return what you configured.

## Environment

| Variable | Required | Purpose |
|----------|----------|---------|
| `TEMPORAL_ADDRESS` | yes | Temporal frontend to dial. A registered Fleet may override it. |
| `API_SERVER_URL` | yes | api-server base URL, for registration and the workload executor. |
| `CALLBACK_URL` | yes | Where a launched agent container reports its result. |
| `ORCHESTRATOR_SECRET` | yes | Authenticates this Worker to the api-server's internal endpoints. |
| `FLEET_TOKEN` | one of | Selects registration. Must match the server's `WORKER_REGISTRATION_TOKEN`. |
| `TEMPORAL_NAMESPACE`, `TEMPORAL_TASK_QUEUE` | one of | Select the static single Fleet. |
| `TEMPORAL_TLS_DISABLED` | no | Set `true` for a Temporal that serves plaintext gRPC. |

`TEMPORAL_TLS_DISABLED` is opt-in so a deployment against a TLS Temporal cannot lose TLS by
omission. It is only needed when a Fleet carries a credential: the Temporal SDK turns TLS on
whenever credentials are present, whatever they contain. A Worker with no credential — the
static path, or a registration that returned an empty token — presents none at all and dials
plaintext without it.

## Build and test

```sh
go build ./...
go test -race ./...
```

See [CONTRIBUTING.md](../CONTRIBUTING.md) for the full test strategy and
[ARCHITECTURE.md](../ARCHITECTURE.md) for how the components fit together.
