# The dormant registry-mirror injection seam is removed; warm DinD is the sole image-supply path

## Status

current

## Context

[2026-09-09---01-warm-dind-image-supply-over-registry-mirror.md](2026-09-09---01-warm-dind-image-supply-over-registry-mirror.md)
made a pre-warmed DinD sidecar image the image-supply mechanism and *retained* a
`WorkloadRegistryMirrorResolver` seam — defaulting to none — so a deployment that ran a
pull-through registry mirror / build cache could inject its endpoints (`REGISTRY_MIRROR`,
`BUILD_CACHE_REGISTRY`, `INSECURE_REGISTRIES`) into the DinD sidecar during
`WorkloadService.prepareWorkload`.

That seam then stayed dormant. No deployment registers a resolver bean: the default resolved none
everywhere, and the one closed deployment that had operated a per-tenant mirror retired it on the
strength of the warm-DinD mechanism. The seam threaded a mirror value through the whole workload
launch path — the `WorkloadRegistryMirrorResolver` interface and its `RegistryMirror` DTO, a field
on `PrepareWorkloadResponse`, the Worker's `ExecutionParams`, and the Kubernetes executor's
`addDindSupport` env injection — while never carrying a non-null value at runtime.

## Decision

**The registry-mirror injection seam is deleted. Warm (optionally per-project custom) DinD is the
only image-supply path.** This supersedes the seam-retention half of
[2026-09-09---01-warm-dind-image-supply-over-registry-mirror.md](2026-09-09---01-warm-dind-image-supply-over-registry-mirror.md);
its warm-DinD decision is unchanged and stands.

- Removed the `WorkloadRegistryMirrorResolver` interface, its default `NoRegistryMirrorResolver`,
  and the `RegistryMirror` record from `com.choruskube.core.executor`.
- Dropped the `registryMirror` component from `PrepareWorkloadResponse`, the mirror resolution in
  `WorkloadService.prepareWorkload`, and the Worker-side mirror plumbing
  (`ExecutionParams.RegistryMirror`, the workload-client DTO, and the Kubernetes executor's mirror
  env injection).

## Consequences

- Reintroducing an injected mirror now means restoring the resolver seam and the executor wiring,
  not merely registering a bean. That is the deliberate trade: the platform no longer carries a
  dormant public extension point that nothing exercised.
- Image supply is unchanged in practice — the seam already resolved to none everywhere, so no
  runtime behavior changes; an image absent from the warm/custom DinD still falls back to pulling
  from its origin registry.
- The build-cache (`BUILD_CACHE_REGISTRY`) agent-entrypoint block — which bootstrapped a
  `docker-container` buildx builder trusting a cache registry over HTTP — is removed as well:
  after the injection above is gone nothing sets the variable and no build step consumes the
  builder (image builds use plain `docker build`), so it was dead code, not a live contract.
- The dependency-proxy (`DEP_PROXY_BASE`) entrypoint contract is left in place, a distinct
  package-manager-proxy concern honored only when that variable is set by other means.
