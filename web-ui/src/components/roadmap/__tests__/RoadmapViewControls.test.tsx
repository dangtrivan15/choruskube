import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import RoadmapViewControls from "@/components/roadmap/RoadmapViewControls";

const mockNavigate = vi.fn();
vi.mock("react-router", async (importOriginal) => {
  const actual = await importOriginal<typeof import("react-router")>();
  return { ...actual, useNavigate: () => mockNavigate };
});

beforeEach(() => {
  mockNavigate.mockReset();
});

/** Opens the ticket-type dropdown and picks `level`. */
async function pickTicketType(level: "epic" | "story" | "task") {
  const user = userEvent.setup({ pointerEventsCheck: 0 });
  await user.click(screen.getByTestId("roadmap-level-select"));
  await user.click(screen.getByTestId(`roadmap-level-option-${level}`));
}

describe("RoadmapViewControls — view axis", () => {
  it("offers List, Board and Timeline for Epics, each pointing at its own page", () => {
    renderWithProviders(<RoadmapViewControls level="epic" view="board" />);

    expect(screen.getByTestId("roadmap-view-list")).toHaveAttribute("href", "/roadmap");
    expect(screen.getByTestId("roadmap-view-timeline")).toHaveAttribute("href", "/roadmap/timeline");
  });

  it("renders no Timeline control at all for Stories — not a disabled one", () => {
    renderWithProviders(<RoadmapViewControls level="story" view="board" />);

    expect(screen.getByTestId("roadmap-view-list")).toHaveAttribute("href", "/roadmap/stories");
    expect(screen.queryByTestId("roadmap-view-timeline")).not.toBeInTheDocument();
  });

  it("renders no Timeline control at all for Tasks either", () => {
    renderWithProviders(<RoadmapViewControls level="task" view="list" />);

    expect(screen.getByTestId("roadmap-view-board")).toHaveAttribute("href", "/roadmap/board/tasks");
    expect(screen.queryByTestId("roadmap-view-timeline")).not.toBeInTheDocument();
  });

  it("marks the current view as the current page instead of linking back to itself", () => {
    renderWithProviders(<RoadmapViewControls level="epic" view="timeline" />);

    const current = screen.getByTestId("roadmap-view-timeline");
    expect(current.tagName).not.toBe("A");
    expect(current).toHaveAttribute("aria-current", "page");

    expect(screen.getByTestId("roadmap-view-list").tagName).toBe("A");
    expect(screen.getByTestId("roadmap-view-board").tagName).toBe("A");
  });

  it("carries a focused Epic+Story into every Epic-level view link", () => {
    renderWithProviders(
      <RoadmapViewControls level="epic" view="board" focusedEpicId="epic-1" focusedStoryId="story-1" />,
    );

    expect(screen.getByTestId("roadmap-view-list")).toHaveAttribute(
      "href",
      "/roadmap?epic=epic-1&story=story-1",
    );
    expect(screen.getByTestId("roadmap-view-timeline")).toHaveAttribute(
      "href",
      "/roadmap/timeline?epic=epic-1&story=story-1",
    );
  });

  it("omits the query entirely when nothing is focused", () => {
    renderWithProviders(<RoadmapViewControls level="epic" view="list" />);

    expect(screen.getByTestId("roadmap-view-board")).toHaveAttribute("href", "/roadmap/board");
    expect(screen.getByTestId("roadmap-view-timeline")).toHaveAttribute("href", "/roadmap/timeline");
  });
});

describe("RoadmapViewControls — Graph action", () => {
  it("links to the focused Epic's graph", () => {
    renderWithProviders(<RoadmapViewControls level="epic" view="board" focusedEpicId="epic-1" />);

    expect(screen.getByTestId("roadmap-graph-action")).toHaveAttribute(
      "href",
      "/roadmap/epics/epic-1/graph",
    );
  });

  it("is a disabled button with an explanation when nothing is focused", () => {
    renderWithProviders(<RoadmapViewControls level="epic" view="timeline" />);

    const graph = screen.getByTestId("roadmap-graph-action");
    expect(graph.tagName).toBe("BUTTON");
    expect(graph).toBeDisabled();
    expect(graph).not.toHaveAttribute("href");
    expect(graph).toHaveAttribute("title", "Focus an Epic or Story to open its graph");
  });

  it("stays disabled on Story and Task surfaces, which have no focus to offer it", () => {
    renderWithProviders(<RoadmapViewControls level="story" view="board" />);
    expect(screen.getByTestId("roadmap-graph-action")).toBeDisabled();
  });

  it("marks itself the current page on the Graph view, where no view button is current", () => {
    renderWithProviders(<RoadmapViewControls level="epic" view="graph" focusedEpicId="epic-1" />);

    expect(screen.getByTestId("roadmap-graph-action")).toHaveAttribute("aria-current", "page");
    expect(screen.getByTestId("roadmap-view-board")).not.toHaveAttribute("aria-current");
    expect(screen.getByTestId("roadmap-view-board").tagName).toBe("A");
  });
});

describe("RoadmapViewControls — ticket type", () => {
  it("shows the current ticket type in the plural", () => {
    renderWithProviders(<RoadmapViewControls level="story" view="board" />);
    expect(screen.getByTestId("roadmap-level-select")).toHaveTextContent("Stories");
  });

  it("offers all three ticket types as options", async () => {
    renderWithProviders(<RoadmapViewControls level="epic" view="board" />);
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    await user.click(screen.getByTestId("roadmap-level-select"));

    // Base UI renders each item as role="option", not as a <button> — an unavailable ticket type
    // could therefore never be expressed as a disabled control here, which is part of why the
    // view axis (where availability really varies) is buttons rather than a second dropdown.
    expect(screen.getAllByRole("option")).toHaveLength(3);
    expect(screen.getByTestId("roadmap-level-option-task")).toBeInTheDocument();
  });

  it("keeps the current view when the new ticket type has it", async () => {
    renderWithProviders(<RoadmapViewControls level="epic" view="list" />);
    await pickTicketType("task");

    expect(mockNavigate).toHaveBeenCalledWith("/roadmap/tasks");
  });

  it("falls back to Board when the current view has no page for the new ticket type", async () => {
    renderWithProviders(<RoadmapViewControls level="epic" view="timeline" />);
    await pickTicketType("story");

    expect(mockNavigate).toHaveBeenCalledWith("/roadmap/board/stories");
  });

  it("falls back to Board from the Graph view, which is not a view type", async () => {
    renderWithProviders(<RoadmapViewControls level="epic" view="graph" focusedEpicId="epic-1" />);
    await pickTicketType("task");

    expect(mockNavigate).toHaveBeenCalledWith("/roadmap/board/tasks");
  });

  it("drops the focus when crossing below the Epic level", async () => {
    renderWithProviders(
      <RoadmapViewControls level="epic" view="board" focusedEpicId="epic-1" focusedStoryId="story-1" />,
    );
    await pickTicketType("story");

    // No `?epic=`/`?story=`: StoryBoardPage never reads them, so a URL carrying them would claim
    // a selection the destination cannot honour.
    expect(mockNavigate).toHaveBeenCalledWith("/roadmap/board/stories");
  });

  it("carries the focus back up when returning to the Epic level", async () => {
    renderWithProviders(
      <RoadmapViewControls level="story" view="board" focusedEpicId="epic-1" focusedStoryId="story-1" />,
    );
    await pickTicketType("epic");

    expect(mockNavigate).toHaveBeenCalledWith("/roadmap/board?epic=epic-1&story=story-1");
  });

  it("does not navigate when the already-selected ticket type is re-picked", async () => {
    renderWithProviders(<RoadmapViewControls level="epic" view="board" />);
    await pickTicketType("epic");

    expect(mockNavigate).not.toHaveBeenCalled();
  });
});
