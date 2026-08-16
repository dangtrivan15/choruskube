import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/__tests__/test-utils";
import RoadmapGraphLegend from "@/components/roadmap/RoadmapGraphLegend";

describe("RoadmapGraphLegend", () => {
  it("renders labeled swatches for hierarchy, within-Epic, Epic-tier, and cross-Epic edges", () => {
    renderWithProviders(<RoadmapGraphLegend />);

    expect(screen.getByTestId("roadmap-graph-legend-hierarchy")).toHaveTextContent("Hierarchy");
    expect(screen.getByTestId("roadmap-graph-legend-dependency")).toHaveTextContent("Blocking dependency");
    expect(screen.getByTestId("roadmap-graph-legend-epic-dependency")).toHaveTextContent(
      "Epic-tier dependency",
    );
    expect(screen.getByTestId("roadmap-graph-legend-cross-epic")).toHaveTextContent("Cross-Epic dependency");
  });
});
