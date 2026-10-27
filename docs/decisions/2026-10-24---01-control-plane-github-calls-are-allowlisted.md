# Control-plane GitHub calls are allowlisted; the list only shrinks

**Status:** current

## Context

[2026-10-13---02](2026-10-13---02-data-plane-credential-rule.md) forbade a new control-plane
GitHub call, and its Consequences named the gap plainly: until a check names the remaining call
sites, that entry is what a reviewer holds such a change to. A reviewer reading a diff cannot
easily tell that a new method call reaches GitHub, and the inventory of what remains existed only
as prose spread across several decision records — prose that drifts as calls move.

## Decision

**A test fails the build on three conditions:** a call into a GitHub-calling method from a site
its allowlist does not name; a listed site whose call no longer exists; or production source
outside a guarded class that names the GitHub API location. The first closes the gap above; the
second keeps the list a true inventory rather than a set of pre-approved slots; the third closes
the side door a fresh HTTP client could use to reach GitHub unguarded.

**Guarded methods are derived, never hand-listed.** A guarded class's method is guarded when its
own calls reach the network or an already-guarded method, iterated to a fixpoint. A new network
method is covered without a list edit; a storage-only method on a mixed service stays free of the
allowlist, so its callers need no entry.

**An entry is `caller class#method -> guarded class#method`, with a reason that cites the decision
entry keeping it.** There are two kinds of entry. A **broker** entry stays for as long as the code
base brokers GitHub credentials for a Worker that holds none (`control-plane`, per the entry
above): minting that Worker's token, and checking the health of a credential the control plane
holds for it. Every other entry is a call still to move, and may only leave the list — adding one
reverses the rule above and needs a decision entry saying so.

**Each code base checks only its own production classes with its own allowlist.** No list names a
call site its own repo cannot see.

**The guard is a shared test fixture**, so a code base built on this API server can apply it to
its own production classes without a second implementation to keep in sync.

## Supersedes

- The part of [2026-10-13---02](2026-10-13---02-data-plane-credential-rule.md) that lists "any
  credential health check a deployment runs" among the control plane's remaining calls still to
  move. A health check of a credential the control plane holds for a Worker that holds none is a
  broker call, like the token mint it already treated that way, and now stays for the same reason.

## Consequences

- [2026-10-13---02](2026-10-13---02-data-plane-credential-rule.md)'s known gap — no check failed a
  new control-plane GitHub call — is closed.
- A change that moves a call off the control plane deletes its allowlist entry in the same change;
  leaving it fails the build on the next run.
- **Known gap: callers of an allowlisted method are not seen.** A new caller of a token resolver,
  say, reaches GitHub without adding a direct call site the guard would flag.
- **Known gap: a second HTTP client is unguarded.** A guarded class's method that sends through an
  HTTP client the guard does not recognise is not guarded, and the guard must learn that client
  before such a method's own callers are checked.
- Non-Java control-plane code is not scanned; the guard only ever ran over this API server's own
  bytecode.

## Rejected

- **Line-number keys.** Any edit above a call would break the build on a line that moved, not one
  that changed.
- **A hand-kept list of guarded method names.** It fails open on exactly the change the guard
  exists to catch: a new network method nobody remembered to list.
- **Tolerating a stale entry.** A dead entry is a pre-approved slot for the next call of the same
  name.
- **Source-text search for callers.** It misses a same-package call, a method reference, and a
  call made through an alias.
- **One test over every code base's classes at once.** This repo would carry no guard of its own,
  and its call sites would be listed only in the allowlist of a code base built on it.
