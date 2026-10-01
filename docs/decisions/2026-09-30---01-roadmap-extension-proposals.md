# Deferred work reaches the roadmap only as a human-approved proposal, materialized at an approval gate

## Status

current

## Context

An agent could park deferred work as "a roadmap item" by calling the direct
roadmap-write routes (`create-proposal`, `create-story`, `create-task`, …) itself.
In practice this created bare, unreviewed Epics with no Story or Task under them —
unstartable, since nothing surfaces an Epic with no addressable leaf work. The
direct-write routes also identified the calling node execution's run only by the id
in the URL, with no check that the caller actually belonged to that run.

## Decision

**Deferred work becomes roadmap items only through a proposal document that a human
approves at a workflow's approval gate — never through a direct write.**

- The agent writes a proposal artifact (`roadmap_candidates.json`: a nested
  Epic → Story → Task tree plus key-based dependencies) and validates it with a new
  CLI, `propose-roadmap`, against a new agent-facing validate route. Only a passing
  document is installed as the node's output artifact. This reuses the gate's
  existing artifact-resolution and materialize-on-approve mechanism rather than
  adding a new addressable direct-write endpoint or an incrementally-built
  server-held draft, so every agent-originated roadmap item is visible and
  explicitly approved before it exists.
- Any Epic, Story or Task entry may carry an `existingId`, turning it into an
  **anchor**: nothing is created for it, but it can hold new children and carry a
  dependency key like any new item. This is the one mechanism for "extend what
  already exists" and "depend on an existing item" — the nesting itself proves
  hierarchy consistency, since the server checks that an anchored Story really
  belongs to the anchored Epic above it.
- Materialization behavior is a gate-config `materialize` mode, not an inferred
  property of the workflow. `roadmap_candidates` (the Roadmap Provisioner's
  existing mode — wholly new Epic trees only) is unchanged; a new
  `roadmap_extension` mode is Feature Development's. A template's graph
  validation rejects an unknown mode, more than one roadmap gate per graph, or a
  roadmap gate that doesn't declare the proposal artifact as an input.
- In `roadmap_extension` mode, a task-triggered run may add new Stories/Tasks
  under its own Epic, or introduce one or more wholly new top-level Epics of its
  own — but an anchor to any *other* existing Epic is never allowed as a parent
  for new children, so a task-triggered run can originate a new initiative but
  can never attach follow-up work to someone else's tree. A manually-started run
  (no triggering Task) may propose either new Epics or extensions of any Epic in
  the run's project. Milestones are not allowed in this mode.
- The addressable invariant — every Epic needs at least one Story, every Story at
  least one Task — is now enforced server-side for **every** roadmap gate, not
  only the new mode, turning what was previously a prompt-only convention into a
  guarantee checked both when the agent submits a proposal and when the reviewer
  approves it.
- Direct roadmap-write routes are refused (403, pointing at `propose-roadmap`) for
  any run whose workflow graph declares a roadmap gate. Every agent-facing roadmap
  route — reads included — now also verifies the calling node execution belongs to
  the run named in the URL, closing a caller/run-pairing gap that previously
  existed on all of them regardless of this change.
- Only the Implement node authors the proposal artifact for a Feature Development
  run, and it is also declared as an optional input from Implement's own prior
  iteration so a retry can re-submit it. A single producer keeps "latest iteration
  wins" resolution unambiguous with the gate's existing artifact discovery.
- At approve time the server re-validates the (possibly reviewer-edited) document
  against the gate's structural rules before creating anything: a violation (for
  example, an anchor that no longer resolves, or a Story with no Task) is
  rejected with the reasons and the gate stays open for another edit. Individual
  per-item write failures during materialization stay best-effort, as they were
  before this change.
- A Task created through `roadmap_extension` materialization also gets a
  backend-filed GitHub issue that closes automatically once the Task reaches
  `done`. Agent-initiated issue creation remains available as a fallback for
  deferred work that must survive even if the run producing the proposal is
  itself abandoned before reaching its gate.

## Consequences

- A workflow with no roadmap gate is unaffected: its agents keep direct roadmap
  writes and can still create a bare Epic, since nothing here changes behavior
  where there is no gate to route the change through.
- A run that fails or is abandoned before its approval gate is reached loses any
  proposal it wrote. Filing a GitHub issue directly stays the right tool for
  deferred work that must survive regardless of the run's own outcome.
- The Roadmap Provisioner's own gate already declares a roadmap-materializing
  mode, so its runs are refused direct writes as soon as this change is deployed
  — enforcement of what its prompt already asked for, not a new behavior for it.
- The task-triggered "Triggering Task" narration that points agents at
  `get-roadmap-graph` as the live-roadmap discovery seam already exists per
  [2026-09-18---01](2026-09-18---01-task-triggered-feature-request-parent-context.md)
  (which put that guidance in the agent entrypoint rather than the frozen prompt
  template); this change only adds the ids a proposal anchors to, and a
  `propose-roadmap` pointer, to that same block.
- An agent cannot be stopped or even detected server-side if it files a GitHub
  issue directly instead of preferring `propose-roadmap` for work that belonged
  in scope — the agent's GitHub calls use a token fetched directly from
  `fetch-github-token` and never transit the api-server the way a roadmap write
  does. The prompt's preference for `propose-roadmap` is soft, not enforced.
