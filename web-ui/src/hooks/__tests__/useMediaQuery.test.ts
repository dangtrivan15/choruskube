import { describe, it, expect, vi, afterEach } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { useMediaQuery } from "../useMediaQuery";

function createMatchMediaMock(initialMatches: boolean) {
  const listeners: ((e: MediaQueryListEvent) => void)[] = [];
  const mql = {
    matches: initialMatches,
    media: "(min-width: 1024px)",
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

describe("useMediaQuery", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("returns true on initial match", () => {
    const mock = createMatchMediaMock(true);
    vi.stubGlobal("matchMedia", mock.mockFn);

    const { result } = renderHook(() => useMediaQuery("(min-width: 1024px)"));
    expect(result.current).toBe(true);
  });

  it("returns false when the query does not match", () => {
    const mock = createMatchMediaMock(false);
    vi.stubGlobal("matchMedia", mock.mockFn);

    const { result } = renderHook(() => useMediaQuery("(min-width: 1024px)"));
    expect(result.current).toBe(false);
  });

  it("updates on a change event", () => {
    const mock = createMatchMediaMock(false);
    vi.stubGlobal("matchMedia", mock.mockFn);

    const { result } = renderHook(() => useMediaQuery("(min-width: 1024px)"));
    expect(result.current).toBe(false);

    act(() => {
      mock.mql.matches = true;
      mock.listeners.forEach((fn) => fn({ matches: true } as MediaQueryListEvent));
    });

    expect(result.current).toBe(true);
  });

  it("removes its listener on unmount", () => {
    const mock = createMatchMediaMock(false);
    vi.stubGlobal("matchMedia", mock.mockFn);

    const { unmount } = renderHook(() => useMediaQuery("(min-width: 1024px)"));
    expect(mock.listeners.length).toBe(1);

    unmount();
    expect(mock.listeners.length).toBe(0);
  });
});
