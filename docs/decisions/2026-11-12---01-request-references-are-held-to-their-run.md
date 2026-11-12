# Every reference a request carries is held to its run, at the write

**Status:** current

## Context

Presign already refuses an agent a path outside its run's own storage tree: the run's storage
prefix, then `/runs/{runId}/`, with no `..` anywhere. Nothing else in the API server applied that
same rule to a path once it was *recorded* rather than read. Four writers record an execution's
artifact paths — the agent's own status write, the Worker's status write, the orchestrator's
completion and review-history write, and a gate's decision, whose attachment paths come back from
whatever submitted the decision. Two more writers record a repository: pull-request registration
and a repo-base report. A reader later trusts whatever path or repository a row names, whoever
wrote it, and lists and reads it with platform credentials. So a forged reference in any of these
six writes would have let its row's owner reach another run's objects or another run's repository
through a reader that never re-checks what a writer already should have.

## Decision

**One containment rule, `RunStorageScope`, is shared by presign and by every write that records an
artifact or attachment reference.** `contains(runId, path)` is presign's own test, factored out
unchanged: the path must start with the run's resolved storage prefix followed by
`/runs/{runId}/`, and must contain no `..` anywhere. `requireContainedRefs(runId, refsJson)` applies
it to a whole refs object — a null, blank or `{}` value carries no paths and is not checked, a
value that is not a JSON object of string values is refused, and any value that fails `contains` is
refused — before anything is read or stored.

- The node-status write and the review-history write (`InternalRunService`) call it first, before
  any setter and before the output-spec and escalation checks that already list storage at the
  reported path — so a write that would otherwise reveal whether another run's objects exist is
  refused before it reads anything.
- A gate's own record (`GateFinalizeService.recordOnGate`) calls it before any setter, covering
  both the hold path's server-built guidance and the two completion paths' stamp attachments.
- The gate-decision intake (`RunService.parseAttachmentRefs`) applies the same rule to each
  attachment value, right after its existing shape check and ahead of the story-version re-check,
  the claim, and the signal — so a forged attachment never reaches either gate record write.
- A write that carries no artifact paths is not checked, so the orchestrator's existing answer to a
  refused completion — recording the node failed, without paths — always goes through, and the
  node fails as retryable instead of hanging.

**"The run's own repositories" is `GraphSnapshotBuilder.reposForRun`** — the same list the run's
GitHub token scope is already built from. Pull-request registration and a repo-base report each
refuse a repository outside that list with the same `NotFoundException` an unknown repository id
already produces, so the refusal does not confirm that the other repository exists. A repo-base
report used to store any repository id with no check at all; an unknown or foreign one now answers
404 instead of 204, and nothing is written.

## Consequences

- **A completion, review-history write, or gate decision naming a path outside its run's tree now
  answers 400** instead of being stored. Every honest writer already stays inside its own run's
  tree, so no in-flight run or existing agent image needs to change.
- **A repo-base report naming an unknown repository now answers 404** instead of being stored with
  204.
- **Presign's own behaviour is unchanged** — the same test, now shared rather than duplicated.
- **Stored rows are never rewritten.** The rule holds every write from this change on; it neither
  re-checks nor corrects a row stored before it.
- **Keys the server builds satisfy the rule too.** An uploaded file's name is sanitised so the key
  built from it never contains `..`; a server-built key the rule refuses would fail an honest gate
  decision.

## Rejected

- **Checking only at read time.** It would cover rows already stored, but every reader — and every
  reader yet to be written — would have to remember to use the checked accessor, where the write
  sites needing the check number three.
- **Checking at both read and write time.** Covers stored rows too, but keeps the read side's cost
  in full for no write-side benefit, since an honest write can never produce a row the read-side
  check would need to catch.
- **Scoping pull-request and repo-base registration to the run's organisation rather than its own
  repositories.** Simpler, and still stops a cross-organisation reference, but a run's rows could
  still name another project's repository in the same organisation, which merge-on-approval would
  then act on.
