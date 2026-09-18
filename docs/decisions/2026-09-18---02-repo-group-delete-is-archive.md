# Deleting a RepoGroup is an archive (soft-delete), not a hard delete or a guarded refusal

## Status

current

## Context

`RepoGroup` delete used to hard-delete the row. `Epic`, `Task`, and `Milestone` all carry a
foreign key to `software_project(id)` with no `ON DELETE` clause, so the hard delete would fail
with a `DataIntegrityViolationException` whenever any of those still referenced the group. To turn
that into a clean 409 instead of an unhandled 500, `RepoGroupController#delete` refused the delete
whenever the group had **any** `Epic`, any non-done `Task`, or a non-terminal run.

The result was backwards for a product owner: the more a project had shipped, the harder it was to
remove. A finished project — the exact one you most want to archive — was undeletable through the
UI, because completed roadmap work still counted against the guard, with the generic
"Failed to delete repo group" toast hiding why.

The base `SoftwareProject` already carries `deleted_at` and `@SQLRestriction("deleted_at IS NULL")`,
and `GitRepo#delete` already sets `deleted_at` — so a soft-delete mechanism existed, but the
`RepoGroup` path did not use it.

## Decision

**Deleting a `RepoGroup` archives it (soft-delete). Only an in-flight run blocks the delete;
completed Epics/Tasks ride along into the tombstone.**

- **Archive, not hard-delete.** `RepoGroupService#delete` sets `deleted_at` and saves. The row and
  its subtype row survive, so the `Epic`/`Task`/run FKs that still point at this
  `software_project_id` keep a live target (no integrity violation, no purge), and
  `@SQLRestriction` hides the group from every read path.
- **The only remaining guard is operational, not record-preservation.** A non-terminal run still
  returns 409, because an agent mid-run would keep resolving a project that just went invisible.
  Completed Epics/Tasks no longer block — they were only ever blocked to protect the old hard
  delete.
- **Archiving clears the group's members.** A member `git_repo`'s own delete is refused while it
  "is a member of a RepoGroup". Leaving `repo_group_member` rows on a now-hidden group would trap
  the member repo behind a group the user can no longer see or edit, so archiving drops the
  membership (orphanRemoval). That keeps the `git_repo` delete path correct without teaching it
  about archived groups.
- **No hard-purge and no restore UI were added.** Nothing is destroyed; `software_project.name`
  has no unique constraint, so an archived name does not block reuse; and there is no compliance
  driver for true erasure. Un-archiving is a `deleted_at` reset.

This intentionally leaves the two `SoftwareProject` subtypes deleting differently: `GitRepo`
soft-deletes and a reconciler then hard-deletes the tombstone (a two-phase removal), while a
`RepoGroup` soft-delete is a durable archive. They share only the `deleted_at` + `@SQLRestriction`
hiding, not the cleanup half.

## Consequences

- A restored (un-archived) `RepoGroup` is memberless and invalid until members are re-added
  (`applyMembers` requires at least one). Any future Restore affordance must re-attach members, not
  merely clear `deleted_at`.
- Reopening or mutating an `Epic`/`Task` under an archived group is not explicitly guarded — the
  group and its roadmap simply drop out of the UI via `@SQLRestriction` on the project, so there is
  no reachable screen to reopen from. A raw API call could still touch such an orphaned row; that
  changes nothing visible and was left out of scope.
- If a hard-purge is ever needed (compliance, true erasure), it is a separate guarded admin action,
  not a change to this delete path.
