# A self-hosted server holds no credential; its Worker holds them all

**Status:** current; its broker route being served in multi-tenant mode, `ControlPlaneGitHubCallsTest` asserting no call site into `GitHubAppService`, the consequence that a Worker outside compose is given `CREDENTIAL_SOURCE=local`, the consequence that a `control-plane` Worker registers and then fails its first node, and the claim that `github.api.url` stays in core, are superseded by [2026-11-13---01-the-open-source-worker-is-local-only](2026-11-13---01-the-open-source-worker-is-local-only.md)

## Context

[2026-10-13---02](2026-10-13---02-data-plane-credential-rule.md) put credentials with the data
plane: the component that makes a call holds what the call needs. The open-source stack still did
the opposite. Its operators configured the Claude token, the GitHub PAT or the App key as
environment on the api-server, and the compose Worker ran `control-plane`. The api-server minted
App tokens for it over the broker route `GET /worker/runs/{runId}/github-token`, and returned its
Claude token from prepare to every Worker that registered. That left three things that
[2026-10-28---01](2026-10-28---01-prepare-attaches-agent-credentials-through-a-seam.md) and
[2026-10-24---01](2026-10-24---01-control-plane-github-calls-are-allowlisted.md) had called out
as interim:

- an env-reading resolver behind prepare's agent-credential seam;
- the one entry on the control-plane GitHub-call allowlist, the env resolver's App mint;
- a self-hosted Worker that holds nothing.

Nothing in e2e ran `local`, the mode every self-hosted install is meant to run, so a regression on
that path shipped untested.

Brokering exists for one deployment shape only: a hosted control plane's shared Fleet, whose
Workers serve every tenant and hold nothing. A self-hosted install has nothing to broker.

## Decision

**A self-hosted api-server reads, mints and returns no credential.** The env-reading GitHub and
Claude resolvers are deleted, together with the `github.app.*` and `anthropic.api.url` properties
only they read. With no agent-credential bean registered, prepare falls back to the seam's empty
default and returns no Claude token and no registry login. The run's token-scope route
(`/worker/runs/{runId}/github-token-scope`) stays: it returns repositories and permissions, holds
no secret, and a `local` Worker uses it to narrow its own App mint and to check a PAT's reach.

**The broker route is served only in multi-tenant mode.** `WorkerGitHubTokenController` carries
`@ConditionalOnProperty(name = "auth.enabled", havingValue = "true")`, the same switch that already
keeps single-tenant credential beans out of a multi-tenant deployment. A single-tenant server
answers the route with 404. A multi-tenant deployment keeps the route at its path with its 401,
403 and 503 behaviour unchanged.

**The compose stack's Worker runs `local` from a mounted credentials folder, with no credential
environment.** `scripts/setup.sh` writes `.secrets/credentials/` in the Worker's layout:
`claude-oauth-token`, `github-pat`, and `github-app/{app-id,installation-id,private-key.pem}`.
Directories are `700` and files `600`. The folder is emptied before each write, so switching
between a PAT and an App leaves nothing stale behind. `docker-compose.yaml` mounts it read-only at
the Worker's default `CREDENTIALS_DIR`, `/etc/choruskube/credentials`, and `scripts/up.sh` creates
it user-owned before compose. The api-server gets no credential environment and no secrets mount.
The e2e stack does the same with a committed test-only folder, `e2e/credentials/`, holding a dummy
PAT and Claude token. The Worker binary keeps its own environment fallback for deployments outside
compose.

**One precedence, the Worker's: folder before environment, then PAT before App.** A GitHub
credential is the first of the folder's `github-pat`, the folder's `github-app/`, `GITHUB_PAT`, and
the `GITHUB_APP_*` variables. Every document that said the App wins is corrected.

**Core's control-plane GitHub-call allowlist is empty.** `ControlPlaneGitHubCallsTest` asserts that
the api-server has no call site into `GitHubAppService` at all. The guard still fails on any new
one, and on any production source outside the guarded client that names the GitHub API.

## Supersedes

- In [2026-10-13---02](2026-10-13---02-data-plane-credential-rule.md), the remaining exceptions its
  status line kept open are closed: "this server still returns its configured Claude token to every
  Worker", and "only the broker's token mint and checks of credentials it holds".
- In [2026-10-17---01](2026-10-17---01-worker-declares-credential-source-at-registration.md), its
  statement that the open-source stack still runs `control-plane`.
- In [2026-10-28---01](2026-10-28---01-prepare-attaches-agent-credentials-through-a-seam.md), its
  single-tenant interim resolver that attached this server's configured token.
- In [2026-10-24---01](2026-10-24---01-control-plane-github-calls-are-allowlisted.md), its one
  broker entry. The list is now empty.

## Consequences

- **An existing Docker install's credentials stop being read until setup runs again.** The
  credential lines in `.env` are ignored after the upgrade. The operator re-runs `setup.sh`, which
  points at the stale lines without editing `.env`, and then deletes them.
- **A self-hosted install outside compose moves its credentials by hand.** The operator removes
  the Claude token, PAT and App variables from the api-server and gives them to the Worker, in its
  credentials folder (for example a read-only Kubernetes Secret mount, see
  [worker/README.md](../../worker/README.md#executor-configuration)) or its environment, with
  `CREDENTIAL_SOURCE=local`.
- **A rotated credential needs no restart.** The Worker reads the folder on every use, so a file
  replaced on disk takes effect on the next run.
- **A `control-plane` Worker against an open-source server registers, then fails its first node.**
  Nothing attaches a Claude token and there is no token route, so the failure is loud. The Worker's
  own error names its mode.
- **E2e now exercises `local`.** Every gate, branch-cleanup and issue flow runs through the
  Worker's own credential, against the same GitHub stub. The core-only `e2eApiServerMadeNoStubCall`
  step fails the suite if WireMock journaled any request from the api-server's JDK client. It also
  fails if the journal holds no request from the Worker, so it cannot pass vacuously. The step
  stays out of the shared Playwright specs, since a suite that composes them may run a server
  that legitimately calls the stub.
- **`github.api.url` stays in core** while the GitHub App client that reads it does. With the
  allowlist empty, nothing in a single-tenant server calls that client.

## Rejected

- **Passing the Worker the same credential variables alongside the folder**, so an existing `.env`
  keeps working. That puts one credential in two places, and a shell variable can take effect
  unseen. Every install re-runs setup soon anyway once its Worker token is generated per install,
  so the migration benefit would be temporary while the second place would be permanent.
- **Routing the credentials to the Worker as environment only.** That is the smallest change, but
  it keeps secrets in the container's inspectable environment and needs a restart for every
  rotation. It also gives up the layout a Kubernetes Secret mount already uses.
- **Keeping the broker route in both modes and refusing when no resolver exists.** That avoids
  conditional wiring, but leaves a runtime branch whose only job is to fail.
- **Moving the broker route out of core in this change.** One move instead of two steps, but a
  multi-tenant deployment that still serves the route would then have to change in lockstep with
  core, where today it needs nothing from this change. It would also take the move from the
  separate change that owns it.
