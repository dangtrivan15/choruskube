import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/__tests__/test-utils";
import RoadmapViewSwitcher from "@/components/roadmap/RoadmapViewSwitcher";

describe("RoadmapViewSwitcher", () => {
  it("with focusedEpicId set, links Graph at the epic-scoped path and Board/Timeline with ?epic=", () => {
    renderWithProviders(<RoadmapViewSwitcher activeView="board" focusedEpicId="epic-1" />);

    expect(screen.getByTestId("roadmap-view-switcher-graph")).toHaveAttribute(
      "href",
      "/roadmap/epics/epic-1/graph",
    );
    expect(screen.getByTestId("roadmap-view-switcher-timeline")).toHaveAttribute(
      "href",
      "/roadmap/timeline?epic=epic-1",
    );
  });

  it("with no focus, Graph is disabled (not a functioning link) and Board/Timeline omit query params", () => {
    renderWithProviders(<RoadmapViewSwitcher activeView="timeline" />);

    const graphEntry = screen.getByTestId("roadmap-view-switcher-graph");
    expect(graphEntry.tagName).toBe("BUTTON");
    expect(graphEntry).toBeDisabled();
    expect(graphEntry).not.toHaveAttribute("href");

    expect(screen.getByTestId("roadmap-view-switcher-board")).toHaveAttribute("href", "/roadmap/board");
  });

  it("with focusedStoryId also set, Board/Timeline links include &story=", () => {
    renderWithProviders(
      <RoadmapViewSwitcher activeView="graph" focusedEpicId="epic-1" focusedStoryId="story-1" />,
    );

    expect(screen.getByTestId("roadmap-view-switcher-board")).toHaveAttribute(
      "href",
      "/roadmap/board?epic=epic-1&story=story-1",
    );
    expect(screen.getByTestId("roadmap-view-switcher-timeline")).toHaveAttribute(
      "href",
      "/roadmap/timeline?epic=epic-1&story=story-1",
    );
  });

  it("the activeView entry is not itself a clickable link to the current page", () => {
    renderWithProviders(<RoadmapViewSwitcher activeView="timeline" focusedEpicId="epic-1" />);

    const timelineEntry = screen.getByTestId("roadmap-view-switcher-timeline");
    expect(timelineEntry.tagName).not.toBe("A");
    expect(timelineEntry).toHaveAttribute("aria-current", "page");

    // The other two entries are still real links.
    expect(screen.getByTestId("roadmap-view-switcher-graph").tagName).toBe("A");
    expect(screen.getByTestId("roadmap-view-switcher-board").tagName).toBe("A");
  });
});
