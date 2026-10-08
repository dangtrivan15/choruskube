# Deferral is decided at the spec, proposed by every stage, and read from the newest copy

## Status

current — amends two clauses of
[2026-09-30---01](2026-09-30---01-roadmap-extension-proposals.md) without superseding it.

## Context

Feature Development agents turned too many gaps into roadmap Tasks. Autopilot starts every
unblocked backlog Task, so each deferral became a run that deferred more. Conditional items
("revisit when a threshold is reached") became Tasks that started immediately and invented work.
The Implement node, the proposal's only author, could also add items no spec or reviewer had
seen; the only human who saw them was the Final Approval reviewer, approving them in the same
action that merges the pull requests.

## Decision

- **Out of scope is not deferred.** A caveat is Accepted, which creates nothing, unless it belongs
  to the change's purpose and is too complicated for the run. It must need its own design
  decision, change a contract or component the change does not otherwise touch, or need an
  investigation before it can be designed. Size alone never defers work.
- **A condition becomes a dependency only when it is work.** If a run could do X, the follow-up is
  proposed blocked by X's Task, and X is proposed too when it is not on the roadmap yet. If X is an
  event, the item is Accepted. Tasks have no hold state, so a Task standing for an event would start
  at once, and a blocker nobody does would never clear.
- **Every stage may propose, at falling rates.**
  - Draft Spec proposes follow-ups in the spec, Spec Review checks them, and the human approves
    them with the spec.
  - Implement writes them up as the proposal document and may add rare, marked items.
  - Code Review rebuilds the document from Implement's latest copy each iteration, re-applies its
    recorded edits, and re-installs it.
  - Every Task opens with an origin line, so the Final Approval reviewer looks only at the
    additions.
- **Roadmap items are still created only at Final Approval.** Creating them at spec approval would
  let Autopilot start a follow-up before the change merges, and would leave orphans when a run is
  abandoned.
- **The gate reads the newest copy.** When several nodes declare the proposal artifact, the
  resolver takes the most recently completed producer that actually wrote it. A missing file falls
  back to an older producer; a malformed newest copy does not. This amends 2026-09-30---01's "only
  the Implement node authors the proposal artifact".
- **The proposal is the only home for deferred work.** Each proposed Task gets the backend-filed
  issue that closes with it, and agents in this workflow file no deferral issues themselves. This
  amends 2026-09-30---01's "agent-initiated issue creation remains available as a fallback".

## Consequences

- A run abandoned before Final Approval loses its proposals, spec-approved ones included. The spec
  artifact still records them.
- If the Supervisor routes a new Implement iteration past Code Review, the gate takes Implement's
  newer copy and drops the older review edits.
- Runs take on work they used to defer, so Implement runs grow.
- The dispositions are prompt rules, enforced by two agent reviewers and two human gates, not by
  the server.
