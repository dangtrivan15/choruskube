# Any object store this project uses must act only on the object a presigned request's signature names

**Status:** current

## Context

A presigned URL is meant to authorize one method on one object. A header or query parameter added
after signing is not covered by the signature, so only the object store, and anything in front of
it, decides what such an addition can do. Testing against a pinned image found that a store can
take a forwarding prefix from the request itself, join it to the request path, and act on the key
that join rebuilds rather than the key the URL was signed for — a retarget — and that a retarget to
the bucket's root lists it, handing over every key the bucket holds.

## Decision

**Any object store a deployment of this project uses must act only on the object a presigned
request's signature names.** An input outside the signature — an unsigned `x-amz-*` header, a
forwarding prefix, an added query parameter — may fail the request or be ignored before it acts,
but it must never pick or change the object. The signature is computed over the method, the path
the store routes on, the full query and the headers named as signed; the payload hash and the
signing parameters echoed as headers never select an object and are exempt from that set.

**`PresignedRequestStoreContractTest` holds this contract against the exact image the compose
stacks and the other store-backed tests pin, between two run ids**, through the real presign path.
It asserts every row of the table under "What the object store must refuse" in
[ARCHITECTURE.md](../../ARCHITECTURE.md#what-the-object-store-must-refuse).
`web-ui/e2e/specs/presigned-request-contract.spec.ts` repeats the copy-source and retarget
refusals from inside a real agent container, against a URL the api-server actually minted, so the
check also covers the one hop the store-backed test cannot reach on its own.

**The pin moves only once the contract test passes at the new image, and only to a release whose
forwarding-prefix handling is gated behind a trusted-proxy allowlist that a deployment leaves
empty.** Upstream SeaweedFS takes a forwarding prefix from any peer before release 4.49 and from an
allowlisted proxy only from 4.49 on — a fact about the store, not a choice this project makes — so
4.49 is the floor any pin here may name.

### Rejected

- **A policy proxy in front of the object store for agent traffic.** Covers every store release but
  adds a hop to every transfer and a second signing endpoint; the store is the one component every
  path already reaches, so a fix placed there needs no new component.
- **Routing every agent through a deployment's existing proxy in front of the store.** Leaves the
  store itself trusting a caller-supplied forwarding prefix, and gives every other backend behind
  that same proxy the identical exposure for no new defense.
- **Refusing to presign a key that embeds the bucket name as a path segment.** Blocks one shape of
  the retarget while the store still honours the forwarding prefix for every other key.

## Consequences

- A store release that regresses this contract fails the build that pins it, before any deployment
  runs it.
- A forwarded-host header still rebinds the signed host next to the store: a caller with nothing in
  front of it could already send any Host header directly, so this changes no object and is
  accepted.
- A standard header added to a presigned PUT (content type, disposition, cache control) is still
  kept by the store; neither it nor the exempted payload-hash and echoed-signing-parameter headers
  changes which object is acted on.
- Every pin here, and the contract test's own image, names a release tag rather than a digest; a
  tag that moved under an unchanged pin would be caught only on the next deliberate bump, not by
  this test.
