import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { useResizable } from "@/hooks/useResizable";

describe("useResizable", () => {
  let getItemMock: ReturnType<typeof vi.fn>;
  let setItemMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    getItemMock = vi.fn();
    setItemMock = vi.fn();
    Object.defineProperty(globalThis, "localStorage", {
      value: {
        getItem: getItemMock,
        setItem: setItemMock,
        removeItem: vi.fn(),
        clear: vi.fn(),
        length: 0,
        key: vi.fn(),
      },
      writable: true,
      configurable: true,
    });
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("returns defaultWidth when no stored value exists", () => {
    getItemMock.mockReturnValue(null);
    const { result } = renderHook(() =>
      useResizable({
        side: "right",
        defaultWidth: 224,
        minWidth: 160,
        maxWidth: 400,
        storageKey: "test-width",
      }),
    );

    expect(result.current.width).toBe(224);
    expect(result.current.isDragging).toBe(false);
  });

  it("reads stored width from localStorage", () => {
    getItemMock.mockReturnValue("300");
    const { result } = renderHook(() =>
      useResizable({
        side: "right",
        defaultWidth: 224,
        minWidth: 160,
        maxWidth: 400,
        storageKey: "test-width",
      }),
    );

    expect(result.current.width).toBe(300);
  });

  it("clamps stored width to minWidth", () => {
    getItemMock.mockReturnValue("50");
    const { result } = renderHook(() =>
      useResizable({
        side: "right",
        defaultWidth: 224,
        minWidth: 160,
        maxWidth: 400,
        storageKey: "test-width",
      }),
    );

    expect(result.current.width).toBe(160);
  });

  it("clamps stored width to maxWidth", () => {
    getItemMock.mockReturnValue("999");
    const { result } = renderHook(() =>
      useResizable({
        side: "right",
        defaultWidth: 224,
        minWidth: 160,
        maxWidth: 400,
        storageKey: "test-width",
      }),
    );

    expect(result.current.width).toBe(400);
  });

  it("falls back to defaultWidth for invalid stored value", () => {
    getItemMock.mockReturnValue("not-a-number");
    const { result } = renderHook(() =>
      useResizable({
        side: "right",
        defaultWidth: 224,
        minWidth: 160,
        maxWidth: 400,
        storageKey: "test-width",
      }),
    );

    expect(result.current.width).toBe(224);
  });

  it("persists width to localStorage", () => {
    getItemMock.mockReturnValue(null);
    renderHook(() =>
      useResizable({
        side: "right",
        defaultWidth: 224,
        minWidth: 160,
        maxWidth: 400,
        storageKey: "test-width",
      }),
    );

    expect(setItemMock).toHaveBeenCalledWith("test-width", "224");
  });

  it("does not access localStorage when storageKey is omitted", () => {
    const { result } = renderHook(() =>
      useResizable({
        side: "right",
        defaultWidth: 224,
        minWidth: 160,
        maxWidth: 400,
      }),
    );

    expect(result.current.width).toBe(224);
    expect(getItemMock).not.toHaveBeenCalled();
    expect(setItemMock).not.toHaveBeenCalled();
  });

  it("handles right-side drag: moving right increases width", () => {
    getItemMock.mockReturnValue(null);
    const { result } = renderHook(() =>
      useResizable({
        side: "right",
        defaultWidth: 224,
        minWidth: 160,
        maxWidth: 400,
        storageKey: "test-width",
      }),
    );

    // Simulate pointerdown
    const fakeTarget = document.createElement("div");
    act(() => {
      result.current.handlePointerDown({
        clientX: 200,
        pointerId: 1,
        target: fakeTarget,
        preventDefault: () => {},
      } as unknown as React.PointerEvent);
    });

    expect(result.current.isDragging).toBe(true);

    // Simulate pointermove: move 50px to the right
    act(() => {
      document.dispatchEvent(
        new PointerEvent("pointermove", { clientX: 250 }),
      );
    });

    expect(result.current.width).toBe(274); // 224 + 50

    // Simulate pointerup
    act(() => {
      document.dispatchEvent(new PointerEvent("pointerup"));
    });

    expect(result.current.isDragging).toBe(false);
  });

  it("handles left-side drag: moving left increases width", () => {
    getItemMock.mockReturnValue(null);
    const { result } = renderHook(() =>
      useResizable({
        side: "left",
        defaultWidth: 320,
        minWidth: 240,
        maxWidth: 600,
        storageKey: "test-detail",
      }),
    );

    const fakeTarget = document.createElement("div");
    act(() => {
      result.current.handlePointerDown({
        clientX: 500,
        pointerId: 1,
        target: fakeTarget,
        preventDefault: () => {},
      } as unknown as React.PointerEvent);
    });

    // Move 80px to the left — should increase width
    act(() => {
      document.dispatchEvent(
        new PointerEvent("pointermove", { clientX: 420 }),
      );
    });

    expect(result.current.width).toBe(400); // 320 + 80
  });

  it("clamps width during drag to min/max", () => {
    getItemMock.mockReturnValue(null);
    const { result } = renderHook(() =>
      useResizable({
        side: "right",
        defaultWidth: 224,
        minWidth: 160,
        maxWidth: 400,
        storageKey: "test-width",
      }),
    );

    const fakeTarget = document.createElement("div");
    act(() => {
      result.current.handlePointerDown({
        clientX: 200,
        pointerId: 1,
        target: fakeTarget,
        preventDefault: () => {},
      } as unknown as React.PointerEvent);
    });

    // Move 500px right — should clamp to maxWidth
    act(() => {
      document.dispatchEvent(
        new PointerEvent("pointermove", { clientX: 700 }),
      );
    });

    expect(result.current.width).toBe(400);

    // Move 500px left — should clamp to minWidth
    act(() => {
      document.dispatchEvent(
        new PointerEvent("pointermove", { clientX: -300 }),
      );
    });

    expect(result.current.width).toBe(160);

    // Clean up
    act(() => {
      document.dispatchEvent(new PointerEvent("pointerup"));
    });
  });

  it("cleans up document event listeners on unmount", () => {
    getItemMock.mockReturnValue(null);
    const addSpy = vi.spyOn(document, "addEventListener");
    const removeSpy = vi.spyOn(document, "removeEventListener");

    const { unmount } = renderHook(() =>
      useResizable({
        side: "right",
        defaultWidth: 224,
        minWidth: 160,
        maxWidth: 400,
      }),
    );

    unmount();

    // Should have called removeEventListener for cleanup
    const removeCallTypes = removeSpy.mock.calls.map((c) => c[0]);
    expect(removeCallTypes).toContain("pointermove");
    expect(removeCallTypes).toContain("pointerup");

    addSpy.mockRestore();
    removeSpy.mockRestore();
  });
});
