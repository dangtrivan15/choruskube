# The open-source Worker is local-only; the control-plane credential provider moves out

**Status:** current

## Context

[2026-11-10---01](2026-11-10---01-self-hosted-server-holds-no-credential.md) made core hold no
credential and serve the broker route only in multi-tenant mode. That left the broker's remaining
pieces — the GitHub App client (`GitHubAppService`), its resolver interface
(`GitHubCredentialResolver`), the broker controller (`WorkerGitHubTokenController`), and the
`github.api.url` property — still in core, reachable only from a mode core itself never boots
into. It also left the Worker's `control-plane` credential provider (`credential.NewControlPlane`)
in the open-source `worker` Go module, built from a `CREDENTIAL_SOURCE` env var this binary never
needs to set to anything but `local`, and a single-Fleet registrar that accepted a Worker
declaring `control-plane` and then left it to fail its first node for want of a credential.

Brokering a GitHub credential is a hosted control plane's own shared Fleet's concern: only that
control plane resolves a credential per organization and serves many tenants from Workers that
hold nothing. Core itself is single-tenant and holds no credential to broker, so it neither needs
to serve that route nor to ask for one.

## Decision

**The broker moves out of core entirely.** `WorkerGitHubTokenController`, `GitHubCredentialResolver`,
`GitHubAppService`, and the exceptions only the App client raised
(`GitHubTokenMintException`, `GitHubRateLimitHints`, `GitHubRateLimited`) are deleted from core,
along with the `github.api.url` property and the `nimbus-jose-jwt` dependency pin only the App
client used. The run-scoped token-scope route (`/worker/runs/{runId}/github-token-scope`) stays:
it carries no secret and the local credential plane still uses it.

**The single-Fleet registrar refuses a Worker that declares it wants a broker.**
`SingleFleetWorkerRegistrar` now checks `request.credentialSource()` and answers
`control-plane` with HTTP 409 — core holds no credential to broker, so there is nothing useful a
registration could do with that declaration besides let the Worker discover the gap later, one
node at a time.

**The Worker library takes a required `CredentialPlane`.** `worker.Config.CredentialPlane` is a
declared `Source` string plus a `New` constructor; `Validate` rejects a zero value. The seam lets
a binary embedding the `worker` Go module supply its own credential source — a hosted control
plane's own Worker build is the one exception this module still names in its own doc comments,
even though its implementation lives elsewhere now. `worker.LocalCredentialPlane` is the plane
every open-source binary passes; `credential.NewControlPlane` and its file move with the
control-plane implementation to wherever that build lives, since the only caller left for it was
`cmd/worker`.

**The open-source binary refuses to start if `CREDENTIAL_SOURCE` is set to anything.** This
binary always holds its own credentials now, so the variable has nothing left to select between;
silently ignoring it would hide a stale deployment setting instead of surfacing it.

**The guard fixture stays in core's test fixtures and gains a source-only check.**
`ControlPlaneGitHubCallGuard` keeps its existing call-graph form — scanning for calls into a named
guarded class, checked against an allowlist — unchanged, for a downstream build that holds a
GitHub client and needs to allowlist its own call sites into it. It gains
`requireNoGitHubApiNamed(sourceRoot)`, a source-only entry point with no guarded class and no
allowlist: it fails if any `.java` file under the root names the GitHub API location, with nothing
exempt. `ControlPlaneGitHubCallsTest` now runs only this form over core's `src/main/java`; the
call-graph form is proven only by the guard's own test, `ControlPlaneGitHubCallGuardTest`.

## Supersedes

- [2026-11-10---01](2026-11-10---01-self-hosted-server-holds-no-credential.md): the broker route
  being served in multi-tenant mode, `ControlPlaneGitHubCallsTest` asserting no call site into
  `GitHubAppService`, the consequence that a Worker outside compose is given
  `CREDENTIAL_SOURCE=local`, the consequence that a `control-plane` Worker against core registers
  and then fails its first node (now refused with 409 at registration), and the claim that
  `github.api.url` stays in core.
- [2026-10-13---02](2026-10-13---02-data-plane-credential-rule.md): its statements that a Worker
  declares its mode with `CREDENTIAL_SOURCE` and takes it from that setting alone. A Worker's mode
  is now the `CredentialPlane` its binary passes; the open-source binary passes only `local`.
- [2026-10-17---01](2026-10-17---01-worker-declares-credential-source-at-registration.md): the
  Worker's `CREDENTIAL_SOURCE` mode setting, including the static path accepting it, and the
  single-Fleet server accepting a `control-plane` declaration.
- [2026-10-24---01](2026-10-24---01-control-plane-github-calls-are-allowlisted.md): core guarding
  `GitHubAppService`. Core now runs the source-only check instead.
- [2026-10-11---02](2026-10-11---02-async-gate-finalize-on-the-worker.md): the ControlPlane
  provider existing in the open-source Worker.

## Consequences

- **A self-hosted Worker operator removes `CREDENTIAL_SOURCE` from their environment on
  upgrade.** The binary now refuses to start while it is set, to any value.
- **Core's production source can never again name the GitHub API without failing the build.**
  `ControlPlaneGitHubCallsTest` has no allowlist to add an exception to; a new GitHub-calling need
  belongs in a Worker step.
- **A downstream build with its own GitHub client keeps the call-graph guard available** by
  depending on this fixture and building its own `scanning(...).guarding(...)` call, same as core
  did before this change.

## Rejected

- **Moving `ControlPlaneGitHubCallGuard` out of core's test fixtures and keeping a second,
  standalone scanner in core for the source-only check.** That duplicates the line-matching logic
  this guard already has (`scanBypass`) rather than removing it, and leaves two places that could
  each be wrong about the same three spellings of the GitHub API location. Extending the one
  fixture with a second entry point keeps the matcher singular while still letting a downstream
  build that holds a GitHub client depend on the same fixture for its own call-graph check.
