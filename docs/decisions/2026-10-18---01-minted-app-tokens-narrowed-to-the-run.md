# A minted GitHub App token is narrowed to the run's repositories and a fixed permission list

**Status:** current

## Context

A GitHub App installation token minted for a run — whether the control plane brokers it for a
Worker that holds no credentials, or a `local` Worker mints it with its own key — has always
named only the installation. GitHub answers that with a token covering every repository the
installation can reach, with every permission the App was granted: far more than the run that
asked for it touches. [2026-10-13---02](2026-10-13---02-data-plane-credential-rule.md) named this
gap explicitly and left it out of scope: "narrowing minted tokens to a run's repositories" is
listed there as unaddressed. This entry closes that gap. A personal access token is unaffected —
GitHub has no API to narrow a PAT once issued, so a PAT-configured deployment keeps whatever
access its owner gave it, documented rather than changed.

## Decision

**The control plane decides a run's token scope; whoever holds the App key mints with exactly
that scope.** The scope is the run's GitHub-hosted repositories (as `owner/name` pairs) plus one
fixed run permission list, declared once in the API server and never hardcoded by a Worker. When
the control plane brokers a mint, it applies the scope itself. When a `local` Worker holds its own
App key, it first fetches the scope from a new Worker-facing route that carries no credential and
makes no GitHub call, then mints with it. Computing the scope stays a decision; minting stays with
whoever holds the key — this is the existing data-plane credential rule applied to one more thing
the control plane now decides for every mint, not a new call site on either side.

**The narrowing rule, implemented once per language:** before minting, look up the installation —
its account and granted permissions — caching the answer for ten minutes, keyed by the whole
credential (App id, installation id and private key) rather than by installation id alone, since
two different organizations can otherwise name the same installation id. Keep only the scope's
repositories owned by that account, reduced to their bare name, de-duplicated and sorted. Cap each
permission at the lower of what the run's work needs and what the installation grants, omitting
one the installation lacks.

**An empty scope is refused before any GitHub call.** No repository left, or no permission left
after capping, is never sent as a mint: GitHub reads a missing or empty `repositories` or
`permissions` field as "every repository" or "every permission", so an empty result can only be
refused, never minted.

**A PAT is passed through unchanged.** The PAT branch on every resolver never touches the scope
resolver or the narrowing rule — there is nothing to narrow, and the setup documentation says so.

## Consequences

- **A new Worker-facing route, `GET /worker/runs/{runId}/github-token-scope`,** authorized exactly
  like the existing run-token route, answers a run's scope with no credential and no GitHub call.
- **Branch cleanup's planning step, and roadmap issue filing's choice of issue targets, skip a
  repository GitHub does not host,** using the same GitHub-only URL parser the scope resolver
  uses. Either Worker step fails and retries as a whole on a refused token, so it must never be
  handed a repository the run's scope cannot cover; a skipped issue is recorded as unfiled.
- **The App credential health check mints a metadata-only token instead of an installation-wide
  one,** through a new credential-check method on the GitHub App client — the unscoped mint method
  it used before is removed, so every caller now mints with a scope.
- **A repository outside the installation's repository selection now fails its run's whole mint,**
  where before only operations on that one repository failed, because GitHub answers a narrowed
  mint naming an inaccessible repository with a blanket 422 rather than minting for the rest. The
  mint error names every requested repository and the installation account, since GitHub's own 422
  does not say which repository is the problem.
- **This adds no new control-plane GitHub call site**, which keeps the data-plane credential rule
  intact: the installation lookup runs inside the GitHub App client, on the way to a mint the API
  server already performed; the scope route makes no GitHub call at all; and the credential check
  replaces a mint the health probe already made rather than adding one.

## Rejected

- **Requesting the fixed permission list as-is, with no cap against what the installation
  grants.** The whole mint would then fail for any installation granted less than the full list —
  the common case, since an App is often granted only what merging a pull request needs.
- **Inheriting the installation's own permissions onto the run's repositories.** That would leave
  administration, secrets and webhook access on the token wherever the installation holds them,
  undoing the reason to narrow at all.
- **Minting first and revoking the token if its returned repository list looks wrong.** That
  briefly holds a token for the wrong repository and turns revocation into a second failure path,
  for no benefit over looking the installation up before minting.
- **Trusting a bare repository name without first learning which account owns it.** A repository
  under another account with the same name would then be included by accident.
- **Returning a metadata-only token for a run with no repository the installation can reach**,
  rather than refusing outright. Metadata-only still reads every repository's metadata in the
  installation — a scope wider than the run's, which is exactly what this change exists to avoid.
- **Keeping the unscoped mint method callable, deprecated, for one release.** A public unscoped
  method is the one a future caller reaches for by habit; removing it in the same change is what
  makes the old, wider mint actually unreachable.
- **Having the credential health check read the installation rather than mint a metadata-only
  token.** A suspended installation still answers that lookup with success, so the check would lose
  the 401/403 status it classifies a revoked credential by, and gain nothing a discarded,
  process-local token did not already prove.
- **A Worker that holds its own App key deriving a run's repositories from what it already holds** —
  its launch configuration and step inputs — instead of asking the control plane. That would be a
  second definition of "the run's repositories" alongside the one the control plane already
  resolves for every node, and it is lost across a Worker restart regardless.
