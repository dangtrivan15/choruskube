<!-- DO NOT add a # H1 heading here — the title comes from index.json and is rendered by the web UI -->

## Starting a Workflow Run

### From the Roadmap Page

1. Navigate to **Roadmap**, open the Epic and Story that contain the Task you want to implement,
   and open that Task's detail view.
2. Click **Start** on the Task.
3. In the **Start Run** dialog, select a **Workflow Template** — the target **Git Repository** (or
   Repository Group) comes from the Task's Epic.
4. The run is automatically linked to the Task that started it, and appears in that Task's run
   history. Click **Start**.

### From the Runs Page

1. Navigate to **Runs** in the sidebar.
2. Click the **Start** button in the top-right corner.
3. Fill in the **Start Run** dialog — template and repository — then click **Start**. A run
   started this way is not linked to any Task.

### Selecting a Git Repository

- If the template targets a single repository, select any Git Repo configured in your organization.
- If the template uses parallel fan-out, select a **Repository Group** — the workflow branches once per repo in the group.
- Only repositories with completed provisioning (namespace, RBAC) are shown in the selector.

## Monitoring a Run

### The Run List Page

The **Runs** page shows all workflow runs with their current status, start time, template name,
and linked repository. Click any row to open the **Run Monitor**.

### The Run Summary

A summary strip under the run header shows the feature request (collapsed to its first line,
expandable inline or in a dialog), the software project, the Epic › Story › Task chain (each
segment truncated to one line, with the full title on hover or keyboard focus), and any linked
pull requests. It stays visible no matter which node you have selected. On a phone it collapses
to a one-line bar with a **Run info** button that opens the same information in a sheet, plus a
**Review …** (or **Failed: …**) button whenever a node needs you.

### Run Monitor: DAG View

The Run Monitor renders the workflow graph as a live DAG. Each node shows:

- Its **name** and **type** (AI agent, human gate, script, etc.)
- Its **current status** (see color legend below)
- A **spinner** animation when the node is actively executing

Opening a run on a laptop or desktop selects the node that needs you — a waiting gate, then a
failed node, then a running one — the first time you open it; selecting a different node updates
the page's URL, so a copied link reopens the same node. On a phone the graph starts at a
readable zoom centred on that node instead of shrinking the whole graph to fit.

### Node Status Colors

| Color | Status | Meaning |
|---|---|---|
| 🟡 Yellow | Running | Node is currently executing |
| 🟢 Green | Completed | Node finished successfully |
| 🔴 Red | Failed | Node encountered an unrecoverable error |
| ⏸ Blue | Waiting | Paused at a human gate |
| ⬜ Grey | Pending | Not yet started |
| ⬛ Dark | Skipped | Branch not taken due to routing conditions |

### The Node Detail Panel

Click any node in the DAG to open its detail panel — docked beside the graph on a laptop or
desktop, or a bottom sheet on a phone or tablet. The panel:

- Streams logs in real time while the node is running, and shows the full log history after it completes
- Supports log-level filtering (INFO, WARN, ERROR)
- Lists files the agent wrote to `/workspace/out/` — click any artifact to download it or preview its content inline
- Surfaces gate actions (Approve/Reject and the rest) inline for a node awaiting your decision

Closing the panel (or, on a phone, dismissing the sheet) returns you to an empty state that still
lists any node needing attention or currently running, so you're never looking at a blank panel
while something is happening.

## Responding to Human Gates

When a run reaches a **Human Gate** node:

1. The node enters **Waiting** status and a badge count appears on **Approvals**.
2. Navigate to **Approvals** and click the pending item.
3. Review the gate's context, the run log summary, and any attached artifacts.
4. Click **Approve** or **Reject** (or the custom decision label shown) to resume the workflow.

See [Human Gates](human-gates) for a detailed walkthrough.

## Cancelling a Run

To cancel an in-progress run:

1. Open the **Run Monitor** for the target run.
2. Click **Cancel** in the run header, or, on a phone, open the **⋯** menu and choose **Cancel**.

Cancellation signals the Temporal workflow to stop. Running node executions are terminated; completed
node results are preserved. Cancelled runs cannot be resumed.

## Run History and Retention

All completed, failed, and cancelled runs remain visible in the **Runs** list indefinitely.
Run logs and artifacts are stored in object storage and are accessible via the **Artifact Browser**
as long as the run record exists.
