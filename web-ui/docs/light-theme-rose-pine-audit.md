# Light theme audit: core app vs. canonical Rose Pine Dawn

Catalogs every place the core app's light theme deviates from the target settled in
[`docs/decisions/2026-08-29---01-original-rose-pine-dawn-light-theme.md`](../../docs/decisions/2026-08-29---01-original-rose-pine-dawn-light-theme.md).
This is a documentation-only artifact: no theme code changes here. A later restoration
task consumes this catalog as its checklist.

A color is a **deviation** if either: (a) it is a semantic theme token whose value does
not equal its canonical Dawn counterpart, or (b) it is any color in a component/asset
that is not routed through a semantic token at all (hardcoded hex/rgb/hsl, named
Tailwind palette utilities, raw `white`/`black` utilities, or a third-party surface that
ignores the app's tokens). Colors that are on-palette but hardcoded (bypass the token
layer while still matching a canonical hue) are called out separately rather than
flagged as if they render wrong.

## Target — canonical Rose Pine Dawn

Published upstream palette (rosepinetheme.com), the target this catalog measures against:

| Role | Hex |
|---|---|
| base | `#faf4ed` |
| surface | `#fffaf3` |
| overlay | `#f2e9e1` |
| muted | `#9893a5` |
| subtle | `#797593` |
| text | `#575279` |
| love | `#b4637a` |
| gold | `#ea9d34` |
| rose | `#d7827e` |
| pine | `#286983` |
| foam | `#56949f` |
| iris | `#907aa9` |
| highlightLow | `#f4ede8` |
| highlightMed | `#dfdad9` |
| highlightHigh | `#cecacd` |

## Tier 1 — token-level deviations (`web-ui/src/index.css`, `:root`, lines 8–58)

Accent tokens — faithful:

| Token | Current value | Canonical Dawn role/value | Verdict |
|---|---|---|---|
| `--primary`, `--ring`, `--sidebar-primary`, `--sidebar-ring` | `#685e87` | iris `#907aa9` mixed 70% toward text `#575279` | ✓ AA-deepened (text-weighted mix) |
| `--primary-foreground`, `--sidebar-primary-foreground` | `#faf4ed` | base `#faf4ed` | ✓ |
| `--destructive-foreground` | light `#faf4ed` / dark `#191724` | base (each theme's own) | ✓ |
| `--destructive` | `#735779` | love `#b4637a` mixed 70% toward text `#575279` | ✓ AA-deepened (text-weighted mix) |
| `--muted-foreground` | `#656083` | subtle `#797593` mixed 60% toward text `#575279` | ✓ AA-deepened (text-weighted mix) |
| `--chart-1` | `#56949f` | foam `#56949f` | ✓ |
| `--chart-2` | `#907aa9` | iris `#907aa9` | ✓ |
| `--chart-3` | `#d7827e` | rose `#d7827e` | ✓ |
| `--chart-4` | `#286983` | pine `#286983` | ✓ |
| `--chart-5` | `#ea9d34` | gold `#ea9d34` | ✓ |
| `--status-success` | `#56949f` | foam | ✓ |
| `--status-error` | `#b4637a` | love | ✓ |
| `--status-info` | `#286983` | pine | ✓ |
| `--status-warning` | `#ea9d34` | gold | ✓ |
| `--status-accent` | `#907aa9` | iris | ✓ |
| `--status-neutral` | `#797593` | subtle | ✓ |

Neutral tokens — restored to canonical Dawn:

| Token | Current value | Canonical Dawn role/value | Verdict |
|---|---|---|---|
| `--background`, `--sidebar` | `#faf4ed` / `#fffaf3` | base `#faf4ed` / surface `#fffaf3` | ✓ |
| `--popover` | `#fffaf3` | surface `#fffaf3` | ✓ |
| `--foreground`, `--card-foreground`, `--popover-foreground`, `--secondary-foreground`, `--accent-foreground`, `--sidebar-foreground`, `--sidebar-accent-foreground` | `#575279` | text `#575279` | ✓ |
| `--chart-reference` | `#575279` | text `#575279` (chart reference series; same ink as `--foreground`, diverges in dark) | ✓ |
| `--card` | `rgba(255, 255, 255, 0.55)` (line 14) | surface `#fffaf3` (kept translucent over the warm gradient by design — the glass/gradient treatment is intentionally preserved, only its underlying ink was corrected) | ✓ |
| `--secondary`, `--muted`, `--accent`, `--sidebar-accent` | `#f2e9e1` | overlay `#f2e9e1` | ✓ |
| `--border`, `--sidebar-border` | `rgba(87,82,121,0.08)` | text-ink (`#575279`) at 8% — canonical Dawn has no solid highlight-role equivalent for hairline borders, so this stays ink-alpha | ✓ |
| `--input` | `#797593` | subtle `#797593` | ✓ AA-deepened (re-pointed from ink-alpha) |

Not a deviation, but not-yet-audited against canonical Dawn: canonical Dawn does not
define a distinct highlight-role token in this codebase's variable set, so
`highlightLow`/`Med`/`High` have no current mapping to compare against; restoration
should decide whether `--border`/`--sidebar-border`/`--sidebar-accent` route through
them. (`--input` is now settled — it routes through `subtle`, not a highlight role, per
the AA-deepened row above.)

### Decorative layer (not present in upstream Rose Pine at all)

None of the following exist as concepts in the canonical Dawn spec — the spec defines a
flat 15-role palette, not glass surfaces, gradients, textures, or a radius scale. All are
additions layered on top:

| Element | Location | Description |
|---|---|---|
| Glass surface tokens | `index.css:52–58` | `--surface-glass`, `--surface-glass-strong`, `--surface-glass-border` — translucent overlay tokens with no canonical counterpart |
| Radius scale | `index.css:151–157` | `--radius-sm` … `--radius-4xl`, derived from `--radius` |
| Body gradient | `index.css:178–185` | Light-mode `html:not(.dark) body` background: three radial gradients (iris 8%/foam 6%/gold 6% tinted — bounded down from 22%/18%/6% so accent inks don't need to deepen further) plus a linear gradient over Dawn-neutral stops (`#faf4ed`/`#f4ede8`/`#f2e9e1`, all canonical) |
| Dot-grid texture | `index.css:187–198` | Fixed, masked dot-grid overlay via `body::before` |
| Custom scrollbars | `index.css:200–233` | Thin, ink-alpha-tinted scrollbar styling replacing browser default chrome |
| `.bg-card` blur/shadow | `index.css:239–246` | Global `backdrop-filter: blur(12px)` + white-alpha border + drop shadow applied to any `.bg-card` element |

## Tier 2 — component-level deviations

| File | Line(s) | Issue | Should route through | Status |
|---|---|---|---|---|
| `web-ui/src/components/git-repos/CreateGitRepoDialog.tsx` | 82 | `border-blue-300 bg-blue-50 text-blue-800` (+ dark variants) info banner | `--status-info` (pine) | ✓ fixed |
| `web-ui/src/components/integrations/PlatformManagedCredentialPanel.tsx` | 19 | `bg-green-500` status dot | `--status-success` (foam) | ✓ fixed |
| `web-ui/src/components/layout/ActivityFeedButton.tsx` | ~24 | `text-white` badge text | `text-foreground` on an opaque `bg-tint-status-error` | ✓ fixed — see Contrast section below |
| `web-ui/src/components/ui/dialog.tsx` | 33 | `bg-black/10` overlay scrim | a token-based scrim color | accepted as-is — theme-agnostic backdrop, see repo's Caveats & Known Limitations |
| `web-ui/src/components/layout/MobileDrawer.tsx` | 15 | `bg-black/40` overlay scrim | a token-based scrim color | accepted as-is — theme-agnostic backdrop, see repo's Caveats & Known Limitations |
| `web-ui/src/components/layout/CommandPalette.tsx` | 154 | `bg-black/10` overlay scrim | a token-based scrim color | accepted as-is — theme-agnostic backdrop, see repo's Caveats & Known Limitations |
| `web-ui/src/components/ui/MarkdownViewer.tsx` | 12 | mermaid diagrams pinned to `theme: "default"` | the app's semantic tokens; currently ignores both the light palette and dark mode | future work — see repo's Caveats & Known Limitations |
| `web-ui/src/components/ui/toaster.tsx` | 11 | sonner `richColors` | the app's semantic tokens instead of sonner's built-in palette | ✓ fixed — rich-color variables bridged to `--status-*` in `index.css` |
| `web-ui/src/components/analytics/RunTrendChart.tsx` | 47–48, 58–59 | `hsl(var(--card))`, `hsl(var(--border))`, `hsl(var(--foreground))` | **broken, not just off-palette** — these tokens hold hex/rgba values, not `H S% L%` triples, so wrapping them in `hsl(...)` produces invalid CSS | ✓ fixed |
| `web-ui/src/components/analytics/BottleneckChart.tsx` | 56–57 | same `hsl(var(...))` pattern | same fix — reference the token directly, no `hsl()` wrapper | ✓ fixed |
| `web-ui/src/components/analytics/RoadmapThroughputChart.tsx` | 40–41 | same `hsl(var(...))` pattern | same fix — reference the token directly, no `hsl()` wrapper | ✓ fixed |
| `web-ui/index.html` | 16 | `<meta name="theme-color" content="#3e3859">` hardcoded to the custom ink, not a token, and not theme-aware | derive from the active theme, or at minimum from a canonical-Dawn-consistent value | ✓ fixed — now `#575279` |
| `web-ui/src/components/Logo.tsx` | 29–39 | hardcoded hex fills (`#907aa9` iris, `#56949f` foam, `#faf4ed` base) | **on-palette, hardcoded, frozen brand mark** — these already equal canonical Dawn hues; flagged for completeness, not because they render wrong | not a deviation — no change needed |

### Explicitly clean

Run graph and roadmap graph nodes/edges, all status/priority/level badges, and the
`statusColors` / `priorityMeta` / `milestoneMeta` maps route through the semantic tokens
above — no deviation found in any of them.

Status indicators across the app route through `STATUS_TONE_CLASSES` and the `StatusDot`/
`StatusCallout` primitives (`src/lib/statusColors.ts`,
`src/components/ui/StatusDot.tsx`, `src/components/ui/StatusCallout.tsx`), so a state's
tone and class recipe have exactly one source. `palette-hygiene.test.ts` scans the whole
`src/` tree for off-brand colors and guards this from regressing.

## Resolved

The `--muted-foreground` shortfall this section used to track (canonical Dawn `subtle`
on `base` measured ≈4.03:1, under the 4.5:1 AA text threshold) is fixed: it and every
other failing light-mode text/control pair are now deepened per
[`docs/decisions/2026-09-26---01-aa-contrast-text-and-controls.md`](../../docs/decisions/2026-09-26---01-aa-contrast-text-and-controls.md),
which also covers why the deepened tokens are still "on-palette" despite no longer being
exact canonical hexes.

## Contrast: status indicators, badges, charts

Badges, roadmap/priority/milestone chips, and log severity labels used to render their
*word* in the tone color over a translucent `bg-status-*/15` tint. Both numbers below are
computed by `src/lib/__tests__/status-contrast.test.ts`, straight from `index.css`: "before"
is the tone color against its own translucent tint over `--popover`; "after" is
`--foreground` against the new opaque `bg-tint-status-*` (15%) tint. All six "after" values
clear the 4.5:1 AA text floor in both themes; four of six "before" values failed it in
light, two of six in dark.

| Tone | Light before → after | Dark before → after |
|---|---|---|
| success | 2.84:1 → 6.02:1 | 6.87:1 → 8.87:1 |
| error | 3.38:1 → 5.86:1 | 4.54:1 → 10.02:1 |
| info | 4.75:1 → 5.66:1 | 2.74:1 → 10.86:1 |
| warning | 1.93:1 → 6.27:1 | 7.13:1 → 8.86:1 |
| accent | 3.11:1 → 5.97:1 | 5.82:1 → 9.24:1 |
| neutral | 3.55:1 → 5.88:1 | 4.09:1 → 10.00:1 |

Recharts' axis/legend/tooltip labels were pinned to a fixed `#666`, regardless of theme.
Against the dark card that measured only **2.87:1**; against the new opaque chart
surface (`--popover`, via `chartTickProps()`/`chartTooltipProps()` in
`src/lib/chartSeriesStyles.ts`), `--foreground` now measures **7.00:1 in light, 12.50:1
in dark**.

Some marks and tone pairs cannot reach these floors within Rose Pine's existing hues at
all — relieved by an adjacent status word rather than a threshold change, and enforced
as exact-match data (not a loosened check) in `status-contrast.test.ts`:

- **Marks below 3:1** against both `--background` and `--popover`, light only: `status-warning`
  (≈2.05:1/2.16:1), `chart-3` rose (≈2.60:1/2.74:1), `chart-5` gold (≈2.05:1/2.16:1). No
  dark-mode marks fail. Cue: the status word always sits beside the mark.
- **Tone pairs too close by OKLab ΔE** (normal vision < 15, or simulated protanopia/
  deuteranopia < 8): nine of the fifteen light pairs (every pairing that includes
  success, error, info, accent, or neutral except a `warning` pairing) and four of the
  fifteen dark pairs (success/accent, error/neutral, info/neutral, accent/neutral). Cue:
  the same — a status word, never two bare same-shaped dots side by side.

See `docs/decisions/2026-09-27---01-status-ink-labels-and-contrast-gate.md` for the full
decision (the opaque tint utility, the badge's `::before` dot, and the three-part gate:
`src/lib/__tests__/status-contrast.test.ts`, `src/__tests__/status-ink-hygiene.ts`, and
`e2e/specs/status-contrast.spec.ts`).
