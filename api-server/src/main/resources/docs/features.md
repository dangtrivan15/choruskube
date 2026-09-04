<!-- DO NOT add a # H1 heading here — the title comes from index.json and is rendered by the web UI -->

## Overview

ChorusKube is an AI-powered software delivery platform. This page provides a high-level tour of
all major capabilities. Click any feature name to read the dedicated guide.

## Core Features

| Feature | Description | Guide |
|---|---|---|
| Workflow Templates | Directed-acyclic-graph (DAG) templates define reusable automation pipelines | [Workflow Templates](workflow-templates) |
| AI Agent Nodes | Claude Code agent pods that write code, commit changes, and open PRs autonomously | [AI Agent Nodes](ai-agent-nodes) |
| Human Gates | Pause points requiring a human approval before the workflow continues | [Human Gates](human-gates) |
| Multi-Repo Support | Run workflows across multiple repositories using Repository Groups | [Multi-Repo Support](multi-repo-support) |
| Analytics | Run trend charts, throughput metrics, and bottleneck detection | [Analytics](analytics) |
| Pull Request Tracking | Capture and display PR URLs opened by agent nodes in the run detail view | [Running Workflows](running-workflows) |
| Roadmap | Epic → Story → Task backlog hierarchy, with Tasks startable as workflow runs | [Roadmap](roadmap-and-proposals) |

## AI-Powered Agents

Each AI node in a workflow run spawns a **Claude Code agent pod** that:

- Reads context from `/workspace/config.json` and the accumulated run log
- Writes output files to `/workspace/out/`
- Commits code changes to a working branch
- Opens a Pull Request via the GitHub API
- Submits a routing decision (`report-result`) that controls which edge the workflow follows next

See [AI Agent Nodes](ai-agent-nodes) for the full lifecycle.

## Human Gates

**Human Gates** pause a workflow at a designated node and notify reviewers via the **Approvals** page.
Reviewers can:

- Read the AI agent's output and attached artifacts
- Approve or reject the gate — the workflow routes accordingly

See [Human Gates](human-gates) for details.

## Multi-Repo Support

A single workflow can operate across multiple repositories simultaneously using **Repository Groups**.
Each repository gets its own Kubernetes namespace with provisioned RBAC and network policies.
The agent workspace exposes all repos under `/workspace/repo/<name>/`.

See [Multi-Repo Support](multi-repo-support) for setup and best practices.

## Analytics & Observability

The **Analytics** dashboard provides:

- Run volume trends over configurable time periods
- Throughput metrics (runs started vs. completed)
- Bottleneck detection — which node types take the longest
- Per-node timing breakdowns

See [Analytics](analytics) for interpretation guidance.

## Pull Request Tracking

When an AI agent opens a Pull Request, the PR URL is captured and displayed in the **Run Monitor**
alongside the node that created it. All PR links are accessible from the run detail view.

## Roadmap

The **Roadmap** page organizes work as **Epics** containing **Stories** containing **Tasks**.
Only a Task can be started as a workflow run; an Epic's and a Story's status and progress are
derived automatically from their descendant Tasks rather than set by hand. Every run a Task
launches — including restarts — stays visible as that Task's run history.

See [Roadmap](roadmap-and-proposals) for usage.

## Approval Flow

A typical Human Gate approval is a short exchange between a reviewer and the API server:

```mermaid
sequenceDiagram
  participant actor as Reviewer
  participant API as api-server
  actor->>API: Approve or reject
  API-->>actor: 200 OK
```

## Single-Tenant Workspace

The open-source core runs **single-tenant**: there is no identity provider, login, or
organization management. Every request is scoped to one built-in workspace; repositories
and credentials are configured for that workspace.
