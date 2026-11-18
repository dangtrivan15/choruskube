# Agent uploads are sized and budgeted per run

**Status:** current

## Context

An agent's upload presign carried no size, so the URL it got back accepted a body of
any length, and the api-server counted nothing against it. A run, a customer's
Worker, or a bug in the CLI could write without bound. A deployment that puts a proxy
in front of the store is doubly exposed: SigV4 cannot express a maximum, so only the
store itself — the last line of defence — was refusing an oversized body.

## Decision

**A PUT presign must declare the upload's exact byte count, and the api-server signs
exactly that length into the URL.** The `artifact` CLI measures the local file with
`wc -c` before presigning and sends it as a `size` query parameter; `PresignService`
requires a non-null, non-negative `contentLength` for a PUT and signs it via
`PutObjectRequest.contentLength`, so the store itself refuses any other length. A GET
carries no body and so no size: `PresignService` now rejects a non-null
`contentLength` on a GET just as firmly as it requires one on a PUT. The presign
cache key gains the size for the same reason it already carries the endpoint — a URL
signed for one length must never be served to a caller that declared another.

An unsized PUT presign is refused outright (400), naming the missing declaration,
rather than signed as before or booked at a placeholder charge. Signing it unsized
behind a transition setting would leave the hole open to exactly the caller the
budget exists for, and a CLI older than this change simply fails its uploads against
an api-server that carries it — a deployment that builds its agent images from the
same tree as its api-server never hits this gap; one that publishes images
separately must hold its api-server back until every image it launches carries the
sized CLI.

**Each run gets a byte budget, enforced as a reservation ledger at presign time, not
after the fact.** `ArtifactUploadBudget.admit(runId, objectKey, declaredBytes)` locks
the run row, refuses a closed run (409) before touching the ledger, and books the
upload only if the run's total would stay within `objectstore.run-upload-budget`
(default 512 MB, overridable per deployment); past it, the presign is refused (413)
naming the budget, the run's already-booked bytes, and the declared size. The lock is
what makes two presigns racing for the last bytes of the budget admit at most one of
them — under read-committed isolation alone, both could read the same pre-booking
total and both pass the check. A refusal books nothing.

**A booking is per run and object key, and holds the largest size that key was ever
signed for — never the latest.** A presigned URL cannot be revoked once issued, so if
a key's booking shrank on a later, smaller presign, an earlier, larger URL would
still be valid and could write more than the ledger now counts. Booking the maximum
instead means a key that shrinks keeps its larger reservation; the alternative of one
booking row per presign, summed, would let a retry or a rewrite eat the budget for no
new bytes actually written. The booking lives in its own table, one row per run and
object key, and is deleted when the run row is, so it is never in the way of
deleting a run and never outlives it.

**The run-open check is part of admission, not an afterthought.** A job secret stays
valid after its execution ends, so without this check an agent could still write into
a closed run's tree — including, in a deployment that expires a closed run's
artifacts after a retention period, a tree that will be deleted with nothing left to
clean up what was written after.

## Consequences

- A PUT without a declared size, for a closed run, or past its budget gets a 400, 409
  or 413 respectively, with no URL in the body and nothing booked. The CLI retries
  only 408, 429 and 5xx, so any of these fails the upload — and the node — at once,
  with the server's message surfaced and never a signed URL.
- The sized CLI works unchanged against an api-server that predates this change,
  which simply ignores the extra query parameter; the rollout can land one side at a
  time.
- Every existing `PresignService.generatePresignedUrl` call site moved to the new
  four-argument form (`UUID runId, String objectPath, String method, Long
  contentLength`); the three-argument form no longer exists.
