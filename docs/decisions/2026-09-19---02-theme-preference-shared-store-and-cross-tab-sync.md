# A single in-app theme store, synced across tabs without OS live-tracking

## Status

current

## Context

Theme state was per-component: `useTheme` held its own `useState`, initialized by
reading the `theme` cookie on mount. The visible `.dark` class on the document is
global, but a component that reads the theme *value* (not just the class) — e.g. the
toast surface — held an independent copy, so toggling in one place did not reach
another already-mounted consumer until it remounted or the page reloaded. Nothing in
the app converged an already-open tab onto a preference changed elsewhere either.

`2026-09-18---03-first-visit-follows-device-color-scheme.md` established the
no-preference default (OS-resolved, seeded once, never live-tracked) and deliberately
reserved OS live-tracking and a selectable "system" mode as a separate, later decision.
This change does not revisit that: it addresses in-app consistency and cross-tab
propagation of a preference that already exists, once chosen.

## Decision

Theme state moves to a single module-level store (`src/lib/themeStore.ts`), consumed
via React's `useSyncExternalStore` — the same external-store shape already used for
the impersonation singleton (`src/lib/impersonation.tsx`). `useTheme` (`src/hooks/useTheme.ts`)
becomes a thin delegate; its exported `{ theme, toggle }` shape is unchanged, so
`ThemeToggle`, the toast surface, and the app layout needed no changes beyond sharing
state.

The store also propagates a changed preference to already-open surfaces:

1. **Same-origin tabs, live.** `toggle()` publishes on a `BroadcastChannel("theme")`;
   every other tab's store applies the new value and notifies its subscribers
   immediately, with no reload.
2. **Cross-subdomain, on refocus.** The store re-reads the `theme` cookie on
   `document`'s `visibilitychange` and applies it if changed. This is what lets a
   preference set on another ecosystem surface on a sibling subdomain (sharing the
   same root-domain cookie) reach an already-open, backgrounded app tab once it comes
   back into view — `storage` events don't fire for cookie writes, and polling was
   rejected as an unnecessary loop for an eventual-consistency requirement.
3. **No `prefers-color-scheme` listener.** Neither path re-resolves the OS. The store
   only consults `resolveInitialTheme()` (OS-derived) at first-subscribe init, exactly
   as before — live OS tracking stays out of scope per the prior decision.

The `theme` cookie's name, value set, and cross-subdomain attributes are unchanged;
this is purely an in-app state and propagation change.

## Alternatives considered

- **A React Context provider mounted at the app root.** Would also unify in-tab state,
  but needs explicit wiring at every entrypoint that composes this UI. The module
  store needs none — a consuming build inherits it automatically the same way it
  already inherits `useTheme`.
- **Make the cookie represent only an explicit choice** (absent ⇒ always follow the
  device), so a user who never toggled keeps following OS changes indefinitely.
  Rejected here: it reverses the seed-once persistence the prior decision established
  and changes *when* the shared cookie exists, which other consumers of that cookie
  were not audited against as part of this change. Left as a distinct, not-yet-made
  decision.
- **Poll the cookie on an interval** instead of `visibilitychange`. Rejected as an
  unnecessary background timer for a case that only needs to resolve when a tab
  becomes visible again.

## Consequences

Every in-tab consumer of `useTheme` now observes one live value: a toggle anywhere is
reflected everywhere in that tab immediately, and same-origin tabs converge live via
`BroadcastChannel`. A preference changed on a different subdomain reaches an
already-open tab only on refocus, not instantaneously — acceptable for perceived
consistency without a polling loop.

The cookie still stores a device-derived default when no explicit choice was ever
made, so "the remembered preference" cannot yet be distinguished from "what the OS
implied on first visit." That gap, and OS live-tracking, remain open — revisiting
either is a separate decision, not a revision of this one.
