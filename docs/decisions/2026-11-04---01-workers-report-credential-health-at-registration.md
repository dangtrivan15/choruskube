# Workers report the health of the credentials they hold, on every registration

**Status:** current

## Context

A Worker holding its own credentials — the GitHub App or PAT, the Claude token, and the registry
login — was never checked before a step used them. An expired PAT or a revoked Claude token
therefore surfaced as a node failing mid-run, costing a whole run on a problem that was knowable
in advance. A control plane serving more than one Fleet is forbidden from checking a Worker's own
credentials itself: that Worker's whole point is that nothing outside it ever calls out with, or
even reaches, those credentials. The only party able to check them is the Worker.

## Decision

**The Worker checks each credential it holds, itself, and reports the latest result on every
registration call — a call that already repeats about once a minute and that the control plane
already requires.** The control-plane source — the Worker holding none — reports every kind as
`NOT_HELD`, with no outbound call. The static configuration path never registers, so it reports
nothing; only a Worker that registers has anything to report.

- **What is checked, and how.** An App credential is checked by looking up its installation with
  the App's own key and inspecting whether it is suspended — minting nothing. A PAT is checked by
  the lightest authenticated read GitHub offers (`GET /user`), classified by its status and
  `X-OAuth-Scopes` header the same way the control plane's own PAT check already is. A Claude
  token is checked by `GET /v1/models`; a registry login by a Docker Registry v2 auth ping,
  following a Bearer challenge to its token realm with one scoped retry for a credential
  authorized only for specific repositories. None of these ever logs or returns any part of the
  credential itself.
- **When.** At startup, before the first registration call, and then on a fixed five-minute
  interval without a run. Every read carries its own bounded timeout, so the startup check — and
  the registration it precedes — waits at most that long. A timeout or any other network failure
  is reported `UNREACHABLE`, never `EXPIRED`: the credential might be fine, the check just
  couldn't reach the service to find out. A failing check never fails registration or a run, and a
  credential rotated in place is picked up by the next check.
- **The report's statuses.** `VALID`, `EXPIRED`, `INSUFFICIENT_PERMISSIONS` and `UNREACHABLE` are
  the four a control plane's own credential checks already store. `NOT_HELD` is the one new value,
  for a kind a Worker simply does not hold.
- **The API server requires the report.** Registration is extended with a required field, refused
  with no default the same way the credential-source field already is: a Worker binary built
  before this existed cannot register until it is upgraded, and an older API server that has not
  been upgraded yet simply never asks for the field, so the two can roll out minutes apart in
  either order.
- **A single-Fleet server keeps nothing.** It passes the report through a registrar seam that
  already keeps nothing of the credential-source field either — a server with exactly one Fleet
  has nowhere useful to put a per-Worker report, and no second Worker to compare it against.

## Why the App check reads the installation rather than mints

A control plane's own App-credential check mints a metadata-only token and discards it, because
it already has a narrowed-mint code path to reuse and no cheaper authenticated call available to
it. The Worker's own check has no such step to reuse — minting at all would be new machinery added
solely for a health check — and reading the installation directly gives it something a mint
response does not: whether the installation is suspended, which a suspended installation's own
mint attempt would eventually surface as a rejection anyway, just one network round-trip later and
without naming the actual cause.

## Why repository reachability is checked on a run's first token, not here

Only a run names the repositories a token must reach; a credential-health check that holds no run
has nothing to check reachability against. A narrowed App mint already fails on its own when a
run's repository falls outside the installation's selection, so that credential kind needs no
separate check. A PAT has no narrowing step to fail at, so the Worker reads each of the run's
repositories once, the first time a run resolves its token, and remembers the run as verified so a
later node of the same run does not repeat the read. A failed verification is never cached, so a
retried run is checked again rather than staying refused on a transient failure.
