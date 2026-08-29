# Original light theme = canonical upstream Rose Pine Dawn

## Status

current

## Context

The core app's light theme keeps Rose Pine's accent *hues* but renders them over custom
*neutrals* (background, foreground, card, secondary/muted/accent, sidebar-accent, and
ink-alpha borders), plus a decorative glass/gradient/texture layer that upstream Rose
Pine has no concept of. A comment in `web-ui/src/index.css` points at another product
surface as the "source of truth" for that gradient, but that surface's own palette is
itself off-canonical — so following the comment doesn't settle the question, it just
moves it. The repo's clone is shallow (one commit), so git history cannot arbitrate which
look came first or was "original."

Before any restoration work touches theme code, the target needs to be a single,
citable, reviewable authority — not a self-reference between two custom-neutral looks
that both merely borrow Rose Pine's hues.

## Decision

"Original light theme" is defined as the published, upstream **Rose Pine Dawn** palette
(rosepinetheme.com), taken at face value with no local substitutions:

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

The deviation catalog (`web-ui/docs/light-theme-rose-pine-audit.md`) measures the core
app's current light theme against this table.

## Alternatives considered

- **Adopt the existing brand skin as the standard.** The product already ships a "Dawn"
  look that keeps Rose Pine's hues but swaps in a custom darker ink and custom
  near-white paper, plus brand-specific gradients, on more than one product surface.
  Treating that skin as "original" would mean far less of the current app counts as a
  deviation, but it would also codify a look that itself is not attested anywhere
  outside this product's own surfaces — there is no external spec to check it against,
  and it embeds a deliberate contrast/brand call (the darker ink) that this task is not
  positioned to relitigate. Left open for the review gate — see the open question in the
  PR description for this change.
- **Treat another product surface's declared palette as authoritative.** One surface's
  own theme comment already claims to be the "source of truth" for the app's gradient.
  Rejected because that surface's declared palette is itself off-canonical relative to
  Rose Pine Dawn, so anchoring to it would just relocate the ambiguity instead of
  resolving it.

## Consequences

Canonical Dawn is external, versioned by an upstream project, and checkable by anyone
with the URL — a decision record that cites it is reviewable on its own terms. The
tradeoff is that it is the larger gap to close: the app's neutrals and its whole
decorative glass/gradient/texture layer become catalogued deviations rather than
already-compliant. Restoring to this target may also reopen a deliberate cross-surface
brand decision (the custom darker ink), which is why that alternative is recorded above
rather than silently dropped, and why merging this decision is called out as accepting
canonical Dawn over the brand skin by default (see the open question raised alongside
this change).

Accent hues already match canonical Dawn; only the neutrals and the decorative layer are
new deviations to close in a later restoration task. This decision changes no theme
source — see `web-ui/docs/light-theme-rose-pine-audit.md` for the full deviation catalog
that a later restoration task will use as its checklist.
