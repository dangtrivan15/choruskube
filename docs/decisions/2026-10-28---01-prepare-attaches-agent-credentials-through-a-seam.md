# Launch preparation attaches agent credentials through one resolver seam, defaulting to none

**Status:** current

## Context

[2026-10-13---02](2026-10-13---02-data-plane-credential-rule.md) named a known gap: launch
preparation resolved the agent's Claude token and registry login itself and sent both to every
Worker, regardless of which credentials that Worker actually held. A `local` Worker — one that
holds its own credentials and never uses the control plane's — received them anyway and simply
ignored them, since nothing told the API server which mode a Worker had declared. The same code
path also had the opposite failure: it refused to prepare any AI node at all when the server held
no Claude token, even for a Worker that would never touch it. Neither behavior served a deployment
that brokers credentials for only part of where it runs — that case needs a per-run decision,
which the API server had nowhere to put.

## Decision

**Launch preparation asks one resolver seam for the agent credentials it attaches, and resolves
none itself.** The seam is `WorkloadAgentCredentialResolver.resolve(runId, runsClaude)`, called
once per launch, returning a Claude token, a registry login, both, or neither. Preparation never
inspects a credential's shape beyond what the seam hands back, and never throws for a missing
token on its own account.

**The default attaches nothing,** via a Null Object (`NoWorkloadAgentCredentialResolver`), because
a server that brokers no credential for anyone has nothing to attach, and that answer needs no
configuration. A deployment that does broker replaces the default by registering its own resolver;
two registered at once fails startup rather than silently picking one.

**One seam covers both credentials**, not a Claude-token seam and a registry seam each re-deciding
the same thing. A deployment that brokers for only some of where a run executes (for example, only
the Fleet nobody owns) decides that once per run, from where the run executes, and both
credentials follow the same answer. Splitting the decision across two seams would let the two
drift on exactly the question that matters: whether this run gets anything at all.

**This server attaches its own configured token while its stack's Worker runs in brokered mode,
and nothing when the token is unset.** The single-tenant core has no notion of a Worker's declared
mode yet, so it cannot tell a `local` Worker apart from a brokered one; until it can, it keeps
attaching the token it is configured with to every launch, exactly as it always has. A `local`
Worker now ignores what it receives regardless, so nothing crosses a boundary this single-operator
deployment does not already share. This interim resolver is deleted once the single-tenant stack
holds credentials on the Worker instead — a change already tracked on its own roadmap entry, not
part of this one.

**A Worker never blends credential sources.** Its provider receives what preparation attached and
whether the launching node runs Claude, and decides alone: a Worker holding its own credentials
(`local`) ignores what was attached and uses only its own; a brokered Worker (`control-plane`)
uses only what was attached. Neither mode falls back to the other's source for part of a
credential.

**The Claude-token check moves to the Worker, right before launch.** The API server no longer
knows, or needs to know, whether a Worker holds a token; only the Worker that is about to launch
knows where its credentials come from. A Claude-running node with no token to launch with fails
non-retryably, with a message naming exactly where the token belongs — the credentials folder and
environment variable for `local`, or "the control plane attached none" for `control-plane`.

**A registry login stays optional on every path.** None means an anonymous pull; a partial login
(some but not all of host/username/password) is still an error, since that can only be a
misconfiguration, never a deliberate choice.

**The credential pre-flight runs after the run is placed, in the same transaction.** A seam that
decides by where a run executes needs that decision already recorded when it is asked; placing
first means a refusal rolls back the run and its placement together.

**One shared "runs Claude" rule.** An executor type runs Claude unless it is `script` or `human`.
The snapshot builder owns the rule — `GraphSnapshotBuilder.runsClaude(executorType)` plus
`effectiveExecutorType`, which applies a template node's config override the same way the
snapshot already does — so launch preparation, the orchestrator's snapshot and any credential
check all read one definition instead of three that could disagree after a template override.

## Consequences

- **A `local` Worker no longer launches on what prepare attached.** It uses only its own Claude
  token and registry login, so a credential meant to stay on one Fleet can no longer reach another
  by accident.
- **Prepare no longer fails an AI node just because the server holds no Claude token.** Whether
  that is refused is now a question for whatever resolver is registered, or for the Worker at
  launch — not for preparation itself.
- **This server returns no registry login from its interim resolver**, and never has: the public
  agent image is pulled anonymously, and nothing here changes that.
- **Until the single-tenant stack holds credentials on the Worker, it still sends its configured
  Claude token to every Worker, a `local` one included**, which now ignores it. This is the one
  piece of the known gap this entry does not close, and is called out rather than left to look
  closed.
- **A missing Claude token now fails at the Worker, not at prepare.** For this server's AI nodes,
  that moves the failure one step later than before, with a message that names where the token
  should have come from.

## Rejected

- **Keeping separate Claude-token and registry seams**, each re-deciding whether this run's
  credentials are brokered. The decision is per run, not per credential, and two seams can drift.
- **Keeping preparation's throw when no Claude token is configured.** A missing token is routine
  for a deployment that brokers nothing, or for a Worker that holds its own; preparation itself has
  no business refusing on either server's behalf.
- **Requiring a registry login on every path.** The public agent image needs none, and requiring
  one would break every self-hosted install that pulls it anonymously.
- **Recomputing a run's placement inside the pre-flight instead of placing first.** That duplicates
  the placement rule in a second place, where it can silently disagree with the one placement
  already recorded.
- **Attaching nothing from this server right now**, the strictest reading of removing the
  fallback. The roadmap orders this change before the single-tenant stack's move to Worker-held
  credentials, so its AI nodes must keep working in between.
