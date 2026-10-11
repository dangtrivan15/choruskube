# The Run detail page gets three layout tiers, URL-based node selection, and one shared bottom sheet

**Status:** current

## Context

The Run detail page (`/runs/:id`) had one side panel that showed either run info (feature
request, software project, Epic › Story › Task chain, pull requests) or the selected node's
detail — selecting a node replaced run info, and a "back" button swapped them back. That made
run info and node detail mutually exclusive, which is wrong on every viewport: a reviewer reading
a node's logs still needs to see which PR or task triggered the run.

One 768px breakpoint also split "mobile" from "desktop" layout. Between 768 and 1023px (tablet
portrait, a narrow window) a 320px docked panel next to the app's 224px sidebar left the graph
roughly 250px wide — too narrow to read.

Node selection lived in component state: a reload lost it, it could not be linked or shared, and
the page always opened with nothing selected even when the run was waiting on a human decision.
Three different pages (`RunMonitorPage.tsx`, the roadmap graph, and the roadmap timeline) each
hand-rolled their own mobile overlay — a `fixed h-[85vh]` div with no backdrop, no Escape
handling, no focus trap, and no accessible name, and whose `vh` unit extends behind the mobile
browser's address/tab bars, clipping the sheet's bottom.

Elsewhere, unbounded user-authored titles (run names, Epic/Story/Task titles, board-card titles,
graph node labels) wrapped word-by-word into tall, hard-to-scan columns, with no way to recover
the full text once it was clipped.

React Flow's built-in `fitView` fits the *entire* graph into the canvas on first render. On a
390px-wide phone that is often under half zoom, at which point 160px-wide nodes become
unreadable labels. It also never re-fits when the panel collapses or the device rotates. And the
app shell's `100vh` root is taller than the visible viewport on mobile Safari once the browser
chrome is accounted for, so the document scrolls and clips content a fixed-height layout assumed
was fully visible.

## Decision

- **Run info is a persistent summary strip, never sharing a container with node detail.**
  `RunSummary.tsx` renders three variants of one data model — `RunSummaryStrip` (tablet/desktop,
  under the run header), `RunSummaryMobileBar` (phone, a one-line bar), and `RunSummaryDetails`
  (the phone "Run info" sheet's stacked content). The side panel (`DetailPanel.tsx`) shows only
  the selected node, or an empty state (`NodeDetailEmptyState.tsx`) that also lists nodes needing
  attention or currently running.
- **Three layout tiers keyed on viewport width**, not one: phone (`< 768px`), tablet
  (`768–1023px`), desktop (`≥ 1024px`). `useMediaQuery.ts` backs both the existing mobile query
  and the new `DOCKED_PANEL_QUERY = "(min-width: 1024px)"` that gates the docked panel. The
  docked panel only pays off once the graph keeps roughly 480px beside it, which — given the
  app's 224px sidebar and the panel's existing 320px default width — holds from 1024px up.
- **Node selection lives in the URL** as `?node=<templateNodeId>`, written with history
  *replace* so it never creates a Back-button stop. On the docked (desktop) tier only, if no
  valid `node` is present when a run first loads, the page auto-selects the node needing
  attention: `lib/runFocus.ts`'s `classifyActiveNodes`/`pickFocusNode` rank an awaiting-human
  node first, then a failed node, then a running node, tying within a rank by snapshot order.
  This runs at most once per run id — a live update or a later deselect never re-triggers it.
  Phone and tablet never auto-open a sheet; instead they expose a "Review …"/"Failed: …" button
  for the same node `pickFocusNode` would have selected.
- **One shared modal `BottomSheet`** (`components/ui/BottomSheet.tsx`, built on the already-used
  Base UI Dialog) replaces all three hand-rolled overlays. It is modal with a backdrop, closes on
  Escape and outside tap, traps focus (never landing it on a text input), sizes itself with
  dynamic viewport units (`dvh`) and respects the bottom safe-area inset. Run detail's node sheet
  and "Run info" sheet, and both roadmap canvases' mobile overlays, all adopt it while keeping
  their existing test ids.
- **Long user-authored names truncate to one line**, with the full value reachable on hover or
  keyboard focus. `TruncatedText.tsx` gained a `render` prop (so the tooltip trigger can *be* a
  router `Link`) and a `tooltip` override, and is now used for the run title, the roadmap
  breadcrumb (`RoadmapBreadcrumb.tsx`), graph node labels, board-card titles, and Autopilot task
  references. Touch devices cannot hover, so wherever this truncates on a phone there is also a
  stacked, wrapping view with the full text — the "Run info" sheet's `RoadmapBreadcrumb`
  `stacked` variant is the run-detail instance of that rule.
- **The graph's initial viewport is computed by a pure function, not `fitView`.**
  `lib/dagViewport.ts`'s `computeInitialViewport` is applied by an in-canvas
  `DagViewportController.tsx`: wide screens fit the whole graph, capped at 100% zoom; a phone
  that would otherwise drop below a readable zoom instead gets a fixed readable zoom centred on
  the node needing attention (or the entry node, if none does). It re-applies on canvas resize
  until the user first pans or zooms — by gesture, wheel, or the zoom/fit control buttons — and
  again whenever a different run is shown, even one sharing a template (and therefore a layout)
  with the previous one.
- **The app shell uses `100dvh`**, and exposes a `useFullBleedMain()` escape hatch
  (`components/layout/MainLayoutContext.tsx`) that a page can call to drop the shell's default
  padding and scrolling main area in favor of its own. Run detail is the first page to use it.
- **Panel-hosted components use container queries, not viewport breakpoints.** A component like
  `DecisionButtons.tsx` renders inside a full-width approvals card, a 240–600px docked panel, and
  a phone sheet — the same component needs a different layout in each, keyed to the width it is
  actually given, which only a CSS container query (the panel/sheet body is marked `@container`)
  can express; a viewport breakpoint would disagree with reality inside a narrow panel on a wide
  screen.

## Why not the alternatives

- **Tabs inside the one side panel**, or **two docked panels** (run info and node detail side by
  side) — a tab is still the switch being complained about; two docked panels leave the graph a
  narrow strip on anything but a wide desktop.
- **A collapsible drawer over the graph** for run info — hidden by default, which defeats the
  point of making it visible.
- **Driving layout with container queries instead of viewport queries** — the *page* is the
  container, so a page-level container query reduces to a viewport query with an extra
  indirection; container queries are the right tool only once content is hosted inside something
  narrower than the viewport (the panel/sheet case above).
- **Pushing a history entry per node selection** — the Back button would walk through every node
  ever clicked, instead of leaving the page.
- **A "follow mode" that keeps re-focusing the node needing attention live** — it would yank the
  panel away from underneath a user mid-read or mid-typing-feedback.
- **Patching each of the three hand-rolled overlays in place** — three copies of the same fix,
  and a fourth sheet would be a fourth copy. **Adding a drawer library with drag-to-dismiss** —
  a new dependency for a gesture nobody asked for.
- **A native `title` attribute** for long names — not keyboard-reachable, unstyled, and delayed.
  **A two-line clamp** instead of one-line truncation — still breaks a horizontal chain like
  Epic › Story › Task and doubles row height.
- **`fitView` with a `minZoom` clamp** — readable, but still anchored to the graph's centre, so
  it can center on empty space instead of the node that matters. **Replacing the graph with a
  node list on phones** — phones are explicitly supposed to show the graph.
- **Negative margins in the page** instead of a shell opt-in — silently couples the page to the
  shell's current padding values. **Route `handle` metadata read via `useMatches`** — requires a
  data router, which the shell's existing tests (`MemoryRouter`) do not provide.
- **Measuring container width in JavaScript** instead of a CSS container query — re-implements,
  with more moving parts, something Tailwind's container-query support already does natively.

## Consequences

- The summary strip costs roughly two lines of vertical space on every run page; the feature
  request itself renders as a one-line preview by default, with the full text one click away.
- A phone held in landscape (`≥ 768px` wide) lands on the tablet tier, where the app header, run
  header and strip together leave the graph only about 200px tall.
- Deselecting a node and reloading re-triggers the docked tier's one-time auto-focus, since the
  "once per run id" guard is keyed on the run, not on whether a selection was ever made.
- Every bottom sheet is now modal — the graph or board behind it cannot be interacted with while
  one is open (true regardless, since the sheet already covers roughly 85% of the viewport), and
  there is no swipe-to-dismiss gesture.
- A run name's tooltip can show no more than the name already stored (names are capped at 30
  characters server-side); lifting that cap is a separate, not-yet-made change to the run-naming
  contract. Outside run detail, a touch user reaches a truncated title's full value only by
  opening the item — there is no tooltip affordance without hover.
- Contributors adding a new component to the node panel or either bottom sheet must remember to
  use container-query variants, not viewport breakpoints, or it will misbehave the moment it is
  hosted somewhere narrower than the screen.
- The responsive behavior above is verified in desktop Chromium at phone and tablet viewport
  sizes; there is no WebKit/iOS Safari project in the automated suite, so dynamic viewport units,
  safe-area insets and touch gestures on real iOS devices still need a manual check before a
  production rollout.
