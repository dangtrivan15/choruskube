# App text and controls meet WCAG AA contrast in both themes

**Status:** current

## Context

`web-ui/docs/light-theme-rose-pine-audit.md` catalogued the core app's light theme
against canonical Rose Pine Dawn (`docs/decisions/2026-08-29---01-original-rose-pine-dawn-light-theme.md`)
without checking contrast. Measured against WCAG 2.2 1.4.3 (text ≥ 4.5:1) and 1.4.11
(non-text ≥ 3:1), several pairs fail today:

- **Light:** secondary text ≈3.4–4.2:1, primary-button labels and `text-primary` links
  3.47:1, error text 3.84:1, input borders 1.2:1, focus rings (drawn at 50% opacity)
  ≈1.75:1, and a roadmap-timeline tooltip's secondary line (`text-background/70`) 4.20:1.
- **Dark:** sidebar labels at reduced opacity, the destructive-button hover at 3.85:1,
  input borders ≈2.07–2.25:1, placeholders on the translucent input fill 4.21–4.47:1, and
  the invalid-state border (drawn at 50% opacity) ≈2.4:1.

In canonical Dawn, only `text` (`#575279`) and `pine` reach 4.5:1 on Dawn's own `overlay`
surface — `subtle`, `iris` and `love` all fall short on every Dawn surface. So no failing
light-mode ink can stay an exact canonical hex and also pass; something has to give
beyond the strict "canonical hexes only" reading of the app's palette rule. `--status-*`,
badge colors, chart colors, and the marketing/sign-in surfaces are out of scope here —
they belong to sibling contrast work under the same effort.

## Decision

### Correction ladder (which fix applies, in order)

For each failing pair, apply the first rung that works:

1. **Re-point to another canonical role of the same variant** that passes and keeps its
   meaning — e.g. light `--input` becomes `subtle`.
2. **Otherwise, deepen the canonical role toward the same variant's `text` role** via an
   sRGB mix (`mixSrgb(text, role, weight)`), at the smallest 10% step that clears the
   bar. The result ships as a literal hex; the palette guard pins the recipe.
3. **Otherwise, change the component recipe** (e.g. drop an opacity modifier). Never
   introduce a hue the palette cannot produce.

Recipes shipped by this change:

| Token | Before | After | Recipe |
|---|---|---|---|
| `--muted-foreground` | `#797593` (subtle) | `#656083` | `mixSrgb(text, subtle, 0.6)` |
| `--primary`, `--ring`, `--sidebar-primary`, `--sidebar-ring` | `#907aa9` (iris) | `#685e87` | `mixSrgb(text, iris, 0.7)` |
| `--destructive` | `#b4637a` (love) | `#735779` | `mixSrgb(text, love, 0.7)` |
| `--input` (light) | `rgba(text, 0.12)` | `#797593` | canonical `subtle` |
| `--input` (dark) | `#524f67` (highlightHigh) | `#6e6a86` | canonical `muted` |
| `--sidebar-primary-foreground` (dark) | `#e0def4` | `#191724` | canonical `base` |
| `--destructive-foreground` (new, both themes) | — | light `#faf4ed`, dark `#191724` | canonical `base` |

`muted-foreground` clears the AA floor at 60% `text`; `primary` at 70%. `destructive`
would also clear at 60% (4.55:1), but it takes one extra step to 70% because it is the
one accent also used as ink on its own 5–10% tints in callouts: at 60% a `bg-destructive/5`
callout over the darkest canvas floor measures 4.27:1, against 4.52:1 at 70%. The
"smallest step" rule is applied per token against what that token's own uses need, not
against the bare text-on-surface case alone.

### Bounded light canvas

The light body gradient's linear stops moved from `#faf4ed 0% / #f2e9e1 45% / #e9e0d6
100%` (the last stop non-canonical) to `#faf4ed 0% / #f4ede8 45% / #f2e9e1 100%` — all
three canonical Dawn hexes. The iris and foam radial tints were cut from 22%/18% to
8%/6%; gold stays at 6%. Left alone, the canvas got dark enough that even `text` only
reached ≈4.55:1, which would have forced every accent ink toward 90–100% `text` (erasing
the brand hue) to still read on the canvas. The canvas is decoration and the inks carry
the brand, so this trades a visibly subtler wash for keeping `primary` at 70% `text`
rather than 90%.

### Solid destructive button

`bg-destructive/10 text-destructive` cannot pass in either theme (light tops out at
4.46:1 even with `love` deepened 80% toward `text`; dark's hover reaches only 3.85:1).
The destructive button variant is now `bg-destructive text-destructive-foreground` in
both themes (5.71:1 light, 6.07:1 dark), using a new `--destructive-foreground` token
(`base` in each theme). Destructive **menu items** stay tinted, but at a uniform 10% in
both themes (previously 10% light / 20% dark).

Both the `default` and `destructive` button variants also drop their hover recipes
(`[a]:hover:bg-primary/80`, `hover:bg-destructive/20` / `dark:hover:bg-destructive/30`):
each lowered a filled control's own fill opacity toward the page, which is exactly what
would have re-lowered contrast for the light label it carries. No opacity-preserving
replacement (border, shadow, brightness filter) was added — designing one is interaction
design, not a contrast fix, and is out of scope here. `outline`, `secondary` and `ghost`
keep working hover states, since none of their recipes reduce a filled control's opacity
under a light label; only `active:translate-y-px` still responds to a `default`/
`destructive` button press.

### Focus and invalid-state indicators at full opacity

Every focus-visible color (ring, outline, border) drops its `/50` alpha: the halo was
≈1.75:1 in light and ≈2.9–3.06:1 in dark at 50%, against ≥4.58:1 light / ≥7.25:1 dark at
full strength. The dark-only `aria-invalid:border-destructive/50` override (2.44:1 on
base, 2.39:1 on surface) is removed, so the full-strength `aria-invalid:border-destructive`
(≥5.6:1 everywhere) applies in both themes; the supplementary `aria-invalid:ring-destructive/20`
/ `dark:aria-invalid:ring-destructive/40` halo is untouched; the border is the invalid
indicator the gate measures.

### Tint recipes

An accent ink placed on its own tint follows one of two ceilings, both proven by the
gate:

- **≤10%** on an opaque surface (base, popover/surface, or a card composited over the
  canvas) — covers the destructive dropdown item and a tinted status marker.
- **≤5%** where the element can sit directly on the light canvas (e.g. a callout inside
  the run-detail side panel) — covers destructive callouts. 10% there measured 4.24:1 on
  the darkest canvas floor; 5% measures ≥4.52:1, matching the existing mermaid-error and
  file-load callouts.

Nothing lexically scans for a call site that exceeds these ceilings — a reliable rule
would have to pair a background class with a text class on the same element, which a
line-based scanner cannot do. The convention is recorded here so a reviewer has
something to check a new tinted recipe against.

### Text-entry fills decoupled from `--input`

Raising dark `--input` to `muted` fixes the input border, but inputs/selects also paint
`dark:bg-input/30` (select hover `/50`): with the brighter `--input`, that would drop
placeholder text to ≈3.7:1 (2.9:1 under the select's hover fill). So text-entry fills now
read the `--muted` **token** instead (`dark:bg-muted/50`, select hover `/70`) — Tailwind's
`bg-muted` utility reads the shadcn token `--muted`, which in dark is Rose Pine `overlay`,
not the Rose Pine *muted* role that `--input` now holds. `--input` means only "control
boundary" for enabled fields.

Disabled text-entry fills are re-expressed to keep today's look rather than inherit the
repointed `--input`, since disabled controls are contrast-exempt and a heavier disabled
field would be a regression with no accessibility benefit: light `disabled:bg-input/50`
becomes `disabled:bg-muted`, dark `dark:disabled:bg-input/80` becomes
`dark:disabled:bg-input/50`. The outline button keeps `dark:bg-input/30` / hover `/50`
unchanged — its label is `foreground`, which clears ≥7.13:1 on those fills regardless.

### Enforcement

Three parts, all exercised by every tree that composes this stylesheet:

1. A Vitest **contrast gate** (`web-ui/src/lib/__tests__/text-control-contrast.test.ts`)
   that reads both theme blocks from `index.css`, builds a background model (canonical
   surfaces, composited translucent layers, and a conservatively-modelled light canvas
   floor), and checks a declared registry of ink/background pairs against the 4.5:1 or
   3:1 floor. The light canvas floor is asserted against the gradient declaration's full
   set of tints and stops, so an edited, added or removed tint fails the gate instead of
   silently going unmodelled.
2. A whole-`src/`-tree **ink/state scanner** (`web-ui/src/__tests__/ink-hygiene.ts`) that
   rejects any translucent neutral/primary/destructive/inverse ink, any alphaed
   focus-visible color, and any alphaed invalid-state border, behind any variant chain.
   It shares its file walker with the existing off-brand-color scanner rather than
   copying it.
3. A few Playwright **spot checks** (`web-ui/e2e/specs/text-control-contrast.spec.ts`) that
   a real dialog's rendered colors match what the gate measured, in both themes.

`@axe-core/playwright` was rejected: it marks text over a gradient or `backdrop-filter`
as "needs review" (most of light mode), and it would fail on out-of-scope surfaces (status
tones, badges, charts) until those are fixed separately.

## Alternatives considered

- **Canonical hexes only.** Map every failing light ink to `text`. Rejected: it erases
  the difference between primary and secondary text and turns every link and primary
  button into the body-text color — the strict reading of "on-palette" that this decision
  does not take (see the open question below).
- **The product's older custom darker ink.** Rejected: it reopens
  `docs/decisions/2026-08-29---01-original-rose-pine-dawn-light-theme.md`'s choice of
  canonical Dawn as the target and introduces a color that appears nowhere in the
  published palette.
- **`color-mix()` in CSS**, so the mix recipe is self-documenting in the stylesheet.
  Rejected: `getComputedStyle` and the existing hex-parsing palette guard would see an
  unresolved function string, not a color, breaking both.

## Consequences

Five light tokens stop being exact canonical Dawn hexes; the palette guard now pins mix
recipes for them, which is new territory for that guard. Light-mode accents read deeper
and less saturated (the primary fill goes from `#907aa9` to `#685e87`, destructive ink
from `#b4637a` to `#735779`), light form fields read visibly heavier (input border from a
12%-ink hairline to a solid `subtle`), and the light canvas gradient reads subtler. This
refines, not supersedes, `docs/decisions/2026-08-29---01-original-rose-pine-dawn-light-theme.md`
— canonical Dawn stays the source of every role mixed or re-pointed here; nothing in that
entry's own decision is reversed.

`docs/decisions/2026-09-24---01-status-tone-vocabulary.md` states that the
`text-destructive`-vs-status-tone-table split is safe "because a pinned test asserts
`--destructive` and `--status-error` resolve to the same value in both themes, so the two
names can never visually drift apart." After this change that equality holds only in
dark (`--destructive` and `--status-error` both stay `love` `#eb6f92`); in light,
`--destructive` is now `#735779` while `--status-error` stays canonical `love` `#b4637a`
(OKLab ΔE ≈ 12.5) — a failed-run indicator and a Delete button no longer share a hue in
light. This is an amendment to that one supporting fact, not a reversal of the
`destructive`-vs-tone-table split itself, which is untouched and remains the reason that
split is safe (the split no longer relies on hue equality with `--status-error`, only on
each being used in disjoint contexts). `docs/decisions/README.md`'s supersession trigger
("reverses or replaces") does not apply here, so `2026-09-24---01` is not edited and
stays `current` — deepening `--status-error` to match would also pull it within OKLab
ΔE ≈ 8.5 of `--status-neutral`, below that entry's chart-series separation floor, which
is a call for whoever owns `--status-*` to make, not this change.

## Open question accepted by merging this change

If "on-palette" is read strictly as "exact canonical Rose Pine hexes only," the deepened
tokens above do not qualify — the strict alternative maps light `--muted-foreground`,
`--primary` and `--destructive` all to `text`, losing text hierarchy and the brand hue
entirely. Merging this change accepts the correction-ladder reading above (every shipped
color traces to two canonical roles: the original and `text`) as satisfying "on-palette."
