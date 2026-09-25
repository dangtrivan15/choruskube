# A shared status-tone vocabulary, sentence-ink rule, and toast bridge

## Status

current

## Context

Status indicators (badges, dots, banners, toasts) each carried their own hand-written
class strings, and different call sites picked different tones for the same underlying
condition or used different opacities for the same tone. Banners rendered their sentence
text in the tone color on a same-tone tint — for warning (gold), that measures close to
2:1 contrast in light mode, which is hard to read. The toast library's `richColors`
feature ships its own green/blue/amber/red palette, unrelated to the app's tokens, so a
toast next to a status badge for the same event could show a different color for it.

## Decision

`src/lib/statusColors.ts` defines six tones — `success`, `error`, `info`, `warning`,
`accent`, `neutral` — and one class table (`STATUS_TONE_CLASSES`) with a `badge`, `bg`,
`border`, `text`, `dot` and `callout` recipe per tone. Every recipe is a complete string
literal, never assembled with a template, because Tailwind only generates the classes it
finds written out in full in source — an interpolated class name compiles but renders
with no color. The tones mean:

- **success** — healthy or done.
- **error** — broken, rejected or failed.
- **warning** — the viewer must act now, or something is degraded.
- **info** — in progress, or waiting on someone else.
- **accent** — held or paused by a person.
- **neutral** — not started, not configured, or ended without an outcome.

`StatusDot` (a decorative solid-color dot) and `StatusCallout` (a tinted, bordered
block) are the two primitives built on the table. `StatusCallout`'s body text always
renders in the foreground color — never the tone color — with the tone carried instead
by the tint, the border and a leading per-tone icon. Badges and dots keep tone-colored
labels; those are short, not sentences, so the contrast tradeoff differs.

`text-destructive` stays reserved for destructive actions (buttons, confirmation copy)
and short inline validation/mutation-error lines — not for status indicators, which use
the tone table's `error` entry instead. This split is safe because a pinned test asserts
`--destructive` and `--status-error` resolve to the same value in both themes, so the two
names can never visually drift apart even though they're chosen for different reasons.

The toast library injects its own stylesheet at runtime, unlayered. An unlayered CSS rule
always wins over a layered one regardless of specificity, so a bridge written inside a
Tailwind `@layer` would silently lose to the library's palette. `index.css` sets the
library's per-type color variables (background/border as a `color-mix()` tint of the
matching `--status-*` token over `--popover`, text as `--popover-foreground`, icon color
as the raw token) in a block that is itself unlayered and uses a selector more specific
than the library's own, so it wins regardless of stylesheet load order.

A scanner (`src/__tests__/palette-hygiene.ts`) flags raw Tailwind palette utilities,
hex literals, and `rgb`/`hsl`/`oklch`-family color functions anywhere under `src/`,
against a small, explicit allow-list of pre-existing exceptions. One test runs it over
the whole tree, so any downstream build that composes this web-ui with its own source on
top inherits the same scan over the combined tree for free, without a second definition
of "off-brand" to keep in sync.

## Alternatives considered

- **Keep hand-written class strings and add only the scanner.** The scanner catches raw
  palette values but not recipe drift — two call sites both correctly using
  `--status-warning` but at different opacities or border widths would still pass.
- **A full `StatusBadge` component family.** Changes more markup than the fix needs; the
  existing `Badge` component plus a class string from the table already works for badges.
- **Turn off `richColors` and style each toast type with utility classes.** The library's
  unlayered CSS beats Tailwind's layered utilities whatever their specificity, so every
  utility class would need `!important` to win — worse than one small unlayered block.
- **Darken the tone tokens themselves so tone-colored sentence text becomes readable.**
  That is a token-value change, which would recolor every consumer of that token,
  including badges outside the status-indicator surfaces this change touches. Left to
  whichever future change owns contrast token values.

## Consequences

A state's tone and class recipe now have exactly one place to change, and the scanner
means a new raw color anywhere under `src/` — in this repo or a downstream composed
build — fails a test instead of shipping. The toast bridge depends on the library's
internal CSS variable names, which are not a guaranteed-stable API; a rename would
silently fall back to the library's own palette. `theme-tokens.test.ts` only pins this
repo's side (the variable names the bridge sets, and that the block is unlayered); a
rename on the library's side is caught by the toast test in
`web-ui/e2e/specs/status-colors.spec.ts`, which checks a rendered toast's colors.
