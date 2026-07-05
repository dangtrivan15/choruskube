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

### 6. Use Live Chat to Guide an Uncertain Agent

If an agent's output is close but not quite right, do not simply reject and re-run. Open a
**Live Chat** session, explain what needs to change, and then approve. This saves a full re-run
and produces a more targeted correction.

## Repository Management

### 7. Use Repository Groups for Multi-Repo Operations

When a change touches multiple services, use a **Repository Group** rather than running separate
workflows per repo. The fan-out / fan-in pattern ensures all repos are updated atomically (or
individually reported) within a single run.

### 8. Ensure RBAC Provisioning Completes Before Starting Runs

Newly created Git Repos require Kubernetes RBAC provisioning before they can be used in a run.
Check the repo's status in **Settings → Git Repos** — it must show `READY` before you select it
in the Start Run dialog. Starting a run against an unprovisioned repo will fail at the first agent node.

## Operations

### 9. Review PR Diffs Before Approving a Gate

When an AI agent opens a Pull Request and the workflow pauses at a gate, open the PR link in
GitHub and review the diff before approving. The Run Monitor shows the PR link in the node detail
panel. Do not approve based solely on the agent's summary.

### 10. Monitor the Analytics Page for Recurring Bottlenecks

Set a weekly routine to check the **Analytics** page. Recurring high P95 values on a specific
node type are a signal to simplify the node's prompt, break the node into smaller steps, or
add a dedicated human gate for iterative refinement.

## Security

### 11. Never Share JOB_SECRET or ORCHESTRATOR_SECRET

These secrets are injected by the platform into agent pods at runtime. Never log them, include
them in prompts, commit them to code, or share them. They rotate per-execution (JOB_SECRET) or
per-deployment (ORCHESTRATOR_SECRET).

### 12. Manage Per-Org Claude OAuth Tokens

Each org stores its own `CLAUDE_CODE_OAUTH_TOKEN` (long-lived, ~1-year TTL, generated via
`claude setup-token`) encrypted in the `org_ai_credential` table. The system org's token is
seeded from the `CLAUDE_CODE_OAUTH_TOKEN` env var on startup; non-system orgs configure it
via Admin UI → Integrations. Executors resolve the per-org token at pod-launch time and inject
it as `CLAUDE_CODE_OAUTH_TOKEN` into the per-execution K8s Secret. Rotate tokens via Admin UI;
an expired or missing token causes agent pod startup to fail with an authentication error.
