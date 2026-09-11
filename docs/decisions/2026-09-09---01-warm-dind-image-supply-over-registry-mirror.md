# Agent workloads get Docker images from a pre-warmed (optionally custom) DinD sidecar, not an injected registry mirror

## Status

current

## Context

A node that runs Docker gets a DinD sidecar. That raises an image-supply question: how does the
DinD daemon get the base images and build layers a run needs?

The `WorkloadRegistryMirrorResolver` seam (`com.choruskube.core.executor`) exists so a deployment
that runs a pull-through registry mirror / build cache can inject its endpoints (`REGISTRY_MIRROR`,
`BUILD_CACHE_REGISTRY`, `INSECURE_REGISTRIES`) into the DinD sidecar during
`WorkloadService.prepareWorkload`. Where such a mirror lives — a per-tenant proxy, a shared mirror,
or none — is a deployment-specific provisioning detail this module does not resolve itself; the
default `NoRegistryMirrorResolver` resolves none.

Standing up and operating that mirror is real infrastructure: a registry per deployment or per
tenant, credentials, firewall rules, and storage. And it only helps on a *cold* DinD — the first
pull of an image the daemon has never seen.

## Decision

**A pre-warmed DinD sidecar image is the primary image-supply mechanism, optionally overridable per
Software Project via a `dind_image` field. The registry-mirror seam stays but defaults to none.**

- The warm DinD image (`agent-images/choruskube-dind`) bakes the images and layers a run typically
  needs, so the daemon is warm on first use with no pull-through mirror in front of it.
- A Software Project may pin a custom DinD image: `dind_image` on `git_repo` (migration `V24`) and
  on `repo_group` (migration `V25`), carried on `RuntimeRequirements` and surfaced to the Worker as
  `PrepareWorkloadResponse.dindImage`; the single-tenant seeder reads it from
  `CHORUSKUBE_REPO_DIND_IMAGE`. `null` selects the platform default DinD image.
- `WorkloadService` still consults the mirror seam (only on the Docker path, gated on
  `enableDocker`), so a deployment that *wants* an injected mirror registers a
  `WorkloadRegistryMirrorResolver` bean and everything downstream works unchanged. But the
  supported default path is warm / custom DinD — the platform does not require any deployment or
  tenant to run mirror infrastructure.

## Alternatives considered

- **Require a registry mirror as the image-supply path.** Rejected: it forces every deployment to
  run and credential registry infrastructure to get warm pulls. A warm DinD image delivers the same
  cold-pull win as an artifact that ships through the image pipeline the deployment already has.
- **Bake the images into the agent image, with no separate DinD image.** Rejected: the agent
  container and the DinD daemon are separate processes with separate image stores. The images a run
  *builds against* live in the DinD, so the warm layer must be a DinD image (default or per-project
  custom), not the agent image.

## Consequences

- `dind_image` is `null` by default → the platform default DinD image. `repo_group.dind_image` is a
  `JOINED`-table column, so a group's custom image is its own, not inherited from a `git_repo` row.
- Image supply no longer depends on mirror infrastructure existing anywhere; a deployment adds a
  mirror resolver only if it wants one.
- An image absent from the (warm or custom) DinD falls back to pulling from its origin registry —
  warmth is an optimization, not a correctness dependency.
- A specific closed deployment retired its own per-tenant mirror provisioning on the strength of
  this mechanism; that teardown is recorded in that deployment's own (closed) decisions log, not
  here.
