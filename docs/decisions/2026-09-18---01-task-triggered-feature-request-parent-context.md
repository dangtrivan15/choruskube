# Deterministic parent (Story/Epic) context reaches the drafting agent through `feature_request`, not new run-input plumbing

## Status

current

## Context

Starting a Feature Development run from a roadmap Task composed `feature_request` from
only the Task's own title and description. The Task's parent Story and grandparent Epic
carry the higher-level intent behind the work, but that context reached the agent only if
it happened to discover and fetch it — inconsistent, and easy to skip under time pressure.

Two questions needed answering: where does the parent content enter the pipeline, and how
does an agent get pointed at the live roadmap for anything the snapshot misses.

## Decision

**Fold parent Story/Epic content into the existing `feature_request` run input at the
task-start path, instead of adding new prompt variables or widening the runtime task
context every node's system prompt carries.**

- `DefaultTaskService.startCore` resolves the Task's parent Story and grandparent Epic
  (via the already-injected `StoryRepository`/`EpicRepository` and `findStoryOrThrow`/
  `findEpicOrThrow`) and composes `feature_request` through a `composeFeatureRequest`
  helper: the Task stays the lead section (`## <task title>` + description), followed by a
  labelled `## Parent context` section with a `### Story: <title>` and `### Epic: <title>`
  subsection each carrying description (and, for the Epic, motivation). A null/blank
  field is omitted outright — never a literal `null` or an empty header — so a Task whose
  ancestry has no body composes exactly what it did before this change.
- Two alternatives were rejected. Adding new prompt variables (`{run.parent_story}`,
  `{run.parent_epic}`) would need an input-schema change and a template edit. Expanding
  the runtime task-context object so every node's system prompt carries parent bodies
  would span the api-server DTO, the orchestrator, and the worker for a payload only the
  drafting node's template (`{run.feature_request}`) actually reads. Both cost more than a
  one-file api-server change for a benefit that, in the chosen design, the produced spec
  artifact already carries forward to downstream nodes.
- The composition is a **point-in-time snapshot**: edits to the Story/Epic after the run
  starts do not reach an already-composed `feature_request`, matching how `feature_request`
  already behaved before this change.

**The complementary discovery guidance for reaching *live* roadmap state ships in the
agent system prompt, not the prompt-template database.** The Feature Development template
is immutable/versioned — editing it forces a version bump and reseed, and reaches only the
node whose template references the changed text. The entrypoint's existing "Triggering
Task" system-prompt block (`agent-images/claude-code/entrypoint.sh`, emitted only when
`TASK_ID` is set) instead gained explicit language: treat the injected parent summaries as
authoritative starting context, and call `get-roadmap-graph` — which needs no flags for a
task-triggered run; the server resolves the run's Epic when `--epic-id` is omitted — for
the live ticket, dependencies/blockers, and sibling Tasks. This guidance now reaches every
AI node of a task-triggered run and rolls out with the agent image rather than through
template reseed.

## Consequences

- A future field that needs to reach *every* node of a task-triggered run (not just the
  drafting node) belongs in the entrypoint's task-context narration, the same seam this
  guidance used — not a `feature_request`-shaped concatenation, which only the drafting
  node's template consumes.
- `feature_request` can now be considerably larger than a bare Task description (a
  Story/Epic body folded in). No length cap exists anywhere in the composition or
  rendering path; the practical ceiling remains the OS argument limit at agent launch. A
  future truncate/summarize step, if one is added, belongs in `composeFeatureRequest`.
- Non-title Epic/Story metadata (priority, milestone, target dates) is still not injected
  deterministically — only titles, descriptions, and the Epic's motivation are. It remains
  reachable through `get-roadmap-graph` discovery; making it deterministic too is a
  separate, later change to `composeFeatureRequest`, not an extension of this one.
