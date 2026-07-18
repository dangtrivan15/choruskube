import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import RoadmapPage from "@/pages/RoadmapPage";
import type { EpicResponse } from "@/lib/types";

const mockUseEpics = vi.fn();

vi.mock("@/hooks/useEpics", () => ({
  useEpics: () => mockUseEpics(),
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
    progress: { totalTasks: 0, doneTasks: 0 },
    softwareProject: { id: "r1", type: "git_repo", name: "backend-api" },
    repos: [],
    createdAt: "2026-04-01T00:00:00Z",
    updatedAt: "2026-04-01T00:00:00Z",
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
    const link = screen.getByTestId("epic-item");
    expect(link).toHaveAttribute("href", "/roadmap/epics/epic-42");
  });

  it("opens the create epic dialog when New Epic is clicked", async () => {
    mockUseEpics.mockReturnValue({ data: emptyPage, isLoading: false });
    renderWithProviders(<RoadmapPage />);
    const user = userEvent.setup();
    await user.click(screen.getByTestId("new-epic-button"));
    expect(screen.getByRole("heading", { name: "New Epic" })).toBeInTheDocument();
  });
});
