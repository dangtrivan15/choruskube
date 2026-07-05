<!-- DO NOT add a # H1 heading here — the title comes from index.json and is rendered by the web UI -->

## What is a Human Gate?

A **Human Gate** is a workflow node that pauses execution and requires a human decision before the
run can continue. Gates are used to:

- Review AI-generated code before merging
- Confirm that a risky or irreversible action (deploy, schema migration, etc.) should proceed
- Provide additional context or guidance to an AI agent mid-workflow

When a run reaches a human gate, it enters **Waiting** status and remains paused until a reviewer
submits a decision.

## The Approvals Page

### Pending Gate Notifications

A **badge count** on the **Approvals** sidebar item indicates how many gates are waiting for your
review. The badge updates in real time via WebSocket.

### Reviewing Context Before Deciding

The Approvals detail view shows:

- The **gate prompt** — what the workflow is asking you to review or decide
- A summary of the **run log** — what has happened in prior nodes
- Any **artifacts** attached to the gate execution (e.g., diff files, test reports, spec documents)
- The **run graph** link so you can inspect individual node logs in the Run Monitor

Take time to review all available context before approving or rejecting.

### Attaching Files to Your Decision

You can attach files (e.g., annotated diffs, corrected specs) to your approval or rejection decision.
Click **Attach files** in the decision panel before submitting. Attached files are stored as artifacts
on the gate execution and accessible to subsequent AI agent nodes via the run log.

### Approve vs. Reject Flow

| Decision | Effect |
|---|---|
| **Approve** | The workflow follows edges conditioned on `approved`; the run continues |
| **Reject** | The workflow follows edges conditioned on `rejected`; the run may loop back, branch to a revision node, or terminate depending on the template |
| **Custom decision** | Some gates accept custom decision values (defined in the template); enter the value in the custom decision field |

## Live Chat Sessions

### Opening a Live Chat at a Gate

While reviewing a gate, click **Start Live Chat** to open a real-time conversation with the AI agent
currently assigned to the gate. The agent pod runs in a special `live_chat` mode and is ready to
respond to your messages.

### How Messages Reach the AI Agent

Messages you type are sent over STOMP/WebSocket to the API server, which relays them to the agent
pod. The agent reads your message, reasons about it, and sends a reply — also delivered via
WebSocket. The full chat transcript is visible to both you and the agent in real time.

### Ending the Session and Making a Decision

When you are satisfied with the agent's response:

1. Type your final instructions if needed.
2. Close the Live Chat panel.
3. Submit your **Approve** or **Reject** decision on the Approvals page.

The live chat session ends automatically when you submit your decision. The transcript is saved as
an artifact on the gate execution.

### Session Lifecycle and Timeouts

- A Live Chat session is bound to a single gate execution — it cannot be transferred to another run.
- If the session is idle for an extended period, the agent pod may be reclaimed; the chat transcript
  is preserved in the artifact store.
- You can start a new Live Chat session for the same gate as long as the gate is still in **Waiting** status.

## Writing Effective Gate Prompts

Good gate prompts reduce back-and-forth and help reviewers make faster, more confident decisions.

**Do:**
- State clearly what the reviewer needs to evaluate (e.g., "Review the generated migration SQL for safety")
- List specific acceptance criteria the reviewer should check
- Link to relevant documentation or prior context

**Avoid:**
- Vague prompts like "Review and approve"
- Prompts that ask the reviewer to re-read all prior node logs without a summary

**Example of a good prompt:**

> Review the Flyway migration at `api-server/src/main/resources/db/migration/V42__add_invitations.sql`.
> Confirm it is safe to run against a live database:
> - No `DROP TABLE` or `DROP COLUMN` without a guard
> - All new `NOT NULL` columns have a default or a backfill
> - Indexes are created `CONCURRENTLY` (Postgres) to avoid table locks

## Notification Behavior

- Gate notifications appear as a **badge count** on the Approvals sidebar item.
- Badge counts update in real time — no page refresh needed.
- Email notifications are not currently implemented; reviewers should monitor the Approvals page
  or integrate a webhook if immediate alerting is required.
