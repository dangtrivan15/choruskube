# One chart-series style registry, a computed distinguishability gate, and a neutral reference token for "Total"

## Status

current

## Context

The Analytics page's two multi-series charts hard-coded each series' color inline.
Recharts builds the legend and tooltip straight from those props, so nothing could
check every series of a chart against every other series, in each theme, from one typed
input. In dark mode, Run Trend's "Total" line was painted in the foreground ink, which
measures only ΔE 10.4 (OKLab, ×100) against "Completed" — well under the 15 a person
needs to tell two colors apart at a glance — and when every run succeeds the two values
are equal, so Completed's line fully hides Total's. Bottlenecks' P95 bars used the
"warning" status color, which is only 2.05:1 against the light background and wrongly
implies an alarm on a plain measurement. The existing status-color regression tests only
asserted that resolved colors differ as a set, which both defects passed while still
being hard to tell apart.

## Decision

`src/lib/chartSeriesStyles.ts` exports `CHART_SERIES_STYLES`, a chart → series → `{
token, dashArray, mark, label }` map. Every analytics chart reads its series' color,
dash pattern and legend name from it, the same "one typed table, several consumers"
shape `src/lib/roadmapEdgeStyles.ts` uses for roadmap dependency edges. It diverges from
that precedent in two ways: it is typed `as const satisfies Record<...>` rather than
with an interface-typed const, because the registry's own pin test and the
distinguishability gate below need to enumerate the exact literal chart and series keys,
which a widened `string`-keyed type would not preserve; and its `seriesColor()` /
`seriesDashProps()` helpers live in the same file rather than a separate resolver
module, because they are one-line derivations of a single entry with no orchestration
logic of their own.

Status tokens are reserved for series whose meaning *is* a status. Run Trend's
Completed and Failed keep the success/error status tokens, since they mirror the same
run-status badges used elsewhere. Bottlenecks' Avg and P95 are measurements, not states,
so they move to categorical chart tokens (`--chart-4` pine, `--chart-2` iris) instead —
the only pair of the five `--chart-*` slots that clears every check in both themes. The
chart-slot order itself is left alone: those same slots also color roadmap Epic/Story/
Task accents and milestone chips, and reordering them would repaint those screens for a
change scoped to two analytics bars.

A new `--chart-reference` token (`#575279` light, `#6e6a86` dark — both canonical Rose
Pine neutrals) replaces the foreground ink for Total. It is drawn dashed, unfilled, and
last in paint order, so it stays visible on top of a coinciding Completed line instead
of hiding behind it. No existing token cleared both themes against both status series:
foreground itself fails in dark, muted-foreground fails in light, and pine fails in
light and is already a reserved status color. In dark mode the new token deliberately
recedes to 3.2:1 contrast (versus 6.7:1 in light) — the alternative with more contrast
headroom passed the color-difference gate only through its dash pattern, which would
have made Total and Failed indistinguishable by color alone for colorblind readers in
dark mode.

Identity is never carried by color alone: Run Trend's three lines also get distinct
dash patterns (Completed solid, Failed dotted, Total dashed), legend icons carry the
same pattern as their line, and legend/tooltip text renders in the foreground ink
rather than the series color, so the swatch — not the label — is what carries the color.

A unit-level gate (`src/lib/__tests__/chart-series-distinguishable.test.ts`) resolves
every registry entry to its hex per theme, straight from `index.css`, and asserts: every
series clears 3:1 contrast against the chart surface; every pair clears ΔE ≥ 15 under
normal vision; and every pair clears ΔE ≥ 8 under simulated protan/deutan color-vision
deficiency, unless both are line series with different dash patterns. Generic
lightness-band and chroma-floor checks are deliberately not enforced, because canonical
Rose Pine's pastel dark accents and low-chroma light hues fall outside those generic
bands by design — enforcing them would force off-brand values.

## Alternatives considered

- **Keep the inline color literals and add a source-grep test banning them.** Catches a
  hardcoded value but cannot compute pairwise separation without parsing the component
  source, so a new collision between two still-registry-driven series would pass silently.
- **A generic ordered palette array assigned by series index.** Color would follow rank
  instead of the entity, so adding or removing a series would repaint every series after it.
- **Keep status tokens on every series.** P95 keeps looking like an alarm and stays below
  3:1 contrast in light mode.
- **Reorder the five chart slots into a validated sequence.** Fixes Bottlenecks but
  repaints roadmap level accents and milestone chips, which read those same slots by
  position.
- **Restructure Run Trend as a stacked area so Total is implicit.** Removes the
  collision by removing the series, but is a materially bigger UX change than giving the
  existing series a readable identity.
- **Screenshot/visual-regression testing instead of a computed gate.** No baseline
  infrastructure exists, results are sensitive to fonts and antialiasing, and a human
  still has to judge the outcome — a computed contrast/ΔE number is what makes
  "distinguishable" reviewable and enforceable at test time.

## Consequences

A chart's series identity now has exactly one place to change — the registry — and the
distinguishability gate re-validates every pair, in both themes, against whatever
`index.css` and the registry currently say, so a future palette or chart edit that
reintroduces a collision fails the unit suite instead of shipping quietly. The two
chart slots that collide in light mode (`--chart-1` foam, `--chart-2` iris) remain
usable individually; the gate is what stops a future chart from pairing them, not a
structural guarantee. Rose Pine's status palette already uses five of its six accents,
so a measurement series necessarily shares a hue with some status — pine is also
"running", iris is also "paused" — but binding it to the chart *token* rather than the
status *token* means a future status-palette retune cannot repaint the charts as a
side effect.
