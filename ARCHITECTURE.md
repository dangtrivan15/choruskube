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

## Task-triggered runs

A Feature Development run can be started from a roadmap **Task** — the leaf of the
**Epic → Story → Task** hierarchy — rather than manually. Starting a Task composes the
run's `feature_request` input deterministically: the task-start path (`DefaultTaskService`)
walks the Task's parent Story and grandparent Epic and folds their titles, descriptions,
and the Epic's motivation into `feature_request`, with the Task as the lead section and a
labelled "Parent context" section beneath it. A blank or missing parent field is omitted
rather than rendered as an empty header, so a Task with no ancestry content degrades to a
Task-only prompt.

This composition happens once, at start — it is a point-in-time snapshot, not a live view.
To reach anything beyond it (the live ticket, open dependencies, sibling Tasks), the agent
system prompt for a task-triggered run points every AI node at the roadmap CLI
(`get-roadmap-graph`, which resolves this run's Epic server-side with no flags needed) as
the source of truth for current state.

## Roadmap proposals and gates

Some workflow templates include a **human-approval gate configured to review roadmap
changes** — Feature Development's Final Approval, and the Roadmap Provisioner's own
gate. A gate like this declares a `materialize` mode in its config:

- `roadmap_candidates` — the Roadmap Provisioner's mode: the proposal document
  describes wholly new Epic → Story → Task trees.
- `roadmap_extension` — Feature Development's mode: the proposal may extend the
  run's own Epic (when the run was started from a Task) with new Stories and Tasks,
  or introduce a genuinely separate new Epic, but may not attach new items under any
  other existing Epic.

An agent node writes the proposal as an artifact, `roadmap_candidates.json`: a nested
Epic → Story → Task document, plus key-based dependencies between entries. Any Epic,
Story or Task entry may instead carry an `existingId`, turning it into an **anchor** —
an already-materialized item that nothing is created for, but that can hold new
children or serve as a dependency endpoint. The agent validates its own proposal
before installing it (`propose-roadmap`), against a server-side validator that checks
structure (unique keys, consistent anchor nesting, no dangling dependency keys), the
addressable invariant (every new Epic needs at least one Story, every new Story at
least one Task), and the active gate's scope rules.

Because a gate like this makes roadmap creation the reviewer's decision, the direct
roadmap-write routes (`create-proposal`, `update-proposal`, `create-story`,
`create-task`, `create-dependency`, `create-milestone`) are refused for any run whose
workflow declares a roadmap gate, with a message pointing at `propose-roadmap`
instead; the read routes stay available. Every agent-facing roadmap route also verifies the calling
node execution belongs to the run named in its path, so a caller cannot escape a
run's rule by naming a different run's id.

When the gate awaits a decision, the reviewer sees the proposal as an editable tree:
anchors render read-only with their live titles, filled in only when the anchor
resolves inside the run's own software project (project membership is the tenancy
boundary; a foreign or missing anchor cannot be told apart from the reviewer's view).
The reviewer can add new Stories/Tasks under an anchor, edit or remove new items, or
drop an anchor (which never deletes the item it points at). Approving re-validates
the edited document against the gate's rules before creating anything: a structural
violation (for example, a Story a reviewer added with no Task) is rejected with the
reasons, and the gate stays open for another edit. Nothing is created on a rejected
approval.

A Task created this way in `roadmap_extension` mode also gets a matching GitHub issue,
filed by the server (not the agent) right after the Task is created, and closed
automatically once the Task reaches `done`. Issue filing and closing are both
best-effort: a failure is recorded alongside the gate's result rather than blocking
Task creation or completion.

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
