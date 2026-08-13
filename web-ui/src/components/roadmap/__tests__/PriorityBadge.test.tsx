import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import PriorityBadge from "@/components/roadmap/PriorityBadge";
import type { Priority } from "@/lib/types";

describe("PriorityBadge", () => {
  it.each([
    ["high", "High", "text-status-error"],
    ["medium", "Medium", "text-status-warning"],
    ["low", "Low", "text-status-neutral"],
  ] as [Priority, string, string][])(
    "renders the %s priority with its label and accent color",
    (priority, label, accentClass) => {
      render(<PriorityBadge priority={priority} />);
      const badge = screen.getByTestId(`priority-badge-${priority}`);
      expect(badge).toHaveTextContent(label);
      expect(badge.className).toContain(accentClass);
    },
  );

  it("uses an explicit data-testid when provided", () => {
    render(<PriorityBadge priority="high" data-testid="custom-priority" />);
    expect(screen.getByTestId("custom-priority")).toHaveTextContent("High");
  });

  it("renders nothing (no crash) for an unknown priority value", () => {
    // Simulates stale/older cached data carrying a value outside low|medium|high.
    const { container } = render(
      <PriorityBadge priority={"bogus" as unknown as Priority} />,
    );
    expect(container).toBeEmptyDOMElement();
  });

  it("renders nothing for a null/undefined priority", () => {
    const { container, rerender } = render(<PriorityBadge priority={null} />);
    expect(container).toBeEmptyDOMElement();
    rerender(<PriorityBadge priority={undefined} />);
    expect(container).toBeEmptyDOMElement();
  });
});
