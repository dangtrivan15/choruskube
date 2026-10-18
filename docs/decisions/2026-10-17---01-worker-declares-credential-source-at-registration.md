# A Worker declares its credential source at every registration call

**Status:** current

## Context

[2026-10-13---02](2026-10-13---02-data-plane-credential-rule.md) named two credential modes a
Worker runs in — `control-plane`, which asks the API server to broker its credentials, and
`local`, which holds its own — and recorded as a known gap that the API server was never told
which one a given Worker had chosen: `prepare` returned a Claude token and registry login to
every Worker alike, including one that held its own and had no use for them.

Closing that gap needs the Worker to say which mode it is in. It does not need the API server to
remember that answer anywhere: which mode is correct is already a fact about the Fleet the
Worker is joining, not something to learn from whichever Worker happens to register.

## Decision

**The Worker reports `CREDENTIAL_SOURCE` as a required field, `credentialSource`, on every
registration call.** A missing, blank, or unrecognized value is refused with 400 and nothing is
recorded. There is no default for an absent value, because this is a hard cutover rather than a
gradual rollout: a Worker binary built before this field existed cannot register until it is
upgraded to send it.

**The field is a consistency check, not state to store.** Which mode a Fleet's Workers must run
is fixed by the Fleet, as the shapes below set out, not chosen at registration. A server whose
Fleets come in more than one shape checks the declared value against the Fleet at registration,
through its own `WorkerRegistrar`, and refuses a mismatch before it issues anything. A
single-Fleet server like this one has one shape and no tenant notion to check against, so it
accepts the field once validated and keeps no record of what any particular Worker declared.

**The static configuration path cannot declare a mode, and no longer needs to.** It never calls
the registration endpoint, so there was never a call on which to report anything; it used to
refuse `local` for exactly that reason. With no record anywhere for a missing declaration to
leave stale, that refusal protected nothing, so the static path now accepts `CREDENTIAL_SOURCE`
unchanged, `local` included.

**Which side holds credentials is a function of the Fleet, never a per-Worker choice.** There
are four deployment shapes, and the shape — not a declaration — fixes the answer:
- **Self-hosted on Docker:** the Worker holds its own GitHub, Claude and registry credentials
  (`local`).
- **Self-hosted on Kubernetes:** the same (`local`).
- **A hosted control plane's shared Fleet**, which the hosted deployment owns and runs for every
  tenant: the control plane holds each tenant's credentials and brokers them to its Workers per
  run (`control-plane`). This is the only shape where an API server holds credentials.
- **A tenant's own Fleet on a hosted control plane:** the tenant runs the Worker, and the Worker
  holds that tenant's credentials (`local`).

One hosted deployment therefore has Fleets in both modes. This server is single-Fleet and
self-hosted, so its end state is `local`. The open-source stack still runs `control-plane`
against this server's own configured credentials until one change switches it to `local`; that
switch is tracked on the roadmap.

**Moving credential work to the Worker is a hard cutoff.** Each change deletes the path it
replaces: there are no tolerant defaults and no period in which both stay reachable. A path kept
only so that runs recorded before a change can finish is deleted once those runs have finished
or been terminated, not kept until nothing happens to use it. Database migrations are the one
exemption, because a schema already applied cannot be rewritten in place.

**`control-plane` mode's broker — the route a Worker calls to ask for a run's GitHub token, and
the Worker-side provider that calls it — serves only the hosted shared Fleet.** A self-hosted
server has no tenant credentials to broker, so the broker does not belong in this server long
term. Moving it out is tracked on the roadmap rather than done in this change, which only settles
the registration field.

## Rejected

- **A per-request header instead of a registration field.** Every caller would need to send it,
  and a caller that omitted it would silently get some default — the same kind of hidden
  fallback [2026-10-13---02](2026-10-13---02-data-plane-credential-rule.md) already rejected for
  credentials themselves.
- **Runtime detection** — for example, inferring `local` from which files exist, or inferring
  `control-plane` from which calls a Worker happens to make. A wrong guess is indistinguishable
  from a correct one until it already matters.
- **Persisting the declaration against the shared registration token, on a single-Fleet
  server.** A single Fleet has exactly one correct mode, already fixed by who runs it; a record
  keyed by token would only ever restate that fact, one write behind whatever a Worker last sent,
  and would not survive an API server restart until every Worker re-registered.
- **Configuring the mode from the API side instead of asking the Worker.** That is a second
  declaration of a fact only the Worker actually knows about itself, and the two can disagree.
- **Treating an absent value as `control-plane` by default.** A tolerant default is the right
  shape for a gradual rollout; this field's rollout is a hard cutover, so a missing value is a
  refusal, not a guess.
- **Keeping the static path's refusal of `local` as a defensive leftover.** It existed only to
  protect a per-token record from going stale; deleting the record and keeping the refusal would
  leave a rule with no longer any reason behind it.
