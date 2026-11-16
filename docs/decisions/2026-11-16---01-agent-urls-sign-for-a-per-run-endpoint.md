# Agent presigned URLs sign for a per-run endpoint; server-side access stays in-cluster

**Status:** current

## Context

An agent's presigned URL has always been signed for the api-server's own configured
object-store endpoint (`objectstore.endpoint`), because that endpoint is the only one
the api-server itself ever talks to. That stopped being enough once a deployment can
run agents somewhere that cannot resolve or route to the server's own in-cluster
endpoint name: the host baked into the URL has to be something that deployment's
agents can actually reach, while every server-side read and write — uploads from
`UploadService`, the orchestrator's own object-store traffic — keeps using the
endpoint it always has.

Core is single-tenant and runs its own agents in reach of its own endpoint, so it
never needs a second endpoint itself. The seam exists so a deployment that does can
plug in a resolver without the open-source presign path changing shape.

## Decision

**`PresignService` resolves the signing endpoint per run through a seam,
`ArtifactSigningEndpointResolver`,** rather than always using the server's own
endpoint. An empty result from the seam means "use the server's own endpoint"; a
present result must be an `http`/`https` scheme plus a host plus an optional port —
nothing else. `PresignService` validates this before ever handing the answer to a
presigner, so a seam answer carrying a path, query, fragment, or userinfo fails the
request with `IllegalStateException` instead of silently signing something the seam
did not actually name.

**The default, `OwnArtifactSigningEndpoint`, always answers empty.** Like the
existing `WorkloadNamespaceResolver`/`WorkloadAgentCredentialResolver` seams, it is
not a Spring bean — `PresignService` takes it as the `ObjectProvider` fallback, so an
install with no seam registered gets it by absence rather than by bean-scan
ordering, and behaves exactly as it did before this change.

**A thrown exception from the seam fails the presign request.** It is never caught
and downgraded to "use the server's own endpoint" — a broken seam must surface as a
failed presign, not a silent switch to signing for the wrong host.

**One `S3Presigner` per endpoint, all built from the same credential.**
`ArtifactPresigners` replaces the single `S3Presigner` bean `ObjectStoreConfig` used
to expose; it keeps the server's own endpoint (`ownEndpoint()`) and memoises a
presigner per endpoint (`forEndpoint(URI)`), built from the same access key, secret,
region, and path-style configuration every time — only the endpoint override varies.
Server-side object-store access (`S3Client`) is untouched: it still only ever talks
to the server's own endpoint.

**The presign cache key includes the resolved endpoint.** A URL signed for one
endpoint must never be served from cache to a call that resolved a different one for
the same method and key — the endpoint is folded into the cache key alongside the
verb and the object path.

**Presign refuses an empty or dot path segment before signing anything.** A path
containing a leading, trailing, or doubled `/`, or a literal `.`/`..` segment, is
never a real object key; `InternalArtifactController` now answers 400 for one,
checked before calling `PresignService` at all.

## Consequences

- A self-hosted install with no seam implementation registered signs every URL
  against its own endpoint exactly as before — `ownEndpoint()` is what
  `ObjectStoreConfig.artifactPresigners()` is built from, unchanged from the prior
  `s3Presigner()` bean's `endpointOverride`.
- A deployment that does register a seam can choose, per run, to sign for a
  different endpoint without the api-server's own object-store traffic moving off
  its own endpoint — that traffic never goes through `ArtifactSigningEndpointResolver`
  at all.
- `generatePresignedUrl` now takes the run id, so it can ask the seam which endpoint
  to sign for; every caller in core was updated to pass the agent execution's own
  run id, matching the invariant `RunScopeInterceptor` already enforces that an
  execution's run and the path's run are equal.
