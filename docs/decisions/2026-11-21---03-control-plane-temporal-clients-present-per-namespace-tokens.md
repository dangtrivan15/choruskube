# The api-server and orchestrator present a per-namespace Temporal token when a deployment supplies one

**Status:** current

## Context

Core's api-server and orchestrator call Temporal on one shared connection per process, with no
per-namespace credential: every call goes out as whichever identity the transport itself grants.
A deployment whose Temporal checks per-namespace tokens needs both components to present one, but
the open-source default — nothing configured — must keep behaving exactly as it does today, with
no credential on any call.

## Decision

**A deployment supplies a token source as an optional Spring bean; with none, every namespace is
"none," unchanged from today.** The seam reports one of three outcomes per namespace: a bearer
token with its expiry, "this deployment presents no credential," or a failure. A failure is always
"unavailable," never "none" — collapsing the two would let a transient failure of the source look
identical to a deployment that was never going to present a credential, and a caller would then
send an uncredentialed call where a credentialed deployment required one.

**The api-server attaches each call's token by the namespace the request itself names, on the one
connection every namespace already shares.** A gRPC client interceptor reads the outgoing
request's `namespace` field once the message is known, looks up that namespace's token, and
either attaches it or — if the deployment issues tokens and this namespace's token is
unavailable — fails the call before it reaches the transport. A request naming no namespace
carries no header.

**With a token source configured, the api-server refuses to boot unless internal-route
authentication is enforcing with an orchestrator secret configured.** Tokens travel to the
orchestrator over the api-server's internal namespace-roster route, and only that combination
keeps that route the orchestrator's alone — with no secret configured, the internal filter lets
every caller through regardless of mode.

### How does the orchestrator receive its tokens?

| Option | Gives us | Costs us |
|---|---|---|
| ✅ In the namespace roster it already fetches | no new route, and namespaces and tokens always arrive together | roster responses now carry secrets |
| A dedicated per-namespace token route | roster stays credential-free | a new route and a second refresh loop |

Decided by: the roster's refresh already outpaces a token's own lifetime, so a second route and
loop would buy nothing a dedicated endpoint needs. The roster response is bounded so a hanging
token source cannot stall the whole roster past the orchestrator's own timeout for it: any
namespace the source cannot answer within that bound is reported unavailable instead of blocking
every other namespace in the same response.

### How does the api-server pick each call's token?

| Option | Gives us | Costs us |
|---|---|---|
| ✅ By the namespace each request names, on the one shared connection | every caller covered with no change to the beans around it | each call's header waits until its request is seen |
| One connection per namespace | trivial header supply | every existing caller of the shared connection has to be rewired |

Decided by: a second connection-per-namespace scheme would force every direct caller of the
shared connection to move in step with this change, where reading the namespace off the request
itself keeps that surface untouched.

### Does a token change the orchestrator's transport?

The Temporal Go SDK switches a connection to TLS whenever a credential is set, unless told
otherwise.

| Option | Gives us | Costs us |
|---|---|---|
| ✅ No: the orchestrator always sets the transport explicitly | a deployment can start issuing tokens under a running orchestrator safely | no path to a TLS-only Temporal, as today |
| Mirror the SDK default: TLS turns on with a credential unless disabled | one fewer explicit setting | an orchestrator breaks the moment tokens first arrive unless configured beforehand |

Decided by: a token arrives from the api-server at runtime, not at process start, so letting its
mere presence flip the transport would break a live orchestrator the first time a token source
comes online.

### What does the orchestrator do before it holds a namespace's token?

Temporal refuses a worker's startup namespace check when no token is presented, so a namespace
cannot be served before its token arrives wherever one is required.

| Option | Gives us | Costs us |
|---|---|---|
| ✅ Attempt at once, retry every unserved namespace each roster tick, and exit only when a refusal is not explained by a missing token | open-source startup unchanged, and misconfiguration still exits loudly | a token-issuing deployment's namespace waits for its first roster before it is served |
| Fetch the roster before serving anything | one startup path for every deployment | the configured namespace — which every deployment has — would wait on the api-server even where no token is ever needed |
| Keep exiting on the first refusal, as before this change | no new exit logic | a token-issuing deployment would crash-loop at every single start, before its first roster ever lands |

Decided by: the open-source default deliberately serves its configured namespace without waiting
on the api-server at all; retrying instead of exiting is what lets a deployment that does need a
token keep that same immediate attempt, while reserving a fatal exit for a refusal that already
holds a token or that a roster has explicitly said needs none — both of which Temporal should not
have refused, so exiting then stays loud rather than silently retrying forever.

## Rejected

See each Technical Decision's table above for the alternative weighed against the option taken.

This entry names no token issuer, no deployment and no namespace: the seam, and every decision
here, are generic by construction — a closed deployment's own choice of issuer is its own,
unrecorded here.
