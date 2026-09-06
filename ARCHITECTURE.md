# ChorusKube Architecture

A contributor-facing overview of how ChorusKube turns a workflow definition into a
running set of AI-agent tasks. For a project overview see [README.md](README.md);
to run the stack see [QUICKSTART.md](QUICKSTART.md).

## The graph / template model

A workflow is a **directed graph** — a "graph template" — whose nodes are units of
work:

- **AI-agent tasks** — run [Claude Code](https://claude.com/claude-code) inside an
  agent container to do real software work.
- **Scripts** — run a command in a container.
- **Human-approval gates** — pause the run until a reviewer approves, rejects, or
  routes it.

Edges connect the nodes into a DAG. A template is the reusable shape of a workflow;
the **target git repo (`git_repo_id`) is a run input**, supplied when you start a
run, not baked into the template. The same template can therefore run against many
repos.

## The run lifecycle

When a run starts, a **Temporal-backed orchestrator** walks the DAG: it decides
which node runs next, follows edges, and applies conditional routing based on the
outcome of each node (including human decisions at approval gates). Temporal gives
the walk durability and retries.

The orchestrator does **not** create containers itself. It dispatches each ready node
as a **Temporal activity**, and a **Worker** — a process subscribed to the node's task
queue — picks it up and creates the workload itself:

- an **isolated Docker container** when running locally, or
- a **Kubernetes Job** when running in a cluster.

The Worker owns the executor. Before launching, it asks the **api-server** to resolve
per-run configuration and credentials (the workload `prepare` call); when the agent
finishes, the Worker's own callback server receives the result and records execution
state back to the api-server (the workload `complete` call). The api-server is the
source of truth for state, but it is **out of the execution hot path** — it never
creates a container.

The executor is deliberately **tenant-agnostic**: it launches into a namespace, service
account, and credentials it is *given*, and resolves none of them itself. The
single-tenant core has one tenant, so the boundary is invisible here — but it is the
same boundary that lets a Worker run on infrastructure the operator controls without
reaching anything but its own work. Placement and credential scoping across more than
one Fleet are extension seams core declares but does not implement — see
[docs/decisions/2026-09-06---02-worker-placement-and-executor-seams.md](docs/decisions/2026-09-06---02-worker-placement-and-executor-seams.md).

```
   ┌──────────┐  REST / WS   ┌──────────────┐  Temporal signals  ┌──────────────────┐
   │  web-ui  │ <──────────> │  api-server  │ <────────────────> │   orchestrator   │
   └──────────┘              │ state + creds│                    │ (Temporal driver)│
                             └──────┬───────┘                    └────────┬─────────┘
                                    │                                     │ dispatches each
                     PostgreSQL ────┤                                     │ node as an activity
                     object store ──┘                                     ▼
                                    ▲                             ┌──────────────────┐
                     prepare (creds)│                             │      worker      │
                     complete (state)──── worker calls ───────────┤ owns executor +  │
                                                                  │ callback server  │
                                                                  └────────┬─────────┘
                                                                           │ creates per node;
                                                                           │ agent returns its
                                                                           ▼ result via callback
                                                                  ┌──────────────────┐
                                                                  │  agent container │
                                                                  │    or K8s Job    │
                                                                  └──────────────────┘
```

The orchestrator drives the graph; the **Worker** creates and owns the agent workloads;
the **api-server** owns state and credentials and talks to the data stores.

## AI nodes and artifacts

An AI node runs Claude Code inside the agent container against the target repo.
Nodes are isolated from each other, so they exchange data through **artifacts**:
the api-server hands outputs to and from an **object store** using presigned URLs,
so containers read and write artifacts directly without proxying large blobs
through the api-server.

Inside an agent container the workspace is laid out as:

```
/workspace/
├── config.json   # run context for this node (read-only)
├── in/           # input artifacts from predecessor nodes
├── out/          # outputs written here are uploaded to the object store
└── repo/         # clone of the target git repo (if configured)
```

A node reads its task from `config.json`, does its work against `repo/`, and writes
results to `out/`; downstream nodes receive them in their `in/`.

## State and realtime updates

The **api-server is the single source of truth** for run and execution state,
persisted in **PostgreSQL**. When state changes — a node starts, finishes, or a run
reaches an approval gate — the api-server broadcasts the change over
**STOMP/WebSocket**. The web UI **subscribes** to these events and updates live; it
does not poll. This keeps the graph view and run monitor in sync with the
authoritative state without the UI hammering the API.

## Single-tenant model

The open-source core runs **single-tenant** with no external identity provider.
There is no login and no multi-org routing: every request resolves to a single
seeded **"system" organization**, which also holds the seeded credentials (Claude
token, GitHub credential) used for real runs. This keeps the core simple to run
locally and to self-host.

## Components

| Component | Stack | Responsibility |
|-----------|-------|----------------|
| **api-server** | Java / Spring Boot | Source of truth for state; resolves per-run credentials and records Worker-reported execution state; REST API; STOMP/WebSocket broadcasts. |
| **orchestrator** | Go + Temporal | Drives the graph; dispatches each node as a Temporal activity for a Worker to execute. |
| **worker** | Go + Temporal | Runs executors (Docker, Kubernetes) and the agent callback server; receives work from Fleets. |
| **web-ui** | React + Vite | Graph visualization, live run monitoring, human-approval gates; subscribes to WebSocket events. |
| **agent images** | container images | The containers a node runs in — the AI agent (Claude Code) and a fuller dev image built on top of it. |

See the [Components section of README.md](README.md#components) for the
fuller per-component description.
