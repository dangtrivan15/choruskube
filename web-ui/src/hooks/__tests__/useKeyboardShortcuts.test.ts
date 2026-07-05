import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { renderHook } from "@testing-library/react";
import {
  useKeyboardShortcuts,
  type KeyboardShortcutHandlers,
} from "@/hooks/useKeyboardShortcuts";

function createHandlers(): KeyboardShortcutHandlers {
  return {
    onCommandPalette: vi.fn(),
    onNavigate: vi.fn(),
    onToggleTheme: vi.fn(),
    onShortcutsHelp: vi.fn(),
    extraSequences: { go: vi.fn(), gO: vi.fn() },
  };
}

function fireKey(
  key: string,
  opts: Partial<KeyboardEventInit> = {},
  target?: HTMLElement
) {
  const event = new KeyboardEvent("keydown", {
    key,
    bubbles: true,
    cancelable: true,
    ...opts,
  });
  (target ?? document).dispatchEvent(event);
  return event;
}

describe("useKeyboardShortcuts", () => {
  let handlers: KeyboardShortcutHandlers;

  beforeEach(() => {
    vi.useFakeTimers();
    handlers = createHandlers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  // ---------------------------------------------------------------
  // Ctrl+K / Cmd+K
  // ---------------------------------------------------------------

  it("opens command palette on Ctrl+K", () => {
    renderHook(() => useKeyboardShortcuts(handlers));
    fireKey("k", { ctrlKey: true });
    expect(handlers.onCommandPalette).toHaveBeenCalledTimes(1);
  });

  it("opens command palette on Meta+K (Cmd+K)", () => {
    renderHook(() => useKeyboardShortcuts(handlers));
    fireKey("k", { metaKey: true });
    expect(handlers.onCommandPalette).toHaveBeenCalledTimes(1);
  });

  it("does NOT open palette on plain 'k'", () => {
    renderHook(() => useKeyboardShortcuts(handlers));
    fireKey("k");
    expect(handlers.onCommandPalette).not.toHaveBeenCalled();
  });

  it("Ctrl+K works even when an input is focused", () => {
    const input = document.createElement("input");
    document.body.appendChild(input);
    input.focus();

    renderHook(() => useKeyboardShortcuts(handlers));
    fireKey("k", { ctrlKey: true });
    expect(handlers.onCommandPalette).toHaveBeenCalledTimes(1);

    document.body.removeChild(input);
  });

  // ---------------------------------------------------------------
  // Two-key sequences
  // ---------------------------------------------------------------

  it("navigates to /runs on g → r", () => {
    renderHook(() => useKeyboardShortcuts(handlers));
    fireKey("g");
    fireKey("r");
    expect(handlers.onNavigate).toHaveBeenCalledWith("/runs");
  });

  it("navigates to /approvals on g → a", () => {
    renderHook(() => useKeyboardShortcuts(handlers));
    fireKey("g");
    fireKey("a");
    expect(handlers.onNavigate).toHaveBeenCalledWith("/approvals");
  });

  it("navigates to /templates on g → t", () => {
    renderHook(() => useKeyboardShortcuts(handlers));
    fireKey("g");
    fireKey("t");
    expect(handlers.onNavigate).toHaveBeenCalledWith("/templates");
  });

  it("navigates to /analytics on g → n", () => {
    renderHook(() => useKeyboardShortcuts(handlers));
    fireKey("g");
    fireKey("n");
    expect(handlers.onNavigate).toHaveBeenCalledWith("/analytics");
  });

  it("navigates to /roadmap on g → m", () => {
    renderHook(() => useKeyboardShortcuts(handlers));
    fireKey("g");
    fireKey("m");
    expect(handlers.onNavigate).toHaveBeenCalledWith("/roadmap");
  });

  it("fires the injected `g o` sequence", () => {
    renderHook(() => useKeyboardShortcuts(handlers));
    fireKey("g");
    fireKey("o");
    expect(handlers.extraSequences!.go).toHaveBeenCalledTimes(1);
    expect(handlers.onNavigate).not.toHaveBeenCalled();
  });

  it("fires the injected `g O` sequence on g → Shift+O", () => {
    renderHook(() => useKeyboardShortcuts(handlers));
    fireKey("g");
    // Browsers fire a standalone Shift keydown before the O event when the
    // user holds Shift — the hook must ignore it so the pending `g` survives.
    fireKey("Shift", { shiftKey: true });
    fireKey("O", { shiftKey: true });
    expect(handlers.extraSequences!.gO).toHaveBeenCalledTimes(1);
    expect(handlers.extraSequences!.go).not.toHaveBeenCalled();
    expect(handlers.onNavigate).not.toHaveBeenCalled();
  });

  it("distinguishes g+o from g+Shift+O (case-sensitive sequence)", () => {
    renderHook(() => useKeyboardShortcuts(handlers));
    fireKey("g");
    fireKey("o"); // lowercase, no shift
    expect(handlers.extraSequences!.go).toHaveBeenCalledTimes(1);
    expect(handlers.extraSequences!.gO).not.toHaveBeenCalled();
  });

  it("still navigates to /git-repos on g → g (regression)", () => {
    renderHook(() => useKeyboardShortcuts(handlers));
    fireKey("g");
    fireKey("g");
    expect(handlers.onNavigate).toHaveBeenCalledWith("/git-repos");
    expect(handlers.extraSequences!.go).not.toHaveBeenCalled();
  });

  it("toggles theme on t → t", () => {
    renderHook(() => useKeyboardShortcuts(handlers));
    fireKey("t");
    fireKey("t");
    expect(handlers.onToggleTheme).toHaveBeenCalledTimes(1);
  });

  it("does NOT fire sequence after timeout", () => {
    renderHook(() => useKeyboardShortcuts(handlers));
    fireKey("g");
    vi.advanceTimersByTime(900); // > 800ms timeout
    fireKey("r");
    expect(handlers.onNavigate).not.toHaveBeenCalled();
  });

  it("ignores unrecognized second key in sequence", () => {
    renderHook(() => useKeyboardShortcuts(handlers));
    fireKey("g");
    fireKey("z"); // not mapped
    expect(handlers.onNavigate).not.toHaveBeenCalled();
  });

  // ---------------------------------------------------------------
  // Single-key shortcuts
  // ---------------------------------------------------------------

  it("opens shortcuts help on ?", () => {
    renderHook(() => useKeyboardShortcuts(handlers));
    fireKey("?");
    expect(handlers.onShortcutsHelp).toHaveBeenCalledTimes(1);
  });

  // ---------------------------------------------------------------
  // Focus conflict avoidance
  // ---------------------------------------------------------------

  it("ignores single-key shortcuts when an input is focused", () => {
    const input = document.createElement("input");
    document.body.appendChild(input);
    input.focus();

    renderHook(() => useKeyboardShortcuts(handlers));
    fireKey("?");
    expect(handlers.onShortcutsHelp).not.toHaveBeenCalled();

    document.body.removeChild(input);
  });

  it("ignores sequence shortcuts when a textarea is focused", () => {
    const textarea = document.createElement("textarea");
    document.body.appendChild(textarea);
    textarea.focus();

    renderHook(() => useKeyboardShortcuts(handlers));
    fireKey("g");
    fireKey("r");
    expect(handlers.onNavigate).not.toHaveBeenCalled();

    document.body.removeChild(textarea);
  });

  it("ignores shortcuts when contentEditable element is focused", () => {
    const div = document.createElement("div");
    div.contentEditable = "true";
    div.tabIndex = 0; // happy-dom requires tabIndex for focus
    document.body.appendChild(div);
    div.focus();

    renderHook(() => useKeyboardShortcuts(handlers));
    fireKey("?");
    expect(handlers.onShortcutsHelp).not.toHaveBeenCalled();

    document.body.removeChild(div);
  });

  // ---------------------------------------------------------------
  // Modifier key filtering
  // ---------------------------------------------------------------

  it("ignores keys with Alt modifier", () => {
    renderHook(() => useKeyboardShortcuts(handlers));
    fireKey("?", { altKey: true });
    expect(handlers.onShortcutsHelp).not.toHaveBeenCalled();
  });

  // ---------------------------------------------------------------
  // Cleanup
  // ---------------------------------------------------------------

  it("removes event listener on unmount", () => {
    const { unmount } = renderHook(() => useKeyboardShortcuts(handlers));
    unmount();

    fireKey("?");
    expect(handlers.onShortcutsHelp).not.toHaveBeenCalled();
  });
});
