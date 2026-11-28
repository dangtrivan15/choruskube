# The compose stacks are a trusted single-operator environment, not a security boundary

**Status:** current

## Context

Each compose stack in this repository (`docker-compose.yaml` and `docker-compose.e2e.yaml`) runs
its services and the agent containers its Worker launches on one Docker network. From there an
agent container can open a connection to every service in the stack: the database with the
stack's development credentials, Temporal with no authentication, and every port the object store
opens, its unauthenticated filer included. The stacks also publish those ports on all of the
host's interfaces. A deployment that runs agents it does not trust holds them back with its own
controls, such as network policy or an edge in front of the store. No compose stack has those
controls or exercises them.

A proposal to confine agents to the object store's S3 port inside the compose stacks closed one
of those paths and left the rest open. Its checks proved its own forwarder, not any control a
deployment relies on.

## Decision

**The compose stacks are a trusted environment run by one operator, and nothing in them is a
security boundary between the containers they run.** A container in a stack may reach any other
service on the stack's network. That is expected behaviour, not a gap.

- **Do not add isolation inside a compose stack whose only purpose is to stop one of its
  containers reaching another**, whether loopback binds, forwarders, split networks or egress
  rules. The same goes for an e2e spec or smoke step that asserts such isolation.
- **Prove a security property where it is enforced.** The application's own checks (internal
  auth, run scope, presign admission) are proven by tests of that code, which the stacks run as
  production does. A component's behaviour, such as the object store's presigned-request
  contract, is proven by a test against the pinned image. A deployment's network controls are
  proven by that deployment's own checks.
- **A stack may still shape its network to mirror where a deployment's components run**, when
  that is what makes an e2e check fail on a wrong result. That is test fidelity, not a boundary.
- **The host is assumed to sit on a network its operator trusts.** The stacks publish their
  ports on all interfaces, and [SECURITY.md](../../SECURITY.md#threat-model) states that posture.

**`web-ui/e2e/specs/presigned-request-contract.spec.ts` is removed.** It repeated the store
contract test's refusals from inside an agent container. Its assertions are a subset of
`PresignedRequestStoreContractTest`, which runs against the same pinned image through the real
presign path. The one hop it added, the agent's path to the store across the stack network, is a
compose path that no deployment's agents take. This supersedes the paragraph of
[2026-11-25---01-object-store-acts-only-on-the-signed-object](2026-11-25---01-object-store-acts-only-on-the-signed-object.md)
that names the spec.

### Rejected

- **Confine agents to the object store's S3 port in the compose stacks**, using a TCP forwarder
  that owns the store's network namespace, the store bound to loopback, and an e2e spec that
  reads the store's socket table. It kept agents off the store's unauthenticated ports while the
  database and Temporal stayed open to them on the same network. It also added a second container
  per stack whose restart strands the store.
- **Give agents a network that reaches only the services they call.** Every service an agent
  calls would have to join it, which means a second membership list kept by hand in every stack.
  It would make the stacks look like a boundary while still exercising no control a deployment
  uses.
- **Bind the published ports to loopback.** It would keep the host's LAN off the database and
  Temporal, but it would stop the stack being reached from another machine. It also hardens the
  host's exposure rather than the stack. The operator's network is trusted instead.
- **Keep the e2e presigned-request spec.** It does use a URL the deployed api-server minted, but
  `PresignedRequestStoreContractTest` drives the same real presign path, and the extra network hop
  proves nothing a deployment relies on.

## Consequences

- An agent container in a compose stack can read and change other runs' artifacts through the
  store's filer, and connect to the database and Temporal. People who do not trust each other
  must not share a stack; they need a deployment with its own controls.
- A report that a container in a stack reaches another service in it is answered as expected
  behaviour under [SECURITY.md](../../SECURITY.md#threat-model).
