# Story spec versions keep their final review

**Status:** current

## Context

A Story revision reads the final review behind every earlier approved spec version, so it does not
silently reverse a decision an earlier review already made. Until this change it read that review
straight from the design run's storage tree — the same tree a deployment may delete once the
retention period it sets has passed since the run closed. A revision can start at any time after its
Story's spec was approved, long after the run that produced it has closed, so deleting that tree
would leave a revision running without a review it needs.

## Decision

**`story_spec_version` records its final review when it records the spec, and a revision reads it
from there, never from a run's storage.** `final_review` (nullable text) holds the review, or null
when the run had none; `review_captured_at` records when it was captured, or null when it has not
been yet. A `CHECK` constraint keeps the two consistent: `final_review` can hold text only once
`review_captured_at` is set. Recording a version's spec, `StorySpecLifecycleService.record()`, reads
the review from the same completed Story Spec Review execution it reads the spec from and captures
it immediately — a missing review file is a valid outcome ("had none"), captured the same as any
other, while any other read failure leaves the whole version unrecorded, exactly as a spec-read
failure already does.

Two alternatives were rejected. Retention could skip Story Design runs altogether, which needs no
schema change but keeps those runs' whole trees forever. Each review could be copied to a storage
prefix outside any run, which also needs no schema change but adds a second storage area whose own
retention would need designing. The spec version already holds the spec's text for the same reason,
and a review is a few kilobytes.

**A version recorded before this change starts uncaptured, and a reconciler pass backfills it from
its run's storage while that storage still exists.** `StorySpecLifecycleService.backfillReviews()`
runs every uncaptured version, oldest first: a version with no run captures "none" outright; one
with a run reads that run's final review execution, capturing "none" when the run never completed
one or its review file is gone, and the file's text otherwise. A failure on one version is logged
with ids only and leaves that version for the next pass, without blocking the others. The reconciler
runs this step after recording and before processing conflicts, so a conflict-driven revision in the
same tick finds every review the pass could capture, rather than being refused for one still
pending.

**A revision refuses to start while any version it depends on is still uncaptured**, rather than
falling back to a read of that version's run storage. `StoryDesignService.revisionInputs` stages a
prior review only from a version's captured `final_review`, skips a version with no run at all, and
throws `ConflictException`, naming the version, for one that has a run but no `review_captured_at`
yet. The alternative — reading the run's tree as a fallback — would still depend on that tree
staying around past the point this change exists to let it go, and silently dropping the review
instead would let a revision reverse a decision its own review already made. A human's Revise gets
the refusal as a 409, which in practice happens only in the seconds after this change deploys,
before the first backfill pass, or while a version's backfill keeps failing; the reconciler's
conflict step logs it as an expected refusal and retries on its next tick.

## Consequences

- `StorySpecService.recordApproved` takes the run's final review text (or null) as a fourth argument
  and captures it on insert; an idempotent repeat call for an already-recorded run keeps the first
  row's review unchanged.
- `StorySpecService.captureReview` writes a version's review only while it is still uncaptured, via a
  conditional update scoped to `review_captured_at is null`, so a concurrent pass's capture can never
  be overwritten by a slower one still working from stale data.
- `StoryDesignService` no longer reads a design run's storage at all when composing a revision's
  inputs, so the rule this decision sets — anything that outlives a run copies what it needs into the
  database before the retention period a deployment sets can reach that run's storage — covers every
  reader a revision has.
- Anything that deletes a design run's storage must skip that run while a `story_spec_version` row
  naming it has `review_captured_at IS NULL`, or the backfill reads a tree that is gone and captures
  "none" for a review that existed. The partial index `idx_story_spec_version_review_uncaptured`
  serves that lookup.
