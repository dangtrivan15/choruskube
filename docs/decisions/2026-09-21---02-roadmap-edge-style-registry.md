# One shared color+dash-pattern registry for roadmap dependency edges

## Status

current

## Context

A roadmap dependency kind's color and dash pattern were declared independently at up
to four call sites: the edge component's inline stroke (`var(--color-status-*)`), the
arrowhead marker color at `RoadmapGraph.tsx` (all three dependency kinds), the
arrowhead marker color at `RoadmapCandidateGraph.tsx` (a second, independent
`resolveStatusColors()["--status-warning"]` literal for the blocking kind, distinct
from `RoadmapGraph.tsx`'s own), and the legend's hardcoded `border-dashed`/`border-dotted`
approximation. The marker duplication existed because React Flow sets a marker's
`color` as a literal SVG attribute rather than an inline style, so it cannot resolve
`var(--status-*)` the way a stroke can — every marker call site had to re-resolve the
hex itself. Nothing enforced agreement across these copies: the roadmap graph's
Epic-tier dependency edge draws dash-dot, but the legend showed it as plain dashed.

## Decision

`src/lib/roadmapEdgeStyles.ts` exports `ROADMAP_EDGE_STYLES`, one entry per dependency
kind (`hierarchy`, `dependency`, `epicDependency`, `crossEpic`) shaped
`{ token, dashArray, label }`. `token` is deliberately the raw `--status-*` (or
`--muted-foreground`) custom-property name, not the Tailwind `--color-*` alias, so the
one field drives every consumer: `var(<token>)` for a stroke or the legend's inline-SVG
swatch, and `resolveStatusColors()[<token>]` (`src/lib/dagLayout.ts`) for a literal
marker color. Every edge component (`RoadmapDependencyEdge`, `RoadmapEpicDependencyEdge`,
`RoadmapCrossEpicEdge`, `RoadmapGraphEdge`), both marker-construction call sites
(`RoadmapGraph.tsx`, `RoadmapCandidateGraph.tsx`), and `RoadmapGraphLegend.tsx` read
their kind's entry instead of carrying their own literal.

## Alternatives considered

- **Only test that the legend and edge share the same color token, leave the dash
  pattern hardcoded.** Cheaper, but leaves the actual dash-dot/dashed mismatch in
  place with no structural guarantee it can't recur.
- **Route the stroke and legend through the registry but leave the marker literals
  hardcoded.** Removes one duplication but not the one that caused this: an editor
  changing a kind's color would still need to remember two more call sites (both
  markers) by hand, and `RoadmapCandidateGraph.tsx`'s independent
  `resolveStatusColors()["--status-warning"]` copy would remain a silent second source
  of truth for the same kind.
- **Delete the legend.** Removes the only user-facing key describing the roadmap
  graph's edge languages.

## Consequences

A dependency kind's color or dash pattern now has exactly one place to change; the
edge stroke, both marker call sites, and the legend swatch all move together. The
raw-token convention (`token` names a `--status-*`/`--muted-foreground` custom
property, never the `--color-*` alias) is required by `resolveStatusColors()`, which
keys on the raw name — a future entry using the alias would silently fail to resolve
at the marker call sites while still working for CSS strokes, so this constraint is
easy to violate without a test catching it (`roadmapEdgeStyles.test.ts` pins each
kind's exact token to guard it).
