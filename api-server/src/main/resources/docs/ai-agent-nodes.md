<!-- DO NOT add a # H1 heading here — the title comes from index.json and is rendered by the web UI -->

## What an AI Agent Node Does

An **AI Agent Node** is a workflow node that spawns an autonomous AI agent (Claude Code) in a
Kubernetes pod. The agent:

1. Reads its task from a structured configuration file
2. Reasons about the problem, reads relevant code, and writes changes
3. Commits code to a working branch and optionally opens a Pull Request
4. Submits a **routing decision** that controls which edge the workflow follows next
5. Exits — the pod is reclaimed by Kubernetes

The agent operates entirely within the pod's filesystem; it communicates results back to the
orchestrator only via the `report-result` CLI command and output artifacts in `/workspace/out/`.

## The Agent Pod Lifecycle

### Pod Startup

When the orchestrator signals the API server to execute an AI Agent Node, the API server:

1. Creates a per-execution Kubernetes Secret containing the node's credentials and OAuth token.
2. Spawns a Kubernetes Job that mounts the secret and the agent container image.
3. The agent's `entrypoint.sh` sets up the environment and starts the Claude Code session.

### Reading Context: config.json

The agent reads its primary task from `/workspace/config.json`, which contains:

```json
{
  "run_id": "...",
  "node_execution_id": "...",
  "prompt": "The task the agent should perform",
  "system_prompt": "System-level instructions and context",
  "input_artifacts": { "key": "object-storage-path", ... }
}
```

### Reading Prior Node Output: run_log.md

The accumulated output of all prior nodes is available at `/workspace/in/run_log.md`. This file
contains the results, decisions, and artifact paths from every node that ran before this one.
Agents should read this file to understand the context established by earlier nodes.

### Writing Output Artifacts

The agent writes output files to `/workspace/out/`. All files in this directory are automatically
uploaded to S3-compatible object storage when the agent pod exits. Uploaded artifacts are accessible:

- In the **Artifact Browser** tab of the Run Monitor
- As inputs to subsequent nodes (referenced by path in the run log)

Common artifact patterns:
- `spec_and_plan.md` — design documents
- `summary.md` — a brief report of what the agent did
- `test_report.md` — test results

## Reporting Results

### The report-result Command

The agent signals its routing decision by running:

```bash
report-result <decision>
```

The `<decision>` string must match one of the condition values on the node's outgoing edges
(e.g., `approved`, `rejected`, `passed`, `failed`). If no matching conditional edge
exists, the workflow follows any default (unconditioned) edge.

### How Decisions Affect Routing

| Decision | Typical usage |
|---|---|
| `approved` | Node's work is accepted; continue the pipeline |
| `rejected` | Something is wrong; route to a revision or error node |
| `passed` | Automated check succeeded; advance to the next stage |
| `failed` | Automated check failed; route back for rework |
| `revised` | Agent iterated on its own output; self-loop to retry |

Always call `report-result` exactly once before the agent exits. Missing the call causes the
workflow to stall at the node until it times out.

## Pull Request Creation

### How Agents Commit Code and Open PRs

The agent uses a pre-configured GitHub installation token (fetched via `fetch-github-token`)
to push commits to the working branch and open a Pull Request via the GitHub API.

Typical flow:
1. Make code changes in `/workspace/repo/<name>/`
2. `git add`, `git commit`, `git push` on the working branch
3. `gh pr create` (or API equivalent) to open the PR
4. `register-pr --repo-id <uuid> --pr-url <url>` to link the PR to the run execution

### PR URL Capture and Display

When the agent calls `register-pr`, the API server stores the PR URL against the node execution.
The URL is displayed in:
- The node detail panel in the **Run Monitor**
- The run summary in the **Runs** list

## Artifact Browser

Use the **Artifacts** tab in the node detail panel to:
- Browse all files written to `/workspace/out/` by the agent
- Download individual files
- Preview text files and markdown documents inline

## Agent Logs

### Log Streaming in the Run Monitor

Agent stdout and stderr are streamed in real time to the **Log Panel** in the Run Monitor.
Click a running node to open its log panel and follow along as the agent works.

### Log Levels and Filtering

Logs are tagged with levels (INFO, WARN, ERROR). Use the level filter in the Log Panel to
reduce noise during long-running tasks. The full unfiltered log is always available for download
via the Artifact Browser.
