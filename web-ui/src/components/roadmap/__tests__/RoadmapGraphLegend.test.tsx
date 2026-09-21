import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/__tests__/test-utils";
import RoadmapGraphLegend from "@/components/roadmap/RoadmapGraphLegend";
import { ROADMAP_EDGE_STYLES } from "@/lib/roadmapEdgeStyles";

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

  it.each([
    ["roadmap-graph-legend-hierarchy", "hierarchy"],
    ["roadmap-graph-legend-dependency", "dependency"],
    ["roadmap-graph-legend-epic-dependency", "epicDependency"],
    ["roadmap-graph-legend-cross-epic", "crossEpic"],
  ] as const)(
    "%s's swatch color and dash array equal the ROADMAP_EDGE_STYLES.%s entry — the same one the edge itself reads",
    (testId, kind) => {
      renderWithProviders(<RoadmapGraphLegend />);

      const entry = ROADMAP_EDGE_STYLES[kind];
      const line = screen.getByTestId(testId).querySelector("line");
      expect(line).toBeTruthy();
      expect(line).toHaveAttribute("stroke", `var(${entry.token})`);
      if (entry.dashArray) {
        expect(line).toHaveAttribute("stroke-dasharray", entry.dashArray);
      } else {
        expect(line).not.toHaveAttribute("stroke-dasharray");
      }
    },
  );
});
