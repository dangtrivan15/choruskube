import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/__tests__/test-utils";
import RunTrendChart from "@/components/analytics/RunTrendChart";
import type { RunTrendPoint } from "@/lib/types";

function makePoint(overrides: Partial<RunTrendPoint> = {}): RunTrendPoint {
  return {
    date: "2026-01-05",
    total: 4,
    completed: 4,
    failed: 0,
    ...overrides,
  };
}

describe("RunTrendChart", () => {
  it("renders empty-state message when no points are provided", () => {
    renderWithProviders(<RunTrendChart points={[]} />);
    expect(screen.getByText("No run data for this period")).toBeInTheDocument();
  });

  it("renders without throwing for a 3-point dataset (smoke test)", () => {
    const points: RunTrendPoint[] = [
      makePoint({ date: "2026-01-05", total: 4, completed: 4, failed: 0 }),
      makePoint({ date: "2026-01-06", total: 6, completed: 3, failed: 3 }),
      makePoint({ date: "2026-01-07", total: 5, completed: 5, failed: 0 }),
    ];

    expect(() => renderWithProviders(<RunTrendChart points={points} />)).not.toThrow();

    expect(screen.queryByText("No run data for this period")).not.toBeInTheDocument();
    expect(screen.getByTestId("run-trend-chart")).toBeInTheDocument();
  });
});
