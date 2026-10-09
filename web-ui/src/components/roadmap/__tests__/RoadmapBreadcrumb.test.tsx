import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import RoadmapBreadcrumb from "../RoadmapBreadcrumb";

const FULL_TASK = {
  id: "task-1",
  title: "Add dark mode",
  epicId: "epic-1",
  epicTitle: "UI Overhaul",
  storyId: "story-1",
  storyTitle: "Theming",
};

describe("RoadmapBreadcrumb", () => {
  it("inline: renders three segments with correct hrefs under a nav named Roadmap", () => {
    renderWithProviders(<RoadmapBreadcrumb task={FULL_TASK} variant="inline" />);

    expect(screen.getByRole("navigation", { name: "Roadmap" })).toBeInTheDocument();
    expect(screen.getByTestId("roadmap-breadcrumb-epic")).toHaveAttribute("href", "/roadmap/epics/epic-1");
    expect(screen.getByTestId("roadmap-breadcrumb-story")).toHaveAttribute(
      "href",
      "/roadmap/epics/epic-1/stories/story-1",
    );
    expect(screen.getByTestId("roadmap-breadcrumb-task")).toHaveAttribute("href", "/tasks/task-1");
  });

  it("inline: hovering the epic segment shows its full title in a tooltip", async () => {
    const user = userEvent.setup();
    renderWithProviders(<RoadmapBreadcrumb task={FULL_TASK} variant="inline" />);

    await user.hover(screen.getByTestId("roadmap-breadcrumb-epic"));

    await waitFor(() => {
      expect(screen.getByText("Epic · UI Overhaul")).toBeInTheDocument();
    });
  });

  it("inline: epic without a resolvable story renders two segments", () => {
    renderWithProviders(
      <RoadmapBreadcrumb
        task={{ ...FULL_TASK, storyId: null, storyTitle: null }}
        variant="inline"
      />,
    );

    expect(screen.getByTestId("roadmap-breadcrumb-epic")).toBeInTheDocument();
    expect(screen.queryByTestId("roadmap-breadcrumb-story")).not.toBeInTheDocument();
    expect(screen.getByTestId("roadmap-breadcrumb-task")).toBeInTheDocument();
  });

  it("inline: no epic renders the task segment alone", () => {
    renderWithProviders(
      <RoadmapBreadcrumb
        task={{ ...FULL_TASK, epicId: null, epicTitle: null, storyId: null, storyTitle: null }}
        variant="inline"
      />,
    );

    expect(screen.queryByTestId("roadmap-breadcrumb-epic")).not.toBeInTheDocument();
    expect(screen.queryByTestId("roadmap-breadcrumb-story")).not.toBeInTheDocument();
    expect(screen.getByTestId("roadmap-breadcrumb-task")).toHaveTextContent("Add dark mode");
  });

  it("stacked: shows the Epic/Story/Task labels and full titles", () => {
    renderWithProviders(<RoadmapBreadcrumb task={FULL_TASK} variant="stacked" />);

    expect(screen.getByText("Epic")).toBeInTheDocument();
    expect(screen.getByText("Story")).toBeInTheDocument();
    expect(screen.getByText("Task")).toBeInTheDocument();
    expect(screen.getByTestId("roadmap-breadcrumb-epic")).toHaveTextContent("UI Overhaul");
    expect(screen.getByTestId("roadmap-breadcrumb-story")).toHaveTextContent("Theming");
    expect(screen.getByTestId("roadmap-breadcrumb-task")).toHaveTextContent("Add dark mode");
  });

  it("carries a data-variant attribute matching the rendered variant", () => {
    const { rerender } = renderWithProviders(<RoadmapBreadcrumb task={FULL_TASK} variant="inline" />);
    expect(screen.getByTestId("roadmap-breadcrumb")).toHaveAttribute("data-variant", "inline");

    rerender(<RoadmapBreadcrumb task={FULL_TASK} variant="stacked" />);
    expect(screen.getByTestId("roadmap-breadcrumb")).toHaveAttribute("data-variant", "stacked");
  });
});
