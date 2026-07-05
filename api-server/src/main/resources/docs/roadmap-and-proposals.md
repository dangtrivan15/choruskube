<!-- DO NOT add a # H1 heading here — the title comes from index.json and is rendered by the web UI -->

## The Roadmap Page

The **Roadmap** page is a lightweight backlog for tracking feature ideas and their progress
through automated workflows. It is designed to close the loop between business intent (a feature
proposal) and execution (a workflow run).

Navigate to **Roadmap** in the left sidebar to view and manage proposals.

## Feature Proposals

### Creating a Proposal

1. Click **New Proposal** in the top-right corner of the Roadmap page.
2. Fill in the fields:
   - **Title** _(required)_: a concise name for the feature (e.g., "Add user invitation flow")
   - **Description** _(optional)_: what the feature does and why it matters
   - **Motivation** _(optional)_: business context, user story, or success criteria
3. Click **Save** — the proposal appears in the **Backlog** column with status `backlog`.

### Proposal Statuses

| Status | Meaning |
|---|---|
| `backlog` | Proposal is queued — not yet linked to an active run |
| `in_progress` | A workflow run is currently executing against this proposal |
| `done` | The proposal has been implemented and the associated run completed |

Status transitions happen automatically when a run is started (→ `in_progress`) and when the
run reaches a terminal state (→ `done` on success, back to `backlog` on failure/cancel).

## Linking a Proposal to a Run

When you click **Start Run** from the Roadmap page, the selected proposal is automatically linked
to the new run. You can also link a proposal during the **Start Run** dialog by selecting it from
the proposal dropdown.

Each run can be linked to at most one proposal. A proposal can have multiple historical runs
(e.g., first attempt failed, second succeeded).

## Editing and Closing Proposals

### Editing

Click the proposal card to open the detail view. Click **Edit** to modify the title, description,
or motivation. Only proposals in `backlog` status can be edited — in-progress and done proposals
are read-only to preserve audit history.

### Closing / Archiving

To mark a proposal as done without running a workflow:

1. Open the proposal detail view.
2. Click **Mark as Done**.
3. Confirm — the proposal moves to the **Done** column.

To reopen a closed proposal, click **Reopen** from the detail view.

## Best Practices for the Backlog

- **Keep proposals atomic**: one proposal = one deliverable change. Avoid "big-bang" proposals
  that bundle many unrelated features — they are harder to estimate and track.
- **Write a clear motivation**: agents read the proposal's motivation field as context. A well-written
  motivation produces better AI output than a vague title alone.
- **Review done proposals regularly**: archive proposals that are no longer relevant to keep the
  Roadmap page focused on active work.
- **Use the description field for acceptance criteria**: write what "done" looks like so both
  humans and AI agents know when the proposal is complete.
