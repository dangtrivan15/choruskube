# Gate attachments are admitted only at an open gate

**Status:** current

## Context

A reviewer's decision with files is two requests: the web UI first posts the files, then signals
the decision with the refs it got back. `GateAttachmentController#uploadGateAttachments` checked
only that the node execution belongs to the run, so it wrote for a gate already decided,
finalizing or skipped, and for a gate of a closed run, for as long as the run row existed. Nothing
bounded that cost, and a write landing after
[2026-11-20---02](2026-11-20---02-closed-runs-artifacts-expire-after-a-retention-period.md)'s
retention job had deleted the run's tree would sit in a tree the run already reports as expired,
since that job never revisits an expired run.

## Decision

**The endpoint admits an upload only for a node execution that is `awaiting_human`, on a run that
is not in `WorkflowRunStatusGroups.TERMINAL`; anything else gets a 409 before any object is
written.** The check runs after the existing run lookup, organization access check and
node-execution run-membership check, and before the storage prefix is resolved or anything is
uploaded. It reuses the plain-text `ConflictException` the gate signal already answers with when
its own claim (`compareAndSetStatus(... awaiting_human -> running)`) loses, so the web UI's
decision-failure toast renders it with no change on that side. The awaiting condition is exactly
the signal's own claim precondition, so the two are one rule stated twice and must change
together — a gate status the claim later accepts needs the same addition here.

Two alternatives were rejected:

- **Admit a gate that is awaiting or finalizing.** A finalizing approval has already fixed the
  refs it will act on, so a later upload would never be referenced by anything.
- **Admit any gate of a run that has not closed, regardless of the gate's own status.** A decided
  or skipped gate would keep accepting writes for as long as the run stayed open, which is the same
  unbounded cost this decision removes, just scoped to one gate instead of the whole run.

**The check is a read, not a claim, and takes no lock.** An upload admitted while the gate was
awaiting can still land after a concurrent claim or a concurrent close finishes first; the file is
then unreferenced by any decision, and the signal that would have carried it is refused as it
already is today. This is accepted rather than closed with a lock or a two-phase claim-then-upload
contract: either would hold a database row locked across an object-store write, or change the web
UI's upload-before-signal order for every caller to protect a race that only ever strands a few
bytes. The object survives in the run's tree until
[2026-11-20---02](2026-11-20---02-closed-runs-artifacts-expire-after-a-retention-period.md)
deletes the whole tree; that job's own one-day minimum retention period means the write always
lands in a tree retention has not yet reached, so it is deleted with the rest of the tree rather
than orphaned past an expiry.

## Consequences

- An API caller that uploads before a gate opens or after it has been decided — something the web
  UI itself never does — is now refused with 409 instead of succeeding into a run's tree no
  decision will ever read.
- The leftover-object race is an accepted cost, not a monitored one: nothing today counts
  unreferenced gate-attachment objects. See
  [2026-11-18---01](2026-11-18---01-agent-uploads-are-sized-and-budgeted-per-run.md) for the
  budget these uploads stay outside of, and
  [2026-11-21---02](2026-11-21---02-server-side-run-writes-are-bounded-per-request.md) for the
  per-request bound on the two other server-side writers into a run's tree.
