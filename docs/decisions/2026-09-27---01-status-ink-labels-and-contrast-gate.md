# Status words render in ink; tone color moves to marks, with an opaque tint utility and a three-part contrast gate

## Status

current

## Context

`docs/decisions/2026-09-24---01-status-tone-vocabulary.md` set badge/dot text in the tone
color (`text-status-*`) with a translucent `bg-status-*/15` behind it. Measured against
WCAG 2.2 1.4.3 (text ≥ 4.5:1), most of those labels fail in light mode (warning ≈1.9:1,
success ≈2.8:1, accent ≈3.1:1 — only pine/info and ink pass) and two fail in dark
(info ≈2.7:1, neutral ≈4.1:1). The translucent tint compounds this: composited over a
glass card or the gradient canvas, both the tint and the tone-colored word sitting on it
drift further from their nominal contrast depending on what's behind them. Separately,
Recharts painted every axis/legend/tooltip label in a fixed `#666` regardless of theme
(≈2.87:1 on the dark card), and the tooltip's own surface (`var(--card)`) is translucent
in light mode. Status badges, roadmap/priority/milestone chips, log severity labels, DAG
and roadmap graph node status words, layout counters, and chart labels are all instances
of the same defect: a text word colored with the tone instead of the foreground ink.

## Decision

### Words are ink; tone lives on marks only

Every status/tone/level *word* renders in `text-foreground` (or inherits ink from an
inverted-tooltip ancestor). Tone color is confined to non-text marks: a badge's leading
`::before` dot, a `StatusDot`, an icon/glyph, a border, a tint, a chart stroke/line, or a
meter fill. `STATUS_TONE_CLASSES`' `text` key (and the equivalent `textClass`/`icon` keys
in `priorityMeta.ts`, `roadmapLevel.ts`, `milestoneMeta.ts`, `logLevelStyles.ts`) is now
documented as mark-only — applied to an icon, never to a label. `logLevelStyles.ts` splits
its former single `text` key into `icon` (tone, for the glyph) and `label`
(`text-foreground` plus the level's font-weight), since a log row needed the same word/mark
split as everywhere else. This closes the redundant-encoding gap the `2026-09-25---01`
entry (below) opened without fully resolving: identity was already carried by icon shape
and row tint, but the label word itself still doubled the tone until now.

The badge recipe keeps the color cue with zero call-site churn: `badge` gains a leading
`::before` pseudo-element in the tone color (`before:inline-block before:size-1.5
before:shrink-0 before:rounded-full before:bg-status-<tone> before:content-['']`), so
every existing `Badge`/badge-recipe consumer gets the dot back automatically. `before:
inline-block` matters because `::before` defaults to `display: inline`, which ignores
width/height — a host that isn't already a flex container would otherwise render the dot
at zero size silently.

### An opaque tint utility replaces the translucent modifier

`bg-tint-<color>` (15%) and `bg-tint-strong-<color>` (25%) are new Tailwind `@utility`
functional matchers in `index.css`, each a `color-mix(in srgb, --value(--color-*) N%, var(--popover))`
— an sRGB mix over the *opaque* `--popover` surface, never a translucent `bg-*/N`
modifier. sRGB mixing is required because the contrast gate below models this as straight
alpha compositing; mixing in another color space would make that math wrong. The base
must stay an opaque token — `--popover` is opaque in both themes, unlike `--card` (a
translucent glass surface in light mode) — or the tint would stop being backdrop-
independent, reintroducing the exact defect (measured contrast varying with whatever
glass or gradient sits behind the element) this change removes. `STATUS_TONE_CLASSES`
gains three additive keys — `tint`, `tintStrong`, `borderSoft` (a `/60` border alpha for
pairing with a tint background) — never renaming or removing an existing key: a
downstream build that composes this web-ui's source consumes this recipe interface
directly, so the existing `badge`/`bg`/`border`/`text`/`dot`/`callout` keys had to stay
key-compatible.

No `:root`/`.dark` CSS custom-property value changed anywhere in this change — every
fix is in how a color is *used* (word vs. mark, translucent vs. opaque tint), never in
the palette itself.

### Chart text and surface tokens

`chartSeriesStyles.ts` gains `CHART_TEXT_TOKEN` (`--foreground`) and `CHART_SURFACE_TOKEN`
(`--popover`), plus `chartTickProps(fontSize)` and `chartTooltipProps()` helpers every
analytics chart now uses for its axis ticks and tooltip, instead of a fixed `#666` and an
inline `var(--card)` tooltip. This closes the light-surface tooltip gap
`docs/decisions/2026-09-25---02-chart-series-style-registry.md` left open (that entry
fixed series color/dash separation and pinned legend/tooltip text to the foreground ink,
but the tooltip's own background and the axis ticks were untouched) — it is not superseded,
only extended: the chart-surface contrast check in `chart-series-distinguishable.test.ts`
now measures every series against `--popover` in both themes (previously `--background`
in light, `--card` in dark), and a new check pins `--foreground` against that same
surface at the text floor.

### Three-part enforcement, one WCAG math module

1. **A computed unit gate** (`status-contrast.test.ts`) recomputes, per theme, straight
   from `index.css` and the recipe modules: every tint/tintStrong label against
   `--foreground` at 4.5:1; the badge/callout recipe shape (ink word, opaque tint, no
   `text-status-*`); every `badgeVariants()` variant's text against every real surface
   (the opaque background/popover/muted for the variants with their own opaque fill; the
   full canvas/card surface set for `outline`'s transparent background); plain
   `--foreground` against every surface; every status/chart/primary mark against
   `--background`/`--popover` at 3:1; and every pair of the six status tones by OKLab ΔE
   under normal vision and simulated protan/deutan color-vision deficiency.
2. **A lexical guard** (`status-ink-hygiene.ts`/`.test.ts`) bans `text-status-*`/
   `text-chart-*` in any `.tsx` file and a translucent `bg-status-*/N`/`bg-chart-*/N` in
   any `.ts`/`.tsx` file, repo-wide, with one allow-listed exception
   (`DecisionButtons.tsx`'s `hover:bg-status-success/90`, a control fill out of scope for
   this change — a separate concurrent effort owns translucent `bg-primary/N`/
   `bg-destructive/N` control fills, which this guard deliberately does not flag).
3. **A browser spec** (`status-contrast.spec.ts`) cross-checks the same floors against a
   real rendered DOM — real glass cards, the real gradient canvas, a real theme toggle,
   a real Recharts mount — reusing the same compositing math via `e2e/helpers/contrast.ts`.

All three share one WCAG/OKLab math module (`src/lib/__tests__/helpers/colorMetrics.ts`)
so a contrast/ΔE formula is defined exactly once. That module gained `parseColor`,
`composite`, `toHex`, `readTintUtilities` (parses the `bg-tint-*` `@utility` rules'
percentage and base token straight from `index.css`), `resolveTint` (composites a raw
token over a theme's tint base at that percentage — the same math the browser renders),
and canvas/card sampling helpers (`lightCanvasSamples`, `lightCardSamples`). It is now
imported from `e2e/helpers/contrast.ts` too, which required moving its `index.css` path
resolution off a module-scope `path.resolve(__dirname, ...)` (Playwright loads it as an
ESM module with no `__dirname`) to a lazy `fileURLToPath(new URL(...))` inside each
reader — itself with a caveat: written inline as `new URL(relativePath, import.meta.url)`,
Vite's static asset-URL transform intercepts the literal pattern under Vitest and
resolves it against the dev-server origin instead of the filesystem; capturing
`import.meta.url` into a local variable first avoids the transform recognizing the
pattern.

### Relief lists are reviewable data, not a loosened threshold

Some marks and tone pairs cannot clear their floor within the existing Rose Pine hues at
all: computed against the real palette (not any approximate figure a spec may have
guessed), light `status-warning`, `chart-3`, and `chart-5` fall short of 3:1 against both
`--background` and `--popover`; nine of fifteen light tone pairs and four of fifteen dark
tone pairs fall under the ΔE/CVD-ΔE floor. Rather than lowering the threshold, these are
enumerated as exact-match `MARK_RELIEF`/`TONE_PAIR_RELIEF` constants in
`status-contrast.test.ts`, each entry carrying a `cue` field naming the non-color carrier
that disambiguates it in practice — in every case, "status word beside the mark": no
surface in this app shows two same-shaped dots side by side with no adjoining label, so
the relieved pairs are never actually ambiguous to a viewer, only to a bare color-distance
metric. A test asserts the computed failing set equals the relief constant exactly, so a
palette change that fixes one of these must remove it from the list (or the test fails),
and a palette change that newly breaks a previously-passing pair must add it (or the test
fails) — the list can't silently drift out of sync with what the palette actually does.

## Consequences

`STATUS_TONE_CLASSES`, `priorityMeta.ts`, `roadmapLevel.ts`, `milestoneMeta.ts`,
`logLevelStyles.ts`, and `timelineStage.ts` all changed their recipe *values* (tint
instead of translucent `/N`, ink instead of tone on the label) while keeping every
existing key name a downstream build composing this web-ui's source already depends on.
Every render site that consumed a tone-colored word (badges, chips, DAG/roadmap graph
node status words, log severity labels, layout counters, chart labels) now renders that
word in `text-foreground` and gets its tone cue from an adjacent mark instead — the
visual change is a subtler badge/chip word paired with a small hue dot or icon, not a
redesign. This amends, but does not reverse, two clauses of two earlier entries:
`docs/decisions/2026-09-24---01-status-tone-vocabulary.md`'s "badges and dots keep
tone-colored labels" clause (the six tone *meanings*, the foreground-body-text rule for
callouts, the `destructive`-vs-tone-table split, and the toast bridge are untouched), and
`docs/decisions/2026-09-25---01-log-severity-redundant-encoding.md`'s label-color clause
(icon shape, row tint, and the `logLevelStyles` module itself are untouched — only the
label's own color changed, from tone to ink). Per this repository's supersession
convention, a clause-level amendment that doesn't reverse an entry's central decision
does not mark that entry's index row superseded; both stay `current`.
