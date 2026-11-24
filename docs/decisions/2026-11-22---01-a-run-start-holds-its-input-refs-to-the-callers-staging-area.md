# A run start holds its input refs to the caller's own staging area

**Status:** current

## Context

A run start's `inputAttachmentRefs` names a map of file name to a staged object key, built by an
earlier upload. The start moved every named key into the run's own tree with platform credentials
and then deleted it from its source — trusting the request completely. Nothing checked that a key
sat in the caller's own staging area, or that a name was a plain file name. A forged ref could copy
another tree's object into a run under attacker control, and the delete step would then also remove
it from wherever it actually lived, with its owner never told why.

This extends `2026-11-12---01` to one more write that entry's writers did not include: a run
start's `inputAttachmentRefs`. It does not touch anything that entry already decided.

## Decision

**`UploadService.admitStagingRefs` runs once, in `RunService`'s private `startRun`, right after the
template and inputs validate and before anything is written, placed, preflighted or read from the
object store.** A null, blank or `{}` value carries nothing to check and resolves no storage prefix,
so a start without attachments needs no tenant scope. Otherwise the value is parsed exactly once:
every key must sit below the caller's own staging segment with no `..` anywhere, and every name must
be a plain file name — one the upload sanitiser already leaves unchanged. Any failure refuses the
whole value with one fixed 400 (`UploadService.INPUT_REFS_REFUSED`) that names no key and reads the
same whether or not the named object exists.

`copyStagingToRun` now takes the admitted `Map<String, String>` instead of the raw refs string, and
never re-reads the request's own value — so no ref can reach the copy, or the delete that follows
it, without having passed the check. A client that sends back exactly what its own upload returned
sees no difference; the web UI and every server-side starter already do.

The sanitiser that builds a staged key's name is changed to agree with the rule it must now satisfy
end to end: a name made only of dots (`.`, `..`, or a longer run) collapses to the placeholder
`"file"`, the same one it already used for an empty name, instead of to the dot segment `.`.

A deployment whose storage prefix differs per organisation gets this containment for free through
the same storage-prefix seam the staging key is built from — the rule reads the caller's prefix only
through that seam, and the prefix is the one the request's own tenant scope resolves.

## Consequences

- **A run start naming a key outside the caller's own staging area, or a name the sanitiser would
  have changed, now answers 400 before any run row, ownership row, placement, preflight, or
  object-store call.** Nothing is written, read, or deleted.
- **A malformed `inputAttachmentRefs` value (not JSON, not an object, or a non-string entry) is a
  400 from the same check**, where it used to surface as a 500 out of the move.
- **In-flight runs are unaffected.** The check runs only at a start; stored refs are never re-read
  or rewritten.
- **A ref repeating the same name is read once**, by the same JSON parse the check makes: whichever
  entry the parser keeps for that name is the only one ever admitted or refused, so a foreign key
  never reaches the copy through a name collision either way round.

## Rejected

- **Naming only a staging id and deriving every key server-side.** Closes the hole just as well but
  changes the refs contract for every caller — the web UI and every server-side starter — for no
  extra containment this check does not already give.
- **Recording each staged upload server-side and accepting only a recorded id.** Binds a ref to its
  uploader as well as to the caller's own area, but costs a new table with its own migration and
  expiry for no additional boundary this check does not already enforce.
- **Checking inside the move, against the run's own resolved prefix, instead of at intake against
  the caller's.** Keeps source and destination judged by one resolved value, but the refusal would
  then land after the run row, ownership, quota, placement and preflight — so only a rollback would
  leave nothing behind, and a quota or credential refusal could answer first regardless.
- **Sanitising a non-plain name silently instead of refusing it.** Never refuses a start, but two
  different names can then collapse onto the same key, silently replacing one input with another.
