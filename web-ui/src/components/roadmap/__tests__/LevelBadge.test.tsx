import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/__tests__/test-utils";
import LevelBadge from "@/components/roadmap/LevelBadge";
import type { RoadmapLevel } from "@/lib/roadmapLevel";

describe("LevelBadge", () => {
  const cases: { level: RoadmapLevel; label: string }[] = [
    { level: "epic", label: "Epic" },
    { level: "story", label: "Story" },
    { level: "task", label: "Task" },
  ];

  it.each(cases)("renders the $level badge with its label and icon", ({ level, label }) => {
    renderWithProviders(<LevelBadge level={level} />);
    const badge = screen.getByTestId(`level-badge-${level}`);
    expect(badge).toHaveTextContent(label);
    expect(badge.querySelector("svg")).toBeInTheDocument();
  });

  it("forwards an additional className", () => {
    renderWithProviders(<LevelBadge level="epic" className="custom-class" />);
    expect(screen.getByTestId("level-badge-epic")).toHaveClass("custom-class");
  });
});
