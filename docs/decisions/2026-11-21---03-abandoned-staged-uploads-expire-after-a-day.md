# Abandoned staged uploads expire after a day

**Status:** current

## Context

A file attached in the run-start dialog waits in a staging area under the storage prefix until a
run takes it. A start moves it into the run and deletes the staging copy on a best-effort basis, so
a staged object can be left behind: the dialog is abandoned, a run-start fails before it discards
its staging copy, or the move's own best-effort delete fails. Nothing deleted these objects: the
run-expiry pass finds a run's tree through its run row, and a staged object has no run row to key
off.

## Decision

**The retention job also deletes staging objects older than a day, keyed on the object's own age,
and only while a retention period is set.** Staging is transient by contract — the services that
stage a file or text for a run start it in the same request — so an object's age there is itself a
positive record that it was abandoned, not something a deployment has to opt into separately from
run-tree retention. `ArtifactRetentionService.deleteAbandonedStaging()` lists the bucket's top-level
storage prefixes and deletes everything under each one's `staging/` segment whose `LastModified` is
more than a day old, via `ObjectTreeDeleter.storagePrefixes()` and
`ObjectTreeDeleter.deleteModifiedBefore(prefix, cutoff)`. Each prefix is swept independently: a
failure on one is logged and the rest still run, and the run-expiry pass and the staging sweep each
get their own error handling in `ArtifactRetentionReconciler`, so a failure in one never skips the
other.

**Rejected: a sweep deleting any object whose owning row is missing.** That would cover staging and
every other orphan with one path, but it turns a wrong or restored database into data loss: a
missing row would stop meaning "abandoned" and start meaning "deleted by mistake." Keying the sweep
on the object's own age instead never depends on the database being complete or correct.

## Consequences

- A storage prefix found only by listing the bucket, with no row backing it any more, is still
  swept — the mechanism needs no lookup from a prefix to anything in the database.
- A run-start dialog kept open for more than a day loses its attachments; the start then fails as a
  whole with the existing generic error for a staged file that cannot be moved.
