<!-- DO NOT add a # H1 heading here — the title comes from index.json and is rendered by the web UI -->

## Starting a Workflow Run

### From the Roadmap Page

1. Navigate to **Roadmap** and locate the feature proposal you want to implement.
2. Click **Start Run** next to the proposal.
3. In the **Start Run** dialog, select a **Workflow Template** and a **Git Repository** (or Repository Group).
4. The proposal is automatically linked to the run. Click **Start**.

### From the Runs Page

1. Navigate to **Runs** in the sidebar.
2. Click the **Start** button in the top-right corner.
3. Fill in the **Start Run** dialog — template, repository, and optional proposal link — then click **Start**.

### Selecting a Git Repository

- If the template targets a single repository, select any Git Repo configured in your organization.
- If the template uses parallel fan-out, select a **Repository Group** — the workflow branches once per repo in the group.
- Only repositories with completed provisioning (namespace, RBAC) are shown in the selector.

## Monitoring a Run

### The Run List Page

The **Runs** page shows all workflow runs with their current status, start time, template name,
and linked repository. Click any row to open the **Run Monitor**.

### Run Monitor: DAG View

The Run Monitor renders the workflow graph as a live DAG. Each node shows:

- Its **name** and **type** (AI agent, human gate, script, etc.)
- Its **current status** (see color legend below)
- A **spinner** animation when the node is actively executing

### Node Status Colors

| Color | Status | Meaning |
|---|---|---|
| 🟡 Yellow | Running | Node is currently executing |
| 🟢 Green | Completed | Node finished successfully |
| 🔴 Red | Failed | Node encountered an unrecoverable error |
| ⏸ Blue | Waiting | Paused at a human gate |
| ⬜ Grey | Pending | Not yet started |
| ⬛ Dark | Skipped | Branch not taken due to routing conditions |

### The Log Panel

Click any node in the DAG to open its **Log Panel** on the right side of the screen. The panel:

- Streams logs in real time while the node is running
- Shows the full log history after the node completes
- Supports log-level filtering (INFO, WARN, ERROR)
- Displays the node's reported decision value when the run finishes

### The Artifact Browser

Switch to the **Artifacts** tab in the node detail panel to browse files written by the agent to
`/workspace/out/`. Click any artifact to download it or preview its content inline.

## Responding to Human Gates

When a run reaches a **Human Gate** node:

1. The node enters **Waiting** status and a badge count appears on **Approvals**.
2. Navigate to **Approvals** and click the pending item.
3. Review the gate's context, the run log summary, and any attached artifacts.
4. Optionally open a **Live Chat** session to interact with the AI agent before deciding.
5. Click **Approve** or **Reject** (or the custom decision label shown) to resume the workflow.

See [Human Gates & Live Chat](human-gates-and-live-chat) for a detailed walkthrough.

## Cancelling a Run

To cancel an in-progress run:

1. Open the **Run Monitor** for the target run.
2. Click the **Cancel** button in the top-right corner of the monitor.
3. Confirm the cancellation in the dialog.

Cancellation signals the Temporal workflow to stop. Running node executions are terminated; completed
node results are preserved. Cancelled runs cannot be resumed.

## Run History and Retention

All completed, failed, and cancelled runs remain visible in the **Runs** list indefinitely.
Run logs and artifacts are stored in object storage and are accessible via the **Artifact Browser**
as long as the run record exists.
