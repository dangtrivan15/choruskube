import { useEffect, useRef, useCallback } from "react";

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export interface KeyboardShortcutHandlers {
  onCommandPalette: () => void;
  onNavigate: (path: string) => void;
  onToggleTheme: () => void;
  onShortcutsHelp: () => void;
  /**
   * Injected two-key sequences keyed by the joined sequence (e.g. `{ "go": fn, "gO": fn }`),
   * already bound to runtime context by the caller. Lets an extension entrypoint register its
   * own shortcuts without core naming them. The first character also becomes a sequence starter.
   */
  extraSequences?: Record<string, () => void>;
}

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const SEQUENCE_TIMEOUT_MS = 800;

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Returns true when the focused element is a text-entry control. */
function isInputFocused(): boolean {
  const el = document.activeElement;
  if (!el) return false;
  const tag = el.tagName;
  if (tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT") return true;
  if (
    (el as HTMLElement).isContentEditable ||
    (el as HTMLElement).contentEditable === "true"
  ) return true;
  return false;
}

/**
 * Detect macOS / iOS for display purposes.
 * Uses the modern `userAgentData` API with a `navigator.platform` fallback.
 * Cached at module level since the platform never changes during a session.
 */
function detectMac(): boolean {
  if (typeof navigator === "undefined") return false;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const uaData = (navigator as any).userAgentData as
    | { platform?: string }
    | undefined;
  if (uaData?.platform) {
    return /mac/i.test(uaData.platform);
  }
  return /Mac|iPhone|iPad|iPod/.test(navigator.platform);
}

let _isMacCached: boolean | null = null;

export function isMac(): boolean {
  if (_isMacCached === null) {
    _isMacCached = detectMac();
  }
  return _isMacCached;
}

/** @internal — exposed only for tests to reset the cached value */
export function _resetIsMacCache(): void {
  _isMacCached = null;
}

// ---------------------------------------------------------------------------
// Hook
// ---------------------------------------------------------------------------

export function useKeyboardShortcuts(handlers: KeyboardShortcutHandlers) {
  const pendingKeyRef = useRef<string | null>(null);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const clearPending = useCallback(() => {
    pendingKeyRef.current = null;
    if (timerRef.current !== null) {
      clearTimeout(timerRef.current);
      timerRef.current = null;
    }
  }, []);

  useEffect(() => {
    function handleKeyDown(e: KeyboardEvent) {
      // ---------------------------------------------------------------
      // Ctrl+K / Cmd+K — always active, even in inputs
      // ---------------------------------------------------------------
      if (e.key === "k" && (e.metaKey || e.ctrlKey) && !e.shiftKey && !e.altKey) {
        e.preventDefault();
        clearPending();
        handlers.onCommandPalette();
        return;
      }

      // For all other shortcuts, skip when an input is focused
      if (isInputFocused()) return;

      // Ignore events with modifier keys (except shift, used for capitalised
      // sequence keys like `g O` and the single-key `?`).
      if (e.ctrlKey || e.metaKey || e.altKey) return;

      // Standalone modifier keydowns (e.g. Shift held before O) must not be
      // treated as a sequence's second key — otherwise `g` + Shift+O would be
      // consumed as `g` + Shift, clearing the pending `g` before O arrives.
      if (
        e.key === "Shift" ||
        e.key === "Control" ||
        e.key === "Alt" ||
        e.key === "Meta"
      ) {
        return;
      }

      const key = e.key;

      // ---------------------------------------------------------------
      // Two-key sequences: g → {r,a,t,m} and t → t
      // ---------------------------------------------------------------
      if (pendingKeyRef.current) {
        const sequence = `${pendingKeyRef.current}${key}`;
        clearPending();

        switch (sequence) {
          case "gr":
            e.preventDefault();
            handlers.onNavigate("/runs");
            return;
          case "ga":
            e.preventDefault();
            handlers.onNavigate("/approvals");
            return;
          case "gt":
            e.preventDefault();
            handlers.onNavigate("/templates");
            return;
          case "gn":
            e.preventDefault();
            handlers.onNavigate("/analytics");
            return;
          case "gm":
            e.preventDefault();
            handlers.onNavigate("/roadmap");
            return;
          case "gg":
            e.preventDefault();
            handlers.onNavigate("/git-repos");
            return;
          case "tt":
            e.preventDefault();
            handlers.onToggleTheme();
            return;
        }
        // Injected extension sequences — core does not name these.
        const extra = handlers.extraSequences?.[sequence];
        if (extra) {
          e.preventDefault();
          extra();
          return;
        }
        // Unrecognized sequence — fall through to check single-key below
      }

      // ---------------------------------------------------------------
      // First key of a sequence (core starters plus any injected ones)
      // ---------------------------------------------------------------
      const extraStarters = Object.keys(handlers.extraSequences ?? {}).map((s) => s[0]);
      if (key === "g" || key === "t" || extraStarters.includes(key)) {
        pendingKeyRef.current = key;
        timerRef.current = setTimeout(() => {
          pendingKeyRef.current = null;
          timerRef.current = null;
        }, SEQUENCE_TIMEOUT_MS);
        return;
      }

      // ---------------------------------------------------------------
      // Single-key shortcuts
      // ---------------------------------------------------------------
      if (key === "?") {
        e.preventDefault();
        handlers.onShortcutsHelp();
        return;
      }
    }

    document.addEventListener("keydown", handleKeyDown);
    return () => {
      document.removeEventListener("keydown", handleKeyDown);
      clearPending();
    };
  }, [handlers, clearPending]);
}
