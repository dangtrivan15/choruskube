# The control plane decides; the data plane holds credentials and makes the calls that need them

**Status:** current; its listing of issue filing among the control-plane's remaining GitHub calls is superseded, for gates whose plan declares writesRoadmap, by [2026-10-15---01-roadmap-issues-filed-on-the-worker](2026-10-15---01-roadmap-issues-filed-on-the-worker.md); its listing of issue closing is superseded by [2026-10-16---01-task-issue-close-on-the-worker](2026-10-16---01-task-issue-close-on-the-worker.md); its claim that an unset `CREDENTIAL_SOURCE` defaults to `control-plane`, and that the open-source stack stays on `control-plane` until its remaining GitHub calls move, are superseded by [2026-10-17---01-worker-declares-credential-source-at-registration](2026-10-17---01-worker-declares-credential-source-at-registration.md); its listing of credential health checks among the control plane's remaining GitHub calls is superseded, for credentials it holds for a Worker that holds none, and its known gap that no check fails a new control-plane GitHub call is closed, by [2026-10-24---01-control-plane-github-calls-are-allowlisted](2026-10-24---01-control-plane-github-calls-are-allowlisted.md); its known gap that `prepare` still sends credentials to every Worker, and fails an AI node when the API server holds no Claude token, is closed by [2026-10-28---01-prepare-attaches-agent-credentials-through-a-seam](2026-10-28---01-prepare-attaches-agent-credentials-through-a-seam.md), except that this server still returns its configured Claude token to every Worker, which a `local` Worker now ignores, until its stack holds credentials on the Worker

## Context

[2026-10-11---02](2026-10-11---02-async-gate-finalize-on-the-worker.md) moved a gate's GitHub
re-check and merges onto the Worker. Its reason was one line of context: the control plane
decides; the data plane holds credentials and compute. That line is a rule for every change that
touches a credential, not a fact about gates, and no entry stated it as one. The same entry names
the Worker's credential interface `CredentialProvider`; in the code it is `credential.Provider`
in `worker/credential`, and it supplies the agent's Claude token and registry login as well as
GitHub tokens.

The rule exists for a Worker that runs on infrastructure someone other than the deployment owner
controls ([2026-09-06---01](2026-09-06---01-worker-scoped-internal-credential.md)). Such a Worker
must be able to keep its tenant's credentials to itself, and must never be handed a credential
the deployment holds for anyone else.

## Decision

**The control plane decides; the data plane holds credentials and makes the calls that need
them.** The API server owns state and decides what happens next. Every call to GitHub, to Claude
or to a registry is made by the Worker or the agent it launches, with one exception. For a Worker
that holds no credentials (`control-plane`, below), the control plane brokers them, and that
includes minting the Worker's GitHub token.

**No new control-plane GitHub call.** A change that needs GitHub adds a Worker step, as gate
finalize and [2026-10-13---01](2026-10-13---01-branch-cleanup-on-the-worker.md)'s branch cleanup
did; it does not add a call to the API server. Apart from the token mint that brokering needs,
the API server's remaining GitHub calls are the ones still to move — issue filing, issue closing,
and any credential health check a deployment runs — plus the legacy routes each of those two
moves left behind for workflow histories recorded before it: the synchronous gate path, and
branch cleanup's own compare/delete. That list only shrinks.

**A Worker declares one of two credential modes with `CREDENTIAL_SOURCE`.**
- *`control-plane`*: the Worker holds no GitHub, Claude or registry credential of its own. It
  asks the API server for each run's GitHub token and launches the agent with the Claude token
  and registry login `prepare` returns. The API server resolves each per organization, which a
  Worker serving more than one tenant needs. When the credential is a GitHub App, the API server
  mints that token. That mint is the one GitHub call the control plane keeps, because brokering
  is what the mode is.
- *`local`*: the Worker holds one set of credentials — GitHub, the Claude token, the registry
  login — in `CREDENTIALS_DIR` or its own environment, and mints GitHub App tokens itself. The
  control plane never sees them.

**`local` is for a Fleet that serves one tenant.** A `local` Worker has no notion of a tenant:
every run it picks up gets the same credentials, so on a Worker serving more than one tenant one
tenant's run would act with another's credentials. A Worker that serves more than one tenant must
refuse `local` at startup, not run with it.

**The mode is declared, never detected, on both sides.** The Worker takes its mode from
`CREDENTIAL_SOURCE` alone and fails startup on a value it does not know. The API server takes a
Worker's mode from what the Worker declares, never from what it asks for or which files it has.
A fallback in either direction hides a misconfiguration: a Worker meant to hold its own
credentials would quietly be handed the deployment's.

**`prepare` sends no credential to a Worker that holds its own.** A Worker that declared `local`
gets no Claude token and no registry login from `prepare`, and no fallback to a credential held
for another organization applies to it; it holds every credential its runs need. This is per
Worker, not per credential: `prepare` does not fill in what a `local` Worker lacks. A deployment
may withhold more than this, never less.

## Supersedes

- From [2026-09-05---01](2026-09-05---01-worker-owns-tenant-agnostic-executor.md), the API server
  resolving every Worker's credentials in `prepare`, for a Worker that holds its own. For a
  `control-plane` Worker it still does.

## Consequences

- **Already true:** the Worker reads its mode from `CREDENTIAL_SOURCE`, treats an unset value as
  `control-plane` and fails startup on any other value; a `local` Worker mints its own GitHub App
  tokens; a gate's GitHub steps, a completed run's branch-cleanup compare/delete, and the agent's
  GitHub token all come from the Worker's `credential.Provider`.
- **Known gap: `prepare` still sends credentials to every Worker.** The API server is not yet told
  a Worker's mode, so it returns the Claude token and registry login to a `local` Worker too, and
  a `local` Worker that holds neither uses them. It also fails an AI node when the API server has
  no Claude token, even for a Worker that holds its own.
- **Known gap: no check fails a new control-plane GitHub call.** Until one names the remaining call
  sites, this entry is what a reviewer holds such a change to.
- **The open-source stack stays on `control-plane`** until its remaining GitHub calls move, so its
  credentials are configured in one place, as the 2026-10-11---02 entry set out.
- **Out of scope:** narrowing minted tokens to a run's repositories, and which credentials a
  `control-plane` Worker on a Fleet its deployment does not run may receive.

## Rejected

- **Detect the mode,** for example `local` when `CREDENTIALS_DIR` holds files, or "holds its own"
  when a Worker never asks for a token. A wrong guess either sends a credential where it must not
  go or hides one that is missing.
- **Fill in, per credential, what a `local` Worker lacks.** It is a fallback under another name:
  a Worker missing a credential quietly runs on one the control plane holds, which can be one the
  deployment holds for someone else.
- **Keep a control-plane GitHub call where it is simpler,** such as work outside a run. Each one is
  a place a tenant's credential has to reach the control plane, and the exceptions accumulate.
- **Move the token mint to the Worker as well.** A Worker serving more than one tenant would have
  to hold every tenant's GitHub App key.
- **Allow `local` on a Worker serving more than one tenant, with a folder per tenant.** That Worker
  would hold every tenant's credentials at once, and one compromise would expose them all.
