# Closed runs' artifacts expire after a retention period a deployment sets

**Status:** current

## Context

Nothing deleted a run's storage tree once the run closed, so an artifact bucket only grew, and any
capacity limit on it would become the date every run's writes stop. A closed run's tree — node
outputs, parked sessions, gate attachments, run inputs and the run log — is read only while the run
is open, with the exceptions that
[2026-11-20---01-story-spec-versions-keep-their-final-review.md](2026-11-20---01-story-spec-versions-keep-their-final-review.md)
moved into the database.

## Decision

**A scheduled api-server job deletes a closed run's whole storage tree once the retention period
has passed since the run closed, and records when on the run.** The store's own expiry by object
age was rejected: it counts from each object's write rather than from the run's close, and nothing
would tell the run page that the files are gone. The run's database rows — node results, logs,
review history, pull requests and upload bookings — stay.

**The period is off in core and a deployment opts in.** `objectstore.artifact-retention`
(`OBJECT_STORE_ARTIFACT_RETENTION`, for example `30d`) is unset by default, and while it is unset a
pass returns before reading anything, so upgrading a self-hosted install deletes nothing. A
deletion cannot be undone, while keeping data is the behaviour every install already has. A period
under a day refuses to start the api-server, because a bare number binds as milliseconds and would
delete every closed run's tree on the first pass.

**Each pass takes the oldest batch of closed runs past the period that have not expired, skipping
any run whose tree a reader still needs:** a Story Design run whose spec version has not captured
its final review, and a completed Story Design run not yet recorded as a version. Both expirable
queries share one definition, `WorkflowRunRepository.EXPIRABLE`. For each run the job deletes every
object under the run's tree, and records `artifacts_expired_at` only once the tree lists empty, so
a run never reads as expired while objects remain. A failure leaves the run unrecorded for the next
pass. The record is a conditional update, and the entity maps the column read-only, so a concurrent
pass or replica only repeats work and a whole-entity save of a run loaded earlier cannot write the
stale null back. The change is announced on the run's existing topic as `run_artifacts_expired`.

**The close time is `completed_at`.** Every terminal status sets it, the user-cancel path included.
A run closed before the cancel path set it has none; the migration stamps such a run with the
migration's own time, which postpones its deletion and never hastens it.

**The run API exposes `artifactsExpiredAt`, and the run page renders exactly that.** When it is set,
a node's artifacts section says the artifacts expired on that date and neither lists nor opens
files; when it is null, the section is unchanged. The page never infers expiry from a missing file,
which cannot tell an expired file from a lost one, and never computes the period itself, so a
period that differs per deployment needs no UI change.

**End-to-end suites keep retention off.** A short period set for a whole stack would expire other
specs' artifacts mid-suite. A route present only in the `e2e` profile instead expires one named run
through the real pass, with every condition but the period applied.

## Consequences

- The first pass after a deployment sets a period deletes, at once, every run that closed longer
  ago than the period. A deployment postpones it by leaving the setting unset until it is ready.
- The rule from 2026-11-20---01 now binds every new reader: anything that reads a closed run's tree
  after the run closes either copies what it needs into the database first, or adds itself to
  `WorkflowRunRepository.EXPIRABLE`'s skips.
- Retention finds trees through run rows. A tree whose run row is gone — today only through a
  deletion path outside this mechanism — is not reached, and whatever deletes the row must delete
  the tree first.
- Deleting relies on the store's ordinary write grant: on SeaweedFS 4.37 an identity holding only
  List, Read, Tagging and Write on the bucket deletes in batches, which
  `ObjectTreeDeleterIntegrationTest` pins.
- Backups of the store keep deleted objects for their own retention window.
