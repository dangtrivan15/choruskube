import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import ResizeHandle from "@/components/ui/ResizeHandle";

describe("ResizeHandle", () => {
  it("renders a vertical separator element", () => {
    render(
      <ResizeHandle side="right" isDragging={false} onPointerDown={vi.fn()} />,
    );

    const handle = screen.getByRole("separator");
    expect(handle).toBeInTheDocument();
    expect(handle).toHaveAttribute("aria-orientation", "vertical");
  });

  it("has col-resize cursor class", () => {
    render(
      <ResizeHandle side="right" isDragging={false} onPointerDown={vi.fn()} />,
    );

    const handle = screen.getByRole("separator");
    expect(handle.className).toContain("cursor-col-resize");
  });

  it("includes aria-label for the correct side", () => {
    const { rerender } = render(
      <ResizeHandle side="right" isDragging={false} onPointerDown={vi.fn()} />,
    );

    expect(screen.getByRole("separator")).toHaveAttribute(
      "aria-label",
      "Resize right panel",
    );

    rerender(
      <ResizeHandle side="left" isDragging={false} onPointerDown={vi.fn()} />,
    );

    expect(screen.getByRole("separator")).toHaveAttribute(
      "aria-label",
      "Resize left panel",
    );
  });

  it("calls onPointerDown when pointer is pressed", () => {
    const onPointerDown = vi.fn();
    render(
      <ResizeHandle
        side="right"
        isDragging={false}
        onPointerDown={onPointerDown}
      />,
    );

    const handle = screen.getByRole("separator");
    handle.dispatchEvent(
      new PointerEvent("pointerdown", { bubbles: true }),
    );

    expect(onPointerDown).toHaveBeenCalledTimes(1);
  });

  it("applies bg-ring class when dragging", () => {
    const { container } = render(
      <ResizeHandle side="right" isDragging={true} onPointerDown={vi.fn()} />,
    );

    // The inner indicator bar div
    const indicator = container.querySelector("[class*='bg-ring']");
    expect(indicator).toBeInTheDocument();
  });
});
