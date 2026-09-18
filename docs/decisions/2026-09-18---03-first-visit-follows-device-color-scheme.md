# First-visit theme follows the OS color-scheme; a saved preference stays authoritative

## Status

current

## Context

The theme cookie's no-preference default was hardcoded to light, so a visitor whose
browser had no saved `theme` cookie always saw light regardless of their OS's
`prefers-color-scheme` setting. Two independent code paths resolve the theme on a
page load: a pre-paint inline script in `index.html` (runs before hydration to avoid
a flash of the wrong theme) and the React theme hook (`useTheme`/`theme.ts`), which
also persists whatever it resolves back into the shared, cross-subdomain `theme`
cookie on every mount.

## Decision

When no *valid* saved preference exists — the cookie is absent, holds a value other
than exactly `dark`/`light`, or the cookie read itself throws — the initial theme is
resolved from `window.matchMedia("(prefers-color-scheme: dark)")` instead of a fixed
light default. A saved preference, once present, always overrides the OS setting.

This has three parts:

1. **The OS resolves only the no-preference case.** A saved preference is never
   overridden by the OS, so returning visitors — who already carry a preference —
   are unaffected by this change.
2. **The OS-derived value is seeded once, not live-tracked.** The existing
   persist-on-mount behavior writes the OS-resolved value into the shared cookie
   immediately, so it becomes a durable choice from the first visit. The app does
   not add a `matchMedia` change listener and does not follow later OS changes;
   there is no selectable "system/auto" mode.
3. **The OS check is duplicated in the pre-paint script and in `theme.ts`,
   guarded by a disk-reading sync test.** The pre-paint script must run before any
   module loads, so it cannot import shared code; the same no-preference branch
   (`resolveInitialTheme()` in `theme.ts`, and the equivalent inline logic in
   `index.html`) is written in both places. `theme-sync.test.ts` reads both files
   from disk and asserts both reference `prefers-color-scheme`, turning drift
   between the two into a build-time failure instead of a runtime flash.

## Alternatives considered

- **A selectable "system/auto" mode with live OS-change tracking.** Would keep the
  app following OS changes after the first visit and let a user explicitly opt into
  "follow system." Rejected as a larger, separate feature: it introduces a
  three-state model and a `matchMedia` listener, well beyond a first-visit default.
- **Resolve the default server-side** (e.g. a response header or account setting)
  instead of client-side. Rejected because the OS color-scheme preference is only
  observable in the browser; server-side resolution cannot see it.
- **Extract the no-preference check into a module both the pre-paint script and
  React import**, instead of duplicating it. Rejected because the pre-paint script
  runs before any bundle loads and cannot import shared code — the duplication is
  inherent to the flash-avoidance requirement, not a convenience shortcut.
- **Drop the pre-paint script and accept a flash of the wrong theme** on first
  paint. Rejected as a UX regression that the sync-test-guarded duplication avoids
  at low cost.

## Consequences

A genuinely first-time (or cookie-cleared) visitor now opens in the theme that
matches their device instead of always in light; every other visitor is unaffected,
since a saved preference always wins. The shared `theme` cookie's name, values, and
attributes are unchanged, and no new stored state or network call is introduced.

The tradeoff is that the app does not track OS changes after the first visit — a
user who changes their OS theme later keeps whatever was seeded (or subsequently
toggled), and there is no user-facing "follow system" option. This is intentional
per the scoping above; introducing live-tracking or a selectable system mode later
is a separate decision, not a revision of this one.
