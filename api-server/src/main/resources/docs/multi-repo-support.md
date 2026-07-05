<!-- DO NOT add a # H1 heading here — the title comes from index.json and is rendered by the web UI -->

## Software Projects and Git Repos

### Git Repo Entity

A **Git Repo** is the primary unit of repository configuration in ChorusKube. Each Git Repo:

- Points to a GitHub repository (owner/name)
- Gets its own **Kubernetes namespace** with provisioned RBAC and network policies
- Has an optional Docker registry mirror configuration for faster image pulls
- Tracks a **provisioning status** — repos must be fully provisioned before they can be used in a run

### Namespace and RBAC per Repo

When a Git Repo is created, ChorusKube automatically provisions:

- A dedicated Kubernetes namespace (`choruskube-<repo-id>`)
- Role-based access control (RBAC) so agent pods can read/write within the namespace
- Network policies that isolate the repo's workloads from other repos

This isolation ensures that a misbehaving agent in one repo cannot interfere with another.

## Repository Groups

### What a Repository Group Is

A **Repository Group** is a named collection of Git Repos that are targeted together in a single
workflow run. Repository Groups enable **fan-out** — the workflow spawns one parallel branch per
repo in the group, running the same node graph against each repo simultaneously.

### Creating and Managing Groups

1. Navigate to **Settings → Repository Groups**.
2. Click **New Group** and give it a name (e.g., "Backend Services").
3. Add one or more Git Repos to the group.
4. Save — the group is immediately available in the **Start Run** dialog.

You can add or remove repos from a group at any time. Running workflows are not affected by group
changes; the group membership is snapshotted when a run starts.

### Selecting a Group When Starting a Run

In the **Start Run** dialog, the repository selector lists both individual Git Repos and Repository
Groups. Select a group to trigger the multi-repo fan-out pattern.

## How Workflows Fan Out Across Repos

### Multi-Repo Run Behavior

When a run targets a Repository Group, the orchestrator:

1. Starts the workflow normally until it hits a **parallel split node**.
2. Forks one branch per repo in the group — each branch runs independently.
3. Each branch's agent pods see only their assigned repo.
4. A **parallel join node** waits for all branches to complete before the workflow continues.

### Agent Workspace Layout for Multi-Repo Runs

In a multi-repo run, the agent workspace exposes all cloned repositories under `/workspace/repo/`:

```
/workspace/
├── config.json            # Node execution configuration
├── in/
│   └── run_log.md         # Accumulated results from all prior nodes
├── out/                   # Output artifacts (write here)
└── repo/
    ├── service-a/         # First repo in the group
    ├── service-b/         # Second repo in the group
    └── service-c/         # Third repo in the group
```

Agents should navigate to the relevant repo with `cd /workspace/repo/<name>` before running
repo-specific commands. The `config.json` file identifies which repo(s) are in scope for this
execution.

## Best Practices

- **Complete provisioning before starting runs**: the Start Run dialog filters out unprovisioned
  repos, but ensure RBAC provisioning has finished (status: `READY`) to avoid startup delays.
- **Keep Repository Groups focused**: a group with many repos produces many parallel branches and
  can saturate the Kubernetes cluster. Start with small groups and scale up.
- **Use separate groups for different concerns**: a "frontend" group and a "backend" group allows
  targeted runs rather than always running across all repos.
- **Name repos and groups clearly**: agents receive the repo name in their workspace path —
  descriptive names (e.g., `api-server`, `web-ui`) make logs and artifacts easier to interpret.
