import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import RoadmapPage from "@/pages/RoadmapPage";
import type { EpicResponse } from "@/lib/types";

const mockUseEpics = vi.fn();

vi.mock("@/hooks/useEpics", () => ({
  useEpics: (...args: unknown[]) => mockUseEpics(...args),
  useCreateEpic: () => ({
    mutate: vi.fn(),
    isPending: false,
    isError: false,
    reset: vi.fn(),
  }),
}));

vi.mock("@/hooks/useRoadmapSubscription", () => ({
  useRoadmapSubscription: vi.fn(),
}));

vi.mock("@/hooks/useSoftwareProjects", () => ({
  useSoftwareProjects: () => ({ data: [] }),
}));

beforeEach(() => {
  mockUseEpics.mockReset();
});

function makeEpic(overrides: Partial<EpicResponse> = {}): EpicResponse {
  return {
    id: "epic-1",
    title: "Add dark mode",
    description: "Add a dark theme",
    motivation: null,
    status: "backlog",
    stage: "backlog",
    priority: "medium",
    progress: { totalTasks: 0, doneTasks: 0 },
    softwareProject: { id: "r1", type: "git_repo", name: "backend-api" },
    repos: [],
    createdAt: "2026-04-01T00:00:00Z",
    updatedAt: "2026-04-01T00:00:00Z",
    readyItemCount: 0,
    ...overrides,
  };
}

const emptyPage = {
  content: [],
  totalElements: 0,
  totalPages: 1,
  number: 0,
  size: 20,
  first: true,
  last: true,
  empty: true,
};

describe("RoadmapPage", () => {
  it("renders the heading and New Epic button", () => {
    mockUseEpics.mockReturnValue({ data: emptyPage, isLoading: false });
    renderWithProviders(<RoadmapPage />);
    expect(screen.getByTestId("roadmap-heading")).toHaveTextContent("Roadmap");
    expect(screen.getByTestId("new-epic-button")).toBeInTheDocument();
  });

  it("shows loading skeletons while loading", () => {
    mockUseEpics.mockReturnValue({ data: undefined, isLoading: true });
    renderWithProviders(<RoadmapPage />);
    expect(document.querySelectorAll('[data-slot="skeleton"]').length).toBeGreaterThan(0);
  });

  it("shows empty state when there are no epics", () => {
    mockUseEpics.mockReturnValue({ data: emptyPage, isLoading: false });
    renderWithProviders(<RoadmapPage />);
    expect(screen.getByText(/No epics yet/)).toBeInTheDocument();
  });

  it("renders an epic list with title, status, and progress", () => {
    const epic = makeEpic({ title: "Add dark mode", status: "in_progress", progress: { totalTasks: 3, doneTasks: 1 } });
    mockUseEpics.mockReturnValue({
      data: { ...emptyPage, content: [epic], totalElements: 1, empty: false },
      isLoading: false,
    });
    renderWithProviders(<RoadmapPage />);
    expect(screen.getByTestId("epic-list")).toBeInTheDocument();
    expect(screen.getAllByTestId("epic-item")).toHaveLength(1);
    expect(screen.getByText("Add dark mode")).toBeInTheDocument();
    expect(screen.getByText("in progress")).toBeInTheDocument();
    expect(screen.getByTestId("epic-progress")).toHaveTextContent("1/3 tasks done");
  });

  it("links each epic item to its detail route", () => {
    const epic = makeEpic({ id: "epic-42", title: "Support OAuth" });
    mockUseEpics.mockReturnValue({
      data: { ...emptyPage, content: [epic], totalElements: 1, empty: false },
      isLoading: false,
    });
    renderWithProviders(<RoadmapPage />);
    // epic-item is the row container (holding both the detail link and the
    // graph link below) — the detail link is the anchor wrapping the title.
    const detailLink = screen.getByText("Support OAuth").closest("a");
    expect(detailLink).toHaveAttribute("href", "/roadmap/epics/epic-42");
  });

  it("links each epic item's graph action to its Roadmap Graph View route", () => {
    const epic = makeEpic({ id: "epic-42", title: "Support OAuth" });
    mockUseEpics.mockReturnValue({
      data: { ...emptyPage, content: [epic], totalElements: 1, empty: false },
      isLoading: false,
    });
    renderWithProviders(<RoadmapPage />);
    expect(screen.getByTestId("epic-graph-link")).toHaveAttribute(
      "href",
      "/roadmap/epics/epic-42/graph",
    );
  });

  it("opens the create epic dialog when New Epic is clicked", async () => {
    mockUseEpics.mockReturnValue({ data: emptyPage, isLoading: false });
    renderWithProviders(<RoadmapPage />);
    const user = userEvent.setup();
    await user.click(screen.getByTestId("new-epic-button"));
    expect(screen.getByRole("heading", { name: "New Epic" })).toBeInTheDocument();
  });

  // --- "Ready to start" filter ---

  it("toggling the filter re-issues the query with readyOnly=true", async () => {
    mockUseEpics.mockReturnValue({ data: emptyPage, isLoading: false });
    renderWithProviders(<RoadmapPage />);
    const user = userEvent.setup();

    await user.click(screen.getByTestId("ready-to-start-toggle"));

    const lastCall = mockUseEpics.mock.calls[mockUseEpics.mock.calls.length - 1];
    expect(lastCall?.[2]).toBe(true);
  });

  it("toggling off restores the unfiltered query", async () => {
    mockUseEpics.mockReturnValue({ data: emptyPage, isLoading: false });
    renderWithProviders(<RoadmapPage />);
    const user = userEvent.setup();

    await user.click(screen.getByTestId("ready-to-start-toggle"));
    await user.click(screen.getByTestId("ready-to-start-toggle"));

    const lastCall = mockUseEpics.mock.calls[mockUseEpics.mock.calls.length - 1];
    expect(lastCall?.[2]).toBe(false);
  });

  // --- Priority sort & filter ---

  it("selecting the priority sort option issues sort=priority,desc via the pagination arg", async () => {
    mockUseEpics.mockReturnValue({ data: emptyPage, isLoading: false });
    renderWithProviders(<RoadmapPage />);
    const user = userEvent.setup({ pointerEventsCheck: 0 });

    // The only Select on the page is the SortDropdown (the create dialog is closed).
    await user.click(screen.getByRole("combobox"));
    await user.click(screen.getByText(/Priority \(High/));

    const lastCall = mockUseEpics.mock.calls[mockUseEpics.mock.calls.length - 1];
    // pagination is the 2nd positional arg; its sort must be priority,desc.
    expect(lastCall?.[1]).toMatchObject({ sort: { field: "priority", direction: "desc" } });
  });

  it("selecting a priority filter threads the level into the query (priority arg)", async () => {
    mockUseEpics.mockReturnValue({ data: emptyPage, isLoading: false });
    renderWithProviders(<RoadmapPage />);
    const user = userEvent.setup({ pointerEventsCheck: 0 });

    await user.click(screen.getByTestId("priority-filter-high"));

    const lastCall = mockUseEpics.mock.calls[mockUseEpics.mock.calls.length - 1];
    // priority is the 4th positional arg (after title, pagination, readyOnly).
    expect(lastCall?.[3]).toBe("high");
  });

  it("selecting 'All' clears the priority filter", async () => {
    mockUseEpics.mockReturnValue({ data: emptyPage, isLoading: false });
    renderWithProviders(<RoadmapPage />);
    const user = userEvent.setup({ pointerEventsCheck: 0 });

    await user.click(screen.getByTestId("priority-filter-low"));
    await user.click(screen.getByTestId("priority-filter-all"));

    const lastCall = mockUseEpics.mock.calls[mockUseEpics.mock.calls.length - 1];
    expect(lastCall?.[3]).toBeUndefined();
  });

  it("shows filter-specific empty-state copy when the filter yields zero results despite non-empty underlying data", async () => {
    mockUseEpics.mockImplementation((_title?: string, _pagination?: unknown, readyOnly?: boolean) =>
      readyOnly
        ? { data: emptyPage, isLoading: false }
        : {
            data: { ...emptyPage, content: [makeEpic()], totalElements: 1, empty: false },
            isLoading: false,
          }
    );
    renderWithProviders(<RoadmapPage />);
    const user = userEvent.setup();

    expect(screen.queryByText(/No epics currently have ready work/)).not.toBeInTheDocument();

    await user.click(screen.getByTestId("ready-to-start-toggle"));

    expect(screen.getByText(/No epics currently have ready work/)).toBeInTheDocument();
  });
});
