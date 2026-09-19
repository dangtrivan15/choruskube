import { type Theme, THEME_COOKIE, readStoredTheme, resolveInitialTheme, setCookie } from "@/lib/theme";

/**
 * Single in-app source of truth for the effective theme, consumed via
 * useSyncExternalStore (see useTheme.ts). Owns cookie I/O, the .dark class,
 * and cross-tab/cross-surface convergence (BroadcastChannel + visibilitychange),
 * so every consumer in the tab observes the same value instead of each
 * `useTheme` caller resolving (and possibly diverging from) its own copy.
 *
 * Deliberately NOT: a `prefers-color-scheme` change listener. The OS is only
 * ever consulted for the no-preference case (resolveInitialTheme, on init);
 * following later OS changes live is a separate, not-yet-made decision.
 */

const CHANNEL_NAME = "theme";

type Listener = () => void;

// Module-level singleton. `current` is null when there are no subscribers —
// this is what lets the next first-subscribe re-resolve from whatever the
// cookie holds then, instead of caching the very first resolution forever.
let current: Theme | null = null;
const listeners = new Set<Listener>();
let channel: BroadcastChannel | null = null;

function applyClass(theme: Theme): void {
  document.documentElement.classList.toggle("dark", theme === "dark");
}

function notify(): void {
  for (const listener of listeners) listener();
}

/** Applies + records a theme and notifies subscribers, but only if it actually changed —
 * a same-value BroadcastChannel echo or visibilitychange re-read must not re-render. */
function setCurrent(theme: Theme): void {
  if (theme === current) return;
  current = theme;
  applyClass(theme);
  notify();
}

function handleChannelMessage(event: MessageEvent<unknown>): void {
  if (event.data === "dark" || event.data === "light") setCurrent(event.data);
}

function handleVisibilityChange(): void {
  if (document.visibilityState !== "visible") return;
  const stored = readStoredTheme();
  if (stored) setCurrent(stored);
}

function attachListeners(): void {
  // Feature-detect the bare identifier, not window.BroadcastChannel: some
  // environments (e.g. this repo's happy-dom test setup) expose it as a
  // global without also attaching it to the window object.
  if (typeof BroadcastChannel !== "undefined") {
    channel = new BroadcastChannel(CHANNEL_NAME);
    channel.onmessage = handleChannelMessage;
  }
  if (typeof document !== "undefined") {
    document.addEventListener("visibilitychange", handleVisibilityChange);
  }
}

function detachListeners(): void {
  channel?.close();
  channel = null;
  if (typeof document !== "undefined") {
    document.removeEventListener("visibilitychange", handleVisibilityChange);
  }
}

/**
 * Lazy init on first subscribe: resolve from the cookie (or the OS default when
 * absent/invalid), apply the .dark class, and seed-once — write the resolved
 * value back so it becomes a durable preference, matching the pre-existing
 * persist-on-mount behavior this store replaces.
 */
function init(): void {
  const stored = readStoredTheme();
  current = stored ?? resolveInitialTheme();
  applyClass(current);
  if (!stored) setCookie(THEME_COOKIE, current);
  attachListeners();
}

function subscribe(listener: Listener): () => void {
  if (listeners.size === 0) init();
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
    if (listeners.size === 0) {
      detachListeners();
      current = null; // next first-subscribe re-resolves from the live cookie
    }
  };
}

// Must stay pure (no cookie writes, no class toggles) — those are side effects
// of init()/toggle()/the listeners above, not of reading the snapshot. Reading
// through to the cookie when there is no live `current` lets a pre-subscribe
// render (React calls getSnapshot before the mount effect that subscribes)
// see the same value init() will shortly commit.
function getSnapshot(): Theme {
  return current ?? readStoredTheme() ?? resolveInitialTheme();
}

function toggle(): void {
  const next: Theme = getSnapshot() === "dark" ? "light" : "dark";
  setCookie(THEME_COOKIE, next);
  setCurrent(next);
  channel?.postMessage(next);
}

export const themeStore = { subscribe, getSnapshot, toggle };
