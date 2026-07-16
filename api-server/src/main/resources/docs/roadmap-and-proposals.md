<!-- DO NOT add a # H1 heading here — the title comes from index.json and is rendered by the web UI -->

## The Roadmap Page

The **Roadmap** page organizes work as a three-level hierarchy: **Epics** contain **Stories**,
and Stories contain **Tasks**. Only a Task can be started as a workflow run — Epics and Stories
are containers whose status and progress are derived automatically from the Tasks underneath
them. This closes the loop between business intent (an Epic) and execution (a Task's workflow
run) without a separate "mark done" step at every level.

Navigate to **Roadmap** in the left sidebar to view your Epics, then drill down into a Story and
a Task to start work.

## Creating an Epic

1. Click **New Epic** in the top-right corner of the Roadmap page.
2. Fill in the fields:
   - **Title** _(required)_: a concise name for the initiative (e.g., "Add user invitation flow")
   - **Description** _(required)_: what the feature does and why it matters
   - **Motivation** _(optional)_: business context, user story, or success criteria
   - **Software Project** _(required)_: the Git repository or Repository Group this Epic targets
3. Click **Create** — the Epic appears in the Roadmap list with status `backlog`.

An Epic's Software Project is inherited by every Task created under it — it cannot be changed
per-Story or per-Task, and changing it on the Epic later does not retarget Tasks that already
exist.

## Drilling Down: Stories and Tasks

Click an Epic to open its detail view, which lists its Stories. Click **New Story** to add one:

- **Title** and **Description** are the only fields — a Story doesn't carry its own Software
  Project or motivation; it inherits both conceptually from its Epic.

Click a Story to open its detail view, which lists its Tasks. Click **New Task** to add one, the
same way. A Task is the leaf of the hierarchy and the only thing that can be started as a
workflow run.

## Starting and Restarting a Task

Open a Task's detail view and click **Start** to launch a workflow run scoped to the Epic's
Software Project. The Task moves to `in_progress` and the run appears immediately with a live
status.

Every run a Task ever launches stays visible in its **Run History** list, newest first — restarting
a Task after a failed or cancelled run does not lose the earlier attempt, it adds a new run
alongside it. **Restart** appears once the Task's most recent run has failed or been cancelled.

## Completing a Task

Once a Task's most recent run finishes successfully, click **Complete** on the Task detail view to
move it to `done`. Completion is gated on that most recent run being terminal — you cannot mark a
Task done while a run is still in progress, and there is no way to mark a Task done without ever
running it.

## Status and Progress Are Derived, Not Set

Only a Task has a status you set directly (`backlog` → `in_progress` → `done`, driven by
starting and completing it). An Epic's and a Story's status is computed every time you view them
from their descendant Tasks:

| Derived status | When it applies |
|---|---|
| `done` | Every descendant Task is `done` |
| `in_progress` | At least one descendant Task has been started |
| `backlog` | No descendant Task has been started yet (an empty Epic or Story also reads as `backlog`, never `done`) |

Alongside the status, an Epic or Story also shows a **progress** count (e.g., "2/5 tasks done").
There is no manual "mark done" or "reopen" action at the Epic or Story level — the only way to
move a parent forward is to start and complete the Tasks underneath it.

## Editing and Deleting

Only items still in `backlog` status can be edited or deleted — once any descendant Task has
left `backlog`, the Epic, Story, or Task above it is read-only to preserve the audit history of
work in progress. There is currently no way to abandon or archive an Epic, Story, or Task once
work has started short of letting every Task under it reach a terminal state — see the project's
open caveat on this (Caveat 6 in the work-hierarchy design) if you need to cancel in-flight work.

## Best Practices

- **Keep Tasks atomic**: one Task = one workflow run's worth of change. Avoid Tasks that bundle
  many unrelated changes — they are harder to estimate and to review as a single run.
- **Write a clear Epic motivation**: agents read the Epic's motivation field as context when
  proposing Stories and Tasks. A well-written motivation produces better AI output than a vague
  title alone.
- **Use Task descriptions as acceptance criteria**: write what "done" looks like so both humans
  and AI agents know when a Task's run is complete.
- **Review completed Epics regularly**: once every Task under an Epic is `done`, it's a good
  signal to confirm the initiative actually shipped what was intended.
