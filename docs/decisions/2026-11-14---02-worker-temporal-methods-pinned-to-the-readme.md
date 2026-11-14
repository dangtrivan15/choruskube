# The Worker's Temporal method set is observed by recording, declared in the README, and checked for a credential

**Status:** current

## Context

The Worker reaches Temporal through two SDK surfaces: the Fleet supervisor's per-Fleet client and
activity worker, and the activity completer, which completed and heartbeated launched nodes
through whatever client its lookup returned -- the full SDK client, not a narrowed one. Nothing
recorded which gRPC methods either surface actually sent, so a deployment fronting Temporal with
a method allowlist had no document to build that allowlist from, and no way to learn that an SDK
upgrade or a new completer call had started sending a method outside it until a remote Worker
broke in production.

## Decision

**The set is observed by recording real SDK traffic at an in-process fake, not by reading
source.** A test starts a gRPC server inside the test process that implements enough of
`WorkflowService` for the SDK to run every outcome the Worker drives -- dial, start, poll,
synchronous activity success/failure/cancellation/heartbeat, by-id completion and heartbeat, and
stop -- and records the full method name and metadata of every call it receives, on any service,
including ones it refuses. Recording traffic, rather than scanning source, is what catches an SDK
bump that starts sending a method no code change named.

**The completer reaches Temporal only through a narrow interface enumerating exactly the calls it
is allowed to make.** The Fleet supervisor still holds the full SDK client where the SDK's own
constructors need it, but hands the completer only the narrow interface. A method added to that
interface has to be exercised by a test scenario, checked by reflection, or the test fails --
closing the gap where a new completer call would otherwise compile silently.

**The Worker's README table is the one declared copy of the set, and the test parses it.** The
test reads the method column of the README's proxy table and compares that set with what it
recorded, rather than keeping a second list inside the test. Publishing the set only where a
proxy operator already has to look, and parsing that same publication, is what keeps editing the
set a visible, reviewed change instead of a second place to remember.

**The same recording also checks that every call carries the Worker's credential.** A proxy that
authenticates a Worker's token depends on every call -- including the first, at dial -- carrying
it as a bearer. The fake already sees each call's metadata, so asserting the bearer is present
costs nothing beyond the method-set comparison already being made.

## Rejected

- **Scanning production source for SDK call sites.** It catches a new call added in code, but is
  blind to what the SDK itself sends on the wire -- the change an SDK version bump makes, and the
  likelier way a deployment's allowlist breaks silently for a remote Worker.
- **Running the test against a real Temporal dev server.** It answers with the real server's
  behaviour, but downloads a server binary on every test run and still only reveals the methods
  the test happens to drive -- no more than the in-process fake reveals, for a much larger cost.
- **Keeping the pinned list inside the test, with the README maintained by hand (or re-checked
  against the test's list).** Either leaves two places to edit when the set changes, or leaves the
  published table free to drift from what is actually pinned with nothing failing. A proxy
  operator already has to read the README to build an allowlist; parsing it directly is what
  makes that reading the one copy a change to the set must touch.

This entry deliberately names no Temporal method and no deployment: the set itself belongs to the
README table alone, and this record is only the choice of how that table stays true.
