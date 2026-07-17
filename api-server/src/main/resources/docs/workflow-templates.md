<!-- DO NOT add a # H1 heading here — the title comes from index.json and is rendered by the web UI -->

## What is a Workflow Template?

A **Workflow Template** is a reusable directed acyclic graph (DAG) that defines the sequence and
structure of an automated workflow. Each node in the graph represents a unit of work; edges
connect nodes and optionally carry routing conditions.

Templates are **immutable seed data** — they are bundled with the application and cannot be
modified through the UI. To add or change a template, update the seed data in the codebase and
redeploy.

## Node Types

### AI Agent Node

An **AI Agent Node** spawns a Claude Code agent pod. The pod:

- Reads its task from `/workspace/config.json` (the node's `prompt` and `system_prompt` fields)
- Has access to the run log at `/workspace/in/run_log.md` (all prior node outputs)
- Writes output artifacts to `/workspace/out/`
- Reports a routing decision via `report-result <decision>` (e.g., `test`, `approved`, `rejected`)

Use AI Agent Nodes for tasks that require reasoning, code generation, or multi-step automation.

### Script Node

A **Script Node** runs a shell script or command directly in a container. Script nodes are useful
for deterministic steps such as building artifacts, running a fixed test suite, or invoking a CLI
tool. They support the same artifact and decision mechanism as AI Agent Nodes.

### Human Gate Node

A **Human Gate Node** pauses the workflow and notifies reviewers via the **Approvals** page.
The workflow resumes only after a reviewer submits an `approved` or `rejected` decision
(or a custom decision value defined on the node's outgoing edges).

### Parallel Split / Join

Some templates include **parallel branches** — a split node fans out to multiple child nodes that
run concurrently, and a join node waits for all branches to complete before the workflow continues.
This is used for multi-repo fan-out scenarios, where each repository runs in its own branch.

## Edge Conditions & Routing

Edges carry an optional **condition** value (e.g., `approved`, `rejected`).

- When a node reports a decision, the orchestrator follows all outgoing edges whose condition
  matches the reported decision.
- If an edge has **no condition** (a default edge), it is followed whenever no conditional edge matches.
- A node may have multiple outgoing edges with different conditions, producing a conditional branch.

**Example routing:**

```
[Spec Draft]  --"approved"--> [Implement]
[Spec Draft]  --"rejected"--> [Revise Spec]
[Code Review] --"approved"--> [Run Tests]
[Code Review] --"revised"---> [Code Review]  (self-loop; reviewer iterates)
```

## Template Immutability

Templates are defined in the database as immutable seed data seeded at startup. They have:

- A **name** and optional **description**
- A set of **node definitions** (type, prompt, system prompt, timeout)
- A set of **edge definitions** (source node, target node, optional condition)

Templates cannot be created, edited, or deleted through the UI. The `git_repo_id` (which repository
to run against) is **not** part of the template — it is selected at run-start time.

## Selecting a Template When Starting a Run

When you click **Start Run**, the **Start Run dialog** prompts you to:

1. **Choose a Workflow Template** — select from the list of available templates.
2. **Select a Git Repository or Repository Group** — choose which codebase(s) the workflow operates on.

Starting a run from a Task's detail view on the **Roadmap** page links the run to that Task
automatically instead — see [Roadmap](roadmap-and-proposals).

The dialog validates that all required fields are filled before enabling the **Start** button.

## Best Practices for Template Design

- **Keep nodes focused**: each node should have a single, well-scoped responsibility.
- **Use human gates before irreversible actions**: a gate before merging or deploying gives
  reviewers a chance to catch errors.
- **Prefer smaller, composable templates** over monolithic ones with many nodes — they are easier
  to debug and re-run from a specific point.
- **Name decision values clearly**: edge conditions like `approved` and `rejected` are self-documenting;
  avoid generic values like `done` or `ok`.
- **Document the template's intent** in the description field so operators know when to use it.
