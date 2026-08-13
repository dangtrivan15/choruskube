import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import EpicDetailPage from "@/pages/EpicDetailPage";
import type { EpicResponse, StoryResponse } from "@/lib/types";

const mockUseEpic = vi.fn();
const mockUseStories = vi.fn();
const mockDeleteMutate = vi.fn();

vi.mock("@/hooks/useEpics", () => ({
  useEpic: (id: string) => mockUseEpic(id),
  useDeleteEpic: () => ({
    mutate: mockDeleteMutate,
    isPending: false,
    isError: false,
    reset: vi.fn(),
  }),
  useUpdateEpic: () => ({
    mutate: vi.fn(),
    isPending: false,
    isError: false,
    reset: vi.fn(),
  }),
  useUpdateEpicPriority: () => ({
    mutate: vi.fn(),
    isPending: false,
    isError: false,
    reset: vi.fn(),
  }),
}));

vi.mock("@/hooks/useStories", () => ({
  useStories: (epicId: string) => mockUseStories(epicId),
  useCreateStory: () => ({
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

vi.mock("react-router", async (importOriginal) => {
  const actual = await importOriginal<typeof import("react-router")>();
  return {
    ...actual,
    useParams: () => ({ epicId: "epic-1" }),
    useNavigate: () => vi.fn(),
  };
});

beforeEach(() => {
  mockUseEpic.mockReset();
  mockUseStories.mockReset();
  mockDeleteMutate.mockReset();
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
    progress: { totalTasks: 2, doneTasks: 1 },
    softwareProject: { id: "r1", type: "git_repo", name: "backend-api" },
    repos: [],
    createdAt: "2026-04-01T00:00:00Z",
    updatedAt: "2026-04-01T00:00:00Z",
    readyItemCount: 0,
    ...overrides,
  };
}

function makeStory(overrides: Partial<StoryResponse> = {}): StoryResponse {
  return {
    id: "story-1",
    epicId: "epic-1",
    title: "Dark theme toggle",
    description: "desc",
    status: "backlog",
    stage: "backlog",
    priority: "medium",
    readiness: null,
    progress: { totalTasks: 1, doneTasks: 0 },
    createdAt: "2026-04-01T00:00:00Z",
    updatedAt: "2026-04-01T00:00:00Z",
    ...overrides,
  };
}

describe("EpicDetailPage", () => {
  it("shows a loading skeleton while the epic is loading", () => {
    mockUseEpic.mockReturnValue({ data: undefined, isLoading: true });
    mockUseStories.mockReturnValue({ data: undefined, isLoading: true });
    renderWithProviders(<EpicDetailPage />);
    expect(document.querySelectorAll('[data-slot="skeleton"]').length).toBeGreaterThan(0);
  });

  it("renders epic title, status, and progress", () => {
    mockUseEpic.mockReturnValue({ data: makeEpic(), isLoading: false });
    mockUseStories.mockReturnValue({ data: [], isLoading: false });
    renderWithProviders(<EpicDetailPage />);
    expect(screen.getByTestId("epic-detail-title")).toHaveTextContent("Add dark mode");
    expect(screen.getByTestId("epic-detail-status")).toHaveTextContent("backlog");
    expect(screen.getByTestId("epic-detail-progress")).toHaveTextContent("1/2 tasks done");
  });

  it("renders the Epic LevelBadge", () => {
    mockUseEpic.mockReturnValue({ data: makeEpic(), isLoading: false });
    mockUseStories.mockReturnValue({ data: [], isLoading: false });
    renderWithProviders(<EpicDetailPage />);
    expect(screen.getByTestId("level-badge-epic")).toHaveTextContent("Epic");
  });

  it('keeps the "Back to Roadmap" link unchanged', () => {
    mockUseEpic.mockReturnValue({ data: makeEpic(), isLoading: false });
    mockUseStories.mockReturnValue({ data: [], isLoading: false });
    renderWithProviders(<EpicDetailPage />);
    const link = screen.getByRole("link", { name: /Back to Roadmap/ });
    expect(link).toHaveAttribute("href", "/roadmap");
  });

  it("shows Edit and Delete buttons when the epic is in backlog", () => {
    mockUseEpic.mockReturnValue({ data: makeEpic({ status: "backlog" }), isLoading: false });
    mockUseStories.mockReturnValue({ data: [], isLoading: false });
    renderWithProviders(<EpicDetailPage />);
    expect(screen.getByTestId("epic-edit-button")).toBeInTheDocument();
    expect(screen.getByTestId("epic-delete-button")).toBeInTheDocument();
  });

  it("hides Edit and Delete buttons once the epic has left backlog", () => {
    mockUseEpic.mockReturnValue({ data: makeEpic({ status: "in_progress" }), isLoading: false });
    mockUseStories.mockReturnValue({ data: [], isLoading: false });
    renderWithProviders(<EpicDetailPage />);
    expect(screen.queryByTestId("epic-edit-button")).not.toBeInTheDocument();
    expect(screen.queryByTestId("epic-delete-button")).not.toBeInTheDocument();
  });

  it("renders the story list with links to each story's detail route", () => {
    mockUseEpic.mockReturnValue({ data: makeEpic(), isLoading: false });
    mockUseStories.mockReturnValue({ data: [makeStory()], isLoading: false });
    renderWithProviders(<EpicDetailPage />);
    const item = screen.getByTestId("story-item");
    expect(item).toHaveTextContent("Dark theme toggle");
    expect(item).toHaveAttribute("href", "/roadmap/epics/epic-1/stories/story-1");
  });

  it("shows a Blocked badge on a Story row whose readiness is BLOCKED", () => {
    mockUseEpic.mockReturnValue({ data: makeEpic(), isLoading: false });
    mockUseStories.mockReturnValue({ data: [makeStory({ readiness: "BLOCKED" })], isLoading: false });
    renderWithProviders(<EpicDetailPage />);
    expect(screen.getByTestId("story-item-readiness-badge")).toHaveTextContent("Blocked");
  });

  it("shows no readiness badge on a Story row whose readiness is READY", () => {
    mockUseEpic.mockReturnValue({ data: makeEpic(), isLoading: false });
    mockUseStories.mockReturnValue({ data: [makeStory({ readiness: "READY" })], isLoading: false });
    renderWithProviders(<EpicDetailPage />);
    expect(screen.queryByTestId("story-item-readiness-badge")).not.toBeInTheDocument();
  });

  it("shows no readiness badge on a Story row whose readiness is null", () => {
    mockUseEpic.mockReturnValue({ data: makeEpic(), isLoading: false });
    mockUseStories.mockReturnValue({ data: [makeStory({ readiness: null })], isLoading: false });
    renderWithProviders(<EpicDetailPage />);
    expect(screen.queryByTestId("story-item-readiness-badge")).not.toBeInTheDocument();
  });

  it("shows empty state when there are no stories", () => {
    mockUseEpic.mockReturnValue({ data: makeEpic(), isLoading: false });
    mockUseStories.mockReturnValue({ data: [], isLoading: false });
    renderWithProviders(<EpicDetailPage />);
    expect(screen.getByText(/No stories yet/)).toBeInTheDocument();
  });

  // --- "Ready to start" filter (client-side, over already-fetched data) ---

  it("toggling the filter hides BLOCKED story rows without re-parameterizing useStories", async () => {
    mockUseEpic.mockReturnValue({ data: makeEpic(), isLoading: false });
    mockUseStories.mockReturnValue({
      data: [
        makeStory({ id: "story-ready", title: "Ready Story", readiness: "READY" }),
        makeStory({ id: "story-blocked", title: "Blocked Story", readiness: "BLOCKED" }),
      ],
      isLoading: false,
    });
    renderWithProviders(<EpicDetailPage />);
    expect(screen.getByText("Blocked Story")).toBeInTheDocument();

    const user = userEvent.setup();
    await user.click(screen.getByTestId("ready-to-start-toggle"));

    expect(screen.queryByText("Blocked Story")).not.toBeInTheDocument();
    expect(screen.getByText("Ready Story")).toBeInTheDocument();
    // The filter is purely local — useStories must never be called with anything but the
    // epicId, i.e. toggling never fires a new network request.
    mockUseStories.mock.calls.forEach((call) => expect(call).toEqual(["epic-1"]));
  });

  it("shows filter-specific empty-state copy when the filter yields zero results despite non-empty story data", async () => {
    mockUseEpic.mockReturnValue({ data: makeEpic(), isLoading: false });
    mockUseStories.mockReturnValue({ data: [makeStory({ readiness: "BLOCKED" })], isLoading: false });
    renderWithProviders(<EpicDetailPage />);
    const user = userEvent.setup();

    expect(screen.queryByText(/No stories are ready to start/)).not.toBeInTheDocument();

    await user.click(screen.getByTestId("ready-to-start-toggle"));

    expect(screen.getByText(/No stories are ready to start/)).toBeInTheDocument();
  });
});
