<!-- DO NOT add a # H1 heading here — the title comes from index.json and is rendered by the web UI -->

## Overview

This page collects the most impactful patterns for using ChorusKube effectively and safely.
Each recommendation is actionable and based on common operational patterns.

## Workflow Design

### 1. Keep AI Node Prompts Focused and Scoped

Write node prompts that describe a single, well-bounded task. Broad prompts ("implement the entire
feature") lead to long-running agents, larger diffs, and harder reviews.

**Prefer:** "Implement the database migration for the invitations table, following the Flyway conventions in CONTRIBUTING.md."  
**Avoid:** "Build the invitations feature end-to-end."

### 2. Use Human Gates Before Irreversible Actions

Insert a Human Gate node before any action that is difficult or impossible to reverse: merging to
main, running a production migration, publishing a release, or sending external communications.
The gate gives reviewers a chance to catch errors before they become incidents.

### 3. Prefer Smaller, Composable Templates

Break complex workflows into smaller templates that can be composed or re-run independently.
A monolithic 12-node template is hard to debug — a failed node in the middle may require re-running
the entire thing. Smaller templates allow targeted re-runs and are easier to maintain.

## Agent Nodes

### 4. Provide Rich Context in the System Prompt

The system prompt sets the agent's operating context. Include:
- The project's architectural conventions (or a link to `CLAUDE.md` / `CONTRIBUTING.md`)
- The expected output format (e.g., "commit all changes and push to the working branch")
- Any constraints the agent must respect (e.g., "do not modify migration files")

### 5. Check Artifacts and Logs Before Approving

Before approving a human gate, open the **Artifact Browser** and **Log Panel** for the preceding
AI Agent node. Read the summary artifact and scan the logs for warnings. Approving without reviewing
can let subtle errors slip through.

## Repository Management

### 6. Use Repository Groups for Multi-Repo Operations

When a change touches multiple services, use a **Repository Group** rather than running separate
workflows per repo. The fan-out / fan-in pattern ensures all repos are updated atomically (or
individually reported) within a single run.

### 7. Ensure RBAC Provisioning Completes Before Starting Runs

Newly created Git Repos require Kubernetes RBAC provisioning before they can be used in a run.
Check the repo's status in **Settings → Git Repos** — it must show `READY` before you select it
in the Start Run dialog. Starting a run against an unprovisioned repo will fail at the first agent node.

## Operations

### 8. Review PR Diffs Before Approving a Gate

When an AI agent opens a Pull Request and the workflow pauses at a gate, open the PR link in
GitHub and review the diff before approving. The PR links appear in the run summary at the top of
the Run Monitor. Do not approve based solely on the agent's summary.

> [!WARNING]
> On a gate configured to merge pull requests (the Final Approval of Feature Development or Task
> Delivery), approving merges every registered PR — there is no separate confirmation step after
> you click Approve.

### 9. Monitor the Analytics Page for Recurring Bottlenecks

Set a weekly routine to check the **Analytics** page. Recurring high P95 values on a specific
node type are a signal to simplify the node's prompt, break the node into smaller steps, or
add a dedicated human gate for iterative refinement.

## Security

### 10. Never Share JOB_SECRET or ORCHESTRATOR_SECRET

These secrets are injected by the platform into agent pods at runtime. Never log them, include
them in prompts, commit them to code, or share them. They rotate per-execution (JOB_SECRET) or
per-deployment (ORCHESTRATOR_SECRET).

### 11. Keep the Claude OAuth Token Where the Work Runs

An AI node's agent launches with a Claude OAuth token (long-lived, ~1-year TTL, generated via
`claude setup-token`), injected as `CLAUDE_CODE_OAUTH_TOKEN` into its per-execution secret by
whatever runs the work. On a self-hosted install that is the Worker: it reads the token from
its credentials folder (`claude-oauth-token`) or, failing that, its own `CLAUDE_CODE_OAUTH_TOKEN`
environment variable. A missing token fails the AI node before its agent launches; an expired
one fails the agent with an authentication error. Rotate it by replacing the token where it is
held — a file replaced in the Worker's folder takes effect on the next run.
