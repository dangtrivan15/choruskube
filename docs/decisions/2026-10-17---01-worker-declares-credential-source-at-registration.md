# A Worker declares its credential source at every registration call

**Status:** current

## Context

[2026-10-13---02](2026-10-13---02-data-plane-credential-rule.md) named two credential modes a
Worker runs in — `control-plane`, which asks the API server to broker its credentials, and
`local`, which holds its own — and recorded as a known gap that the API server was never told
which one a given Worker had chosen: `prepare` returned a Claude token and registry login to
every Worker alike, including one that held its own and had no use for them.

Closing that gap needs the Worker to say which mode it is in, and needs the API server to keep
that answer somewhere it can look it up later, by the credential a `/worker/**` call presents
rather than by anything the call itself carries.

## Decision

**The Worker reports `CREDENTIAL_SOURCE` as a required field, `credentialSource`, on every
registration call.** A missing, blank, or unrecognized value is refused with 400 and nothing is
recorded. There is no default for an absent value, because this is a hard cutover rather than a
gradual rollout: a Worker binary built before this field existed cannot register until it is
upgraded to send it.

**The answer is bound to the presented credential, never to a per-request header.** A later
`/worker/**` call is answered by looking up the credential it presents, the same one
`WorkerAuthorizer` already authenticates. Every caller inside one Worker process therefore gets
one consistent answer, with nothing to configure per call and nothing a caller could omit.

**A single-Fleet server persists the declaration per shared registration token, not per
Worker.** This server has exactly one Fleet and one registration token, so every Worker that
registers with it shares one credential and must declare the same mode as every other Worker on
that token. The latest declaration wins, and a declaration that disagrees with the token's
current record is logged rather than rejected — Workers restart and redeploy independently, and
the newest one is making the live claim.

**The static configuration path cannot declare a mode, so it refuses `local`.** It never calls
the registration endpoint, so there is no call on which to report anything; running `local`
there would leave the API server's record permanently wrong. The static path only ever runs
`control-plane`, implicitly, since it has nothing else to claim.

**A deployment that mints a credential per Worker records the mode against that credential and
supplies its own resolver**, replacing the single-Fleet fallback the same way other seams in
this server are replaced by a bean. This entry only settles the single-Fleet path and the seam
the override replaces.

## Rejected

- **A per-request header instead of a registration field.** Every caller would need to send it,
  and a caller that omitted it would silently get some default — the same kind of hidden
  fallback [2026-10-13---02](2026-10-13---02-data-plane-credential-rule.md) already rejected for
  credentials themselves.
- **Runtime detection** — for example, inferring `local` from which files exist, or inferring
  `control-plane` from which calls a Worker happens to make. A wrong guess is indistinguishable
  from a correct one until it already matters.
- **Keeping the answer only in the API server's memory.** It would not survive an API server
  restart: until every Worker re-registered, a `local` Worker would be answered `control-plane`.
- **Configuring the mode from the API side instead of asking the Worker.** That is a second
  declaration of a fact only the Worker actually knows about itself, and the two can disagree.
- **Treating an absent value as `control-plane` by default.** A tolerant default is the right
  shape for a gradual rollout; this field's rollout is a hard cutover, so a missing value is a
  refusal, not a guess.
