# ChorusKube

**An AI-agent workflow orchestrator for Kubernetes and Docker.**

Giving an AI coding agent free rein over your codebase is a leap of faith.
ChorusKube lets you define work as a directed graph — AI tasks, scripts, and
human-approval gates — and runs each node as an isolated container, so agents
do the work while you keep control. AI nodes run [Claude Code](https://claude.com/claude-code)
against a target git repo; a Temporal-backed orchestrator drives the graph and
a Worker owns the container lifecycle.

This is the open-source core: single-tenant, no external identity provider —
the API is open and every request is scoped to a seeded "system" organization.

## Why ChorusKube?

- **Workflow as a DAG** — model spec → plan → implement → test → review → merge
  as a graph with mandatory gates. Conditional routing and revision loops included.
- **Container isolation** — every node runs in its own ephemeral container with a
  unique per-execution secret; agents get autonomy in a sandbox, not your cluster.
- **Human-in-the-loop** — pause at review gates, inspect predecessor outputs and
  artifacts, then approve, reject, or request changes that feed the next iteration.
- **Live monitoring** — watch the graph advance node-by-node over WebSocket; the
  UI subscribes to events rather than polling.

## Node types

| Type | What it does |
|------|--------------|
| `ai` | An AI agent (Claude Code) runs autonomously in a container |
| `script` | A shell command runs in a container |
| `human` | The run pauses for approval via the dashboard |
| `both` | AI produces output, then a human reviews before continuing |

## Components

- **api-server** — Spring Boot (Java 25). Source of truth for run state; resolves
  per-run credentials and records Worker-reported execution state.
- **orchestrator** — Go + Temporal. Drives the graph; dispatches each node as a
  Temporal activity for a Worker to execute.
- **worker** — Go + Temporal. Runs executors (Docker, Kubernetes) and the
  agent callback server; receives work from Fleets.
- **web-ui** — React + Vite. Graph visualization, live monitoring, approval gates.
- **agent images** — `claude-code` (the AI agent) and `choruskube-dev` (a fuller
  dev toolchain on top).

## Getting started

Needs Docker + Docker Compose v2. See **[QUICKSTART.md](QUICKSTART.md)** to boot
the stack, **[ARCHITECTURE.md](ARCHITECTURE.md)** for how it fits together, and
**[PRIVACY.md](PRIVACY.md)** for the anonymous opt-out telemetry.

## License

[AGPL-3.0](LICENSE).
