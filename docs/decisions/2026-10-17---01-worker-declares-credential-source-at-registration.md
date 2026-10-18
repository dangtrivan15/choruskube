# A Worker declares its credential source at every registration call

**Status:** current

## Context

[2026-10-13---02](2026-10-13---02-data-plane-credential-rule.md) named two credential modes a
Worker runs in — `control-plane`, which asks the API server to broker its credentials, and
`local`, which holds its own — and recorded as a known gap that the API server was never told
which one a given Worker had chosen: `prepare` returned a Claude token and registry login to
every Worker alike, including one that held its own and had no use for them.

Closing that gap needs the Worker to say which mode it is in. It does not need the API server to
remember that answer anywhere: which mode is correct is already a fact about the deployment —
who runs the Fleet the Worker is joining — not something to learn from whichever Worker happens
to register.

## Decision

**The Worker reports `CREDENTIAL_SOURCE` as a required field, `credentialSource`, on every
registration call.** A missing, blank, or unrecognized value is refused with 400 and nothing is
recorded. There is no default for an absent value, because this is a hard cutover rather than a
gradual rollout: a Worker binary built before this field existed cannot register until it is
upgraded to send it.

**The field is a consistency check, not state to store.** Which mode a Fleet's Workers must run
is fixed by the deployment the Fleet belongs to, not chosen by registration. A deployment whose
Fleets can belong to more than one tenant checks the declared value against what it already
knows about the Fleet and refuses a mismatch; one that serves a single Fleet it does not own on
anyone's behalf has nothing to check the declaration against and nothing to remember it in. This
server is the second kind: it has exactly one Fleet, so it accepts the field once validated and
keeps no record of what any particular Worker declared.

**The static configuration path cannot declare a mode, and no longer needs to.** It never calls
the registration endpoint, so there was never a call on which to report anything; it used to
refuse `local` for exactly that reason. With no record anywhere for a missing declaration to
leave stale, that refusal protected nothing, so the static path now accepts `CREDENTIAL_SOURCE`
unchanged, `local` included.

**Which side holds credentials is a function of the deployment, never a per-Worker choice.** A
Worker's own credential mode is always one of the two this field names, but a deployment is one
of two shapes, and the shape — not a declaration — is what fixes the answer:
- **A self-hosted deployment**, with no tenant but its own operator, where the Worker is expected
  to hold its own GitHub, Claude and registry credentials (`local`). This server, and every
  single-Fleet deployment like it, is this shape; it has no tenant notion to check a declaration
  against.
- **A deployment that mints and holds credentials for Fleets it does not own**, brokering on
  behalf of a tenant that runs no infrastructure of its own (`control-plane`). This is the only
  shape where the server legitimately holds a tenant's credentials, and the only shape with
  something to check a Worker's declaration against: whether the Fleet it is registering against
  is one the server owns credentials for.

There are no tolerant defaults in either shape, and no gradual transition between them: a change
to which shape a Fleet is deletes the behavior it replaces rather than keeping both reachable.
Database migrations are the one exemption, because a schema already applied cannot be rewritten
in place.

**`control-plane` mode's own broker — the route a Worker calls to ask for a run's GitHub token,
and the Worker-side provider that calls it — belongs only to the second shape above.** This
server runs single-tenant and is the first shape, so that broker does not belong here long term;
moving or removing it is tracked on the roadmap rather than done in this change, which only
settles the registration field itself.

**The answer is bound to the presented credential, never to a per-request header**, for any
deployment that does keep a record: a later `/worker/**` call is answered by looking up the
credential it presents, the same one `WorkerAuthorizer` already authenticates, so every caller
inside one Worker process gets one consistent answer with nothing to configure per call.

**A deployment that mints a credential per Worker, and therefore can check and persist a
declaration, supplies its own implementation of the seams this server already declares** —
`WorkerRegistrar` here, and whatever the Fleet-aware equivalent needs — the same way other seams
in this server are replaced by a bean. This entry only settles the single-Fleet path and the
field's contract; it does not add the checking seam itself.

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
