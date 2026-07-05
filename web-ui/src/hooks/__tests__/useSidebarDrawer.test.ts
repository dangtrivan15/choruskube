import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { createTestHookWrapper } from "@/__tests__/test-utils";
import { useSidebarDrawer } from "../useSidebarDrawer";
import { useNavigate } from "react-router";

describe("useSidebarDrawer", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("initial state is closed", () => {
    const { wrapper } = createTestHookWrapper();
    const { result } = renderHook(() => useSidebarDrawer(), { wrapper });

    expect(result.current.isOpen).toBe(false);
  });

  it("open() sets isOpen to true", () => {
    const { wrapper } = createTestHookWrapper();
    const { result } = renderHook(() => useSidebarDrawer(), { wrapper });

    act(() => {
      result.current.open();
    });

    expect(result.current.isOpen).toBe(true);
  });

  it("close() sets isOpen to false", () => {
    const { wrapper } = createTestHookWrapper();
    const { result } = renderHook(() => useSidebarDrawer(), { wrapper });

    act(() => {
      result.current.open();
    });
    expect(result.current.isOpen).toBe(true);

    act(() => {
      result.current.close();
    });
    expect(result.current.isOpen).toBe(false);
  });

  it("toggle() toggles state", () => {
    const { wrapper } = createTestHookWrapper();
    const { result } = renderHook(() => useSidebarDrawer(), { wrapper });

    act(() => {
      result.current.toggle();
    });
    expect(result.current.isOpen).toBe(true);

    act(() => {
      result.current.toggle();
    });
    expect(result.current.isOpen).toBe(false);
  });

  it("closes drawer on route change", () => {
    const { wrapper } = createTestHookWrapper(["/runs"]);

    // Render both hooks in the same router context so navigate triggers
    // a location change that useSidebarDrawer observes.
    const { result } = renderHook(
      () => ({
        drawer: useSidebarDrawer(),
        navigate: useNavigate(),
      }),
      { wrapper }
    );

    act(() => {
      result.current.drawer.open();
    });
    expect(result.current.drawer.isOpen).toBe(true);

    act(() => {
      result.current.navigate("/approvals");
    });
    expect(result.current.drawer.isOpen).toBe(false);
  });
});
