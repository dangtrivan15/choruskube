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

## Tier 1 — token-level deviations (`web-ui/src/index.css`, `:root`, lines 8–57)

Accent tokens — faithful:

| Token | Current value | Canonical Dawn role/value | Verdict |
|---|---|---|---|
| `--primary`, `--ring`, `--sidebar-primary` | `#907aa9` | iris `#907aa9` | ✓ |
| `--primary-foreground`, `--sidebar-primary-foreground` | `#faf4ed` | base `#faf4ed` | ✓ |
| `--destructive` | `#b4637a` | love `#b4637a` | ✓ |
| `--muted-foreground` | `#797593` | subtle `#797593` | ✓ |
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
| `--card` | `rgba(255, 255, 255, 0.55)` (line 14) | surface `#fffaf3` (kept translucent over the warm gradient by design — the glass/gradient treatment is intentionally preserved, only its underlying ink was corrected) | ✓ |
| `--secondary`, `--muted`, `--accent`, `--sidebar-accent` | `#f2e9e1` | overlay `#f2e9e1` | ✓ |
| `--border`, `--sidebar-border` | `rgba(87,82,121,0.08)` | text-ink (`#575279`) at 8% — canonical Dawn has no solid highlight-role equivalent for hairline borders, so this stays ink-alpha | ✓ |
| `--input` | `rgba(87,82,121,0.12)` | text-ink (`#575279`) at 12% — same as above | ✓ |

Not a deviation, but not-yet-audited against canonical Dawn: canonical Dawn does not
define a distinct highlight-role token in this codebase's variable set, so
`highlightLow`/`Med`/`High` have no current mapping to compare against; restoration
should decide whether `--border`/`--input`/`--sidebar-border`/`--sidebar-accent` route
through them.

### Decorative layer (not present in upstream Rose Pine at all)

None of the following exist as concepts in the canonical Dawn spec — the spec defines a
flat 15-role palette, not glass surfaces, gradients, textures, or a radius scale. All are
additions layered on top:

| Element | Location | Description |
|---|---|---|
| Glass surface tokens | `index.css:50–56` | `--surface-glass`, `--surface-glass-strong`, `--surface-glass-border` — translucent overlay tokens with no canonical counterpart |
| Radius scale | `index.css:145–151` | `--radius-sm` … `--radius-4xl`, derived from `--radius` |
| Body gradient | `index.css:169–177` | Light-mode `html:not(.dark) body` background: three radial gradients (iris/foam/gold tinted) plus a linear gradient warmed to Dawn-neutral stops (`#f2e9e1`/`#e9e0d6`, canonical `base` `#faf4ed` is the first stop) |
| Dot-grid texture | `index.css:179–190` | Fixed, masked dot-grid overlay via `body::before` |
| Custom scrollbars | `index.css:192–225` | Thin, ink-alpha-tinted scrollbar styling replacing browser default chrome |
| `.bg-card` blur/shadow | `index.css:227–239` | Global `backdrop-filter: blur(12px)` + white-alpha border + drop shadow applied to any `.bg-card` element |

## Tier 2 — component-level deviations

| File | Line(s) | Issue | Should route through | Status |
|---|---|---|---|---|
| `web-ui/src/components/git-repos/CreateGitRepoDialog.tsx` | 82 | `border-blue-300 bg-blue-50 text-blue-800` (+ dark variants) info banner | `--status-info` (pine) | ✓ fixed |
| `web-ui/src/components/integrations/PlatformManagedCredentialPanel.tsx` | 19 | `bg-green-500` status dot | `--status-success` (foam) | ✓ fixed |
| `web-ui/src/components/layout/ActivityFeedButton.tsx` | 24 | `text-white` badge text | a `*-foreground` token (it sits on `bg-destructive`, so `--destructive-foreground` if one is added, or `--primary-foreground`-style pattern) | not in scope — see repo's Caveats & Known Limitations |
| `web-ui/src/components/ui/dialog.tsx` | 33 | `bg-black/10` overlay scrim | a token-based scrim color | accepted as-is — theme-agnostic backdrop, see repo's Caveats & Known Limitations |
| `web-ui/src/components/layout/MobileDrawer.tsx` | 15 | `bg-black/40` overlay scrim | a token-based scrim color | accepted as-is — theme-agnostic backdrop, see repo's Caveats & Known Limitations |
| `web-ui/src/components/layout/CommandPalette.tsx` | 154 | `bg-black/10` overlay scrim | a token-based scrim color | accepted as-is — theme-agnostic backdrop, see repo's Caveats & Known Limitations |
| `web-ui/src/components/ui/MarkdownViewer.tsx` | 12 | mermaid diagrams pinned to `theme: "default"` | the app's semantic tokens; currently ignores both the light palette and dark mode | future work — see repo's Caveats & Known Limitations |
| `web-ui/src/components/ui/toaster.tsx` | 11 | sonner `richColors` | the app's semantic tokens instead of sonner's built-in palette | future work — see repo's Caveats & Known Limitations |
| `web-ui/src/components/analytics/RunTrendChart.tsx` | 47–48, 58–59 | `hsl(var(--card))`, `hsl(var(--border))`, `hsl(var(--foreground))` | **broken, not just off-palette** — these tokens hold hex/rgba values, not `H S% L%` triples, so wrapping them in `hsl(...)` produces invalid CSS | ✓ fixed |
| `web-ui/src/components/analytics/BottleneckChart.tsx` | 56–57 | same `hsl(var(...))` pattern | same fix — reference the token directly, no `hsl()` wrapper | ✓ fixed |
| `web-ui/src/components/analytics/RoadmapThroughputChart.tsx` | 40–41 | same `hsl(var(...))` pattern | same fix — reference the token directly, no `hsl()` wrapper | ✓ fixed |
| `web-ui/index.html` | 16 | `<meta name="theme-color" content="#3e3859">` hardcoded to the custom ink, not a token, and not theme-aware | derive from the active theme, or at minimum from a canonical-Dawn-consistent value | ✓ fixed — now `#575279` |
| `web-ui/src/components/Logo.tsx` | 29–39 | hardcoded hex fills (`#907aa9` iris, `#56949f` foam, `#faf4ed` base) | **on-palette, hardcoded, frozen brand mark** — these already equal canonical Dawn hues; flagged for completeness, not because they render wrong | not a deviation — no change needed |

### Explicitly clean

Run graph and roadmap graph nodes/edges, all status/priority/level badges, and the
`statusColors` / `priorityMeta` / `milestoneMeta` maps route through the semantic tokens
above — no deviation found in any of them.

## Known limitation

Canonical Dawn's `subtle` (`#797593`, held by `--muted-foreground`) on `base` (`#faf4ed`,
now `--background`) is measured at ≈4.03:1 — under the 4.5:1 WCAG AA threshold for
normal-size text, and `--muted-foreground` is used at `text-sm`/`text-xs` across ~86
files, so it does not qualify for the 3:1 large-text exception. The shortfall pre-dates
restoration (`--muted-foreground` was already canonical Dawn and unchanged by it); fixing
it would mean darkening `--muted-foreground` off its canonical Dawn value, which is out
of scope here. Tracked as deferred, not fixed by this catalog or its consuming
restoration.
