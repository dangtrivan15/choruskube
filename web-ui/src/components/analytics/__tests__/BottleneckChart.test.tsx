import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/__tests__/test-utils";
import BottleneckChart from "@/components/analytics/BottleneckChart";
import type { BottleneckNode } from "@/lib/types";

function makeBottleneck(overrides: Partial<BottleneckNode> = {}): BottleneckNode {
  return {
    label: "node-a",
    avgDurationSeconds: 45,
    p50DurationSeconds: 40,
    p95DurationSeconds: 120,
    sampleSize: 12,
    ...overrides,
  };
}

describe("BottleneckChart", () => {
  it("renders empty-state message when no bottlenecks are provided", () => {
    renderWithProviders(<BottleneckChart bottlenecks={[]} />);
    expect(
      screen.getByText("No bottleneck data for this period"),
    ).toBeInTheDocument();
  });

  it("wraps the chart in an h-72 w-full container so it survives narrow grid items", () => {
    const { container } = renderWithProviders(
      <BottleneckChart bottlenecks={[makeBottleneck()]} />,
    );

    const wrapper = container.querySelector(".h-72");
    expect(wrapper).not.toBeNull();
    expect(wrapper?.className).toContain("w-full");
  });

  it("renders without throwing for a 10-row dataset (smoke test)", () => {
    const data: BottleneckNode[] = Array.from({ length: 10 }, (_, i) =>
      makeBottleneck({
        label: `node-${i}`,
        avgDurationSeconds: (i + 1) * 10,
        p50DurationSeconds: (i + 1) * 8,
        p95DurationSeconds: (i + 1) * 30,
        sampleSize: i + 1,
      }),
    );

    expect(() =>
      renderWithProviders(<BottleneckChart bottlenecks={data} />),
    ).not.toThrow();

    // Empty-state copy should not render once we have data.
    expect(
      screen.queryByText("No bottleneck data for this period"),
    ).not.toBeInTheDocument();
  });
});
