import { describe, it, expect, vi, afterEach } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { useMobileBreakpoint } from "../useMobileBreakpoint";

function createMatchMediaMock(initialMatches: boolean) {
  const listeners: ((e: MediaQueryListEvent) => void)[] = [];
  const mql = {
    matches: initialMatches,
    media: "(max-width: 767px)",
    addEventListener: (_event: string, fn: (e: MediaQueryListEvent) => void) => {
      listeners.push(fn);
    },
    removeEventListener: (_event: string, fn: (e: MediaQueryListEvent) => void) => {
      const idx = listeners.indexOf(fn);
      if (idx >= 0) listeners.splice(idx, 1);
    },
    dispatchEvent: () => false,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
  };
  return {
    mql,
    listeners,
    mockFn: vi.fn().mockReturnValue(mql),
  };
}

describe("useMobileBreakpoint", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("returns true when matchMedia matches (mobile viewport)", () => {
    const mock = createMatchMediaMock(true);
    vi.stubGlobal("matchMedia", mock.mockFn);

    const { result } = renderHook(() => useMobileBreakpoint());
    expect(result.current).toBe(true);
  });

  it("returns false when matchMedia does not match (desktop viewport)", () => {
    const mock = createMatchMediaMock(false);
    vi.stubGlobal("matchMedia", mock.mockFn);

    const { result } = renderHook(() => useMobileBreakpoint());
    expect(result.current).toBe(false);
  });

  it("updates when change event fires on MediaQueryList", () => {
    const mock = createMatchMediaMock(false);
    vi.stubGlobal("matchMedia", mock.mockFn);

    const { result } = renderHook(() => useMobileBreakpoint());
    expect(result.current).toBe(false);

    // Simulate breakpoint change to mobile
    act(() => {
      mock.mql.matches = true;
      mock.listeners.forEach((fn) =>
        fn({ matches: true } as MediaQueryListEvent)
      );
    });

    expect(result.current).toBe(true);
  });

  it("cleans up listener on unmount", () => {
    const mock = createMatchMediaMock(false);
    vi.stubGlobal("matchMedia", mock.mockFn);

    const { unmount } = renderHook(() => useMobileBreakpoint());
    expect(mock.listeners.length).toBe(1);

    unmount();
    expect(mock.listeners.length).toBe(0);
  });
});
