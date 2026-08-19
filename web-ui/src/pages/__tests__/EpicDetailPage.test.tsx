import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import EpicDetailPage from "@/pages/EpicDetailPage";
import type { EpicResponse, StoryResponse } from "@/lib/types";

const mockUseEpic = vi.fn();
const mockUseStories = vi.fn();
const mockDeleteMutate = vi.fn();
const mockStageMutate = vi.fn();
const mockAssignMilestoneMutate = vi.fn();
const mockUseMilestones = vi.fn();

vi.mock("@/hooks/useEpics", () => ({
  useEpic: (id: string) => mockUseEpic(id),
  useUpdateEpicStage: () => ({
    mutate: mockStageMutate,
    isPending: false,
    isError: false,
    reset: vi.fn(),
  }),
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
  useUpdateEpicTargetDate: () => ({
    mutate: vi.fn(),
    isPending: false,
    isError: false,
    reset: vi.fn(),
  }),
}));

vi.mock("@/hooks/useMilestones", () => ({
  useMilestones: (...args: unknown[]) => mockUseMilestones(...args),
  useAssignEpicMilestone: () => ({
    mutate: mockAssignMilestoneMutate,
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
  mockAssignMilestoneMutate.mockReset();
  mockUseMilestones.mockReset();
  mockUseMilestones.mockReturnValue({ data: { content: [] } });
});

function makeEpic(overrides: Partial<EpicResponse> = {}): EpicResponse {
  return {
    id: "epic-1",
    title: "Add dark mode",
    description: "Add a dark theme",
    motivation: null,
    stage: "backlog",
    priority: "medium",
    targetDate: null,
    progress: { totalTasks: 2, doneTasks: 1, startedTasks: 1 },
    softwareProject: { id: "r1", type: "git_repo", name: "backend-api" },
    repos: [],
    createdAt: "2026-04-01T00:00:00Z",
    updatedAt: "2026-04-01T00:00:00Z",
    readyItemCount: 0,
    milestone: null,
    ...overrides,
  };
}

function makeStory(overrides: Partial<StoryResponse> = {}): StoryResponse {
  return {
    id: "story-1",
    epicId: "epic-1",
    title: "Dark theme toggle",
    description: "desc",
    stage: "backlog",
    priority: "medium",
    targetDate: null,
    readiness: null,
    // Consistent with the `progress` default below: one Task, not started and not blocked.
    readyTaskCount: 1,
    progress: { totalTasks: 1, doneTasks: 0, startedTasks: 0 },
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
    expect(screen.getByTestId("epic-detail-stage")).toHaveTextContent("backlog");
    expect(screen.getByTestId("epic-detail-progress")).toHaveTextContent("1/2 tasks done");
  });

  it("renders the Epic LevelBadge", () => {
    mockUseEpic.mockReturnValue({ data: makeEpic(), isLoading: false });
    mockUseStories.mockReturnValue({ data: [], isLoading: false });
    renderWithProviders(<EpicDetailPage />);
    expect(screen.getByTestId("level-badge-epic")).toHaveTextContent("Epic");
  });

  it("renders the formatted target date when set", () => {
    mockUseEpic.mockReturnValue({
      data: makeEpic({ targetDate: "2026-08-13" }),
      isLoading: false,
    });
    mockUseStories.mockReturnValue({ data: [], isLoading: false });
    renderWithProviders(<EpicDetailPage />);
    expect(screen.getByTestId("epic-detail-target-date")).toHaveTextContent("Aug 13, 2026");
  });

  it("renders the empty state when no target date is set", () => {
    mockUseEpic.mockReturnValue({ data: makeEpic({ targetDate: null }), isLoading: false });
    mockUseStories.mockReturnValue({ data: [], isLoading: false });
    renderWithProviders(<EpicDetailPage />);
    expect(screen.getByTestId("epic-detail-target-date")).toHaveTextContent("No target date");
  });

  it('keeps the "Back to Roadmap" link unchanged', () => {
    mockUseEpic.mockReturnValue({ data: makeEpic(), isLoading: false });
    mockUseStories.mockReturnValue({ data: [], isLoading: false });
    renderWithProviders(<EpicDetailPage />);
    const link = screen.getByRole("link", { name: /Back to Roadmap/ });
    expect(link).toHaveAttribute("href", "/roadmap");
  });

  it("offers the roll-out move once every descendant Task is done", async () => {
    mockUseEpic.mockReturnValue({
      data: makeEpic({ stage: "backlog", progress: { totalTasks: 4, doneTasks: 4, startedTasks: 4 } }),
      isLoading: false,
    });
    mockUseStories.mockReturnValue({ data: [], isLoading: false });
    renderWithProviders(<EpicDetailPage />);

    // The contradiction this replaces: the page used to read "done" while the board read
    // "Backlog", with no control anywhere to reconcile them.
    expect(screen.getByTestId("epic-detail-stage")).toHaveTextContent("backlog");
    expect(screen.getByTestId("epic-detail-progress")).toHaveTextContent("4/4 tasks done");
    await userEvent.click(screen.getByTestId("epic-detail-roll-out-button"));
    expect(mockStageMutate).toHaveBeenCalledWith({ id: "epic-1", stage: "rolled_out" });
  });

  it("does not offer the roll-out move while work is outstanding", () => {
    mockUseEpic.mockReturnValue({
      data: makeEpic({ stage: "backlog", progress: { totalTasks: 4, doneTasks: 3, startedTasks: 4 } }),
      isLoading: false,
    });
    mockUseStories.mockReturnValue({ data: [], isLoading: false });
    renderWithProviders(<EpicDetailPage />);
    expect(screen.queryByTestId("epic-detail-roll-out")).not.toBeInTheDocument();
  });

  it("shows Edit and Delete buttons while no descendant Task has started", () => {
    mockUseEpic.mockReturnValue({
      data: makeEpic({ progress: { totalTasks: 2, doneTasks: 0, startedTasks: 0 } }),
      isLoading: false,
    });
    mockUseStories.mockReturnValue({ data: [], isLoading: false });
    renderWithProviders(<EpicDetailPage />);
    expect(screen.getByTestId("epic-edit-button")).toBeInTheDocument();
    expect(screen.getByTestId("epic-delete-button")).toBeInTheDocument();
  });

  it("hides Edit and Delete buttons once a descendant Task has started", () => {
    mockUseEpic.mockReturnValue({
      data: makeEpic({ progress: { totalTasks: 2, doneTasks: 0, startedTasks: 1 } }),
      isLoading: false,
    });
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
        makeStory({ id: "story-ready", title: "Ready Story", readiness: "READY", readyTaskCount: 1 }),
        makeStory({
          id: "story-blocked",
          title: "Blocked Story",
          readiness: "BLOCKED",
          // A blocked Story cascades to its Tasks, so none of them are startable.
          readyTaskCount: 0,
        }),
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
    mockUseStories.mockReturnValue({
      data: [makeStory({ readiness: "BLOCKED", readyTaskCount: 0 })],
      isLoading: false,
    });
    renderWithProviders(<EpicDetailPage />);
    const user = userEvent.setup();

    expect(screen.queryByText(/No stories are ready to start/)).not.toBeInTheDocument();

    await user.click(screen.getByTestId("ready-to-start-toggle"));

    expect(screen.getByText(/No stories are ready to start/)).toBeInTheDocument();
  });

  it("hides a READY Story whose Tasks are all finished", async () => {
    // The reason this filter reads `readyTaskCount` rather than `readiness`: a Story with no
    // blockers stays READY after its work ships, so filtering on readiness alone would keep
    // offering a completed Story as somewhere to start.
    mockUseEpic.mockReturnValue({ data: makeEpic(), isLoading: false });
    mockUseStories.mockReturnValue({
      data: [
        makeStory({
          id: "story-done",
          title: "Finished Story",
          readiness: "READY",
          readyTaskCount: 0,
          progress: { totalTasks: 2, doneTasks: 2, startedTasks: 2 },
        }),
      ],
      isLoading: false,
    });
    renderWithProviders(<EpicDetailPage />);
    expect(screen.getByText("Finished Story")).toBeInTheDocument();

    await userEvent.setup().click(screen.getByTestId("ready-to-start-toggle"));

    expect(screen.queryByText("Finished Story")).not.toBeInTheDocument();
  });

  it("shows priority-specific empty-state copy when only the priority filter yields zero results", async () => {
    mockUseEpic.mockReturnValue({ data: makeEpic(), isLoading: false });
    mockUseStories.mockReturnValue({ data: [makeStory({ priority: "medium" })], isLoading: false });
    renderWithProviders(<EpicDetailPage />);
    const user = userEvent.setup({ pointerEventsCheck: 0 });

    expect(screen.queryByText(/No stories match the selected priority/)).not.toBeInTheDocument();

    await user.click(screen.getByTestId("priority-filter-high"));

    expect(screen.getByText(/No stories match the selected priority/)).toBeInTheDocument();
    expect(screen.queryByText(/No stories are ready to start/)).not.toBeInTheDocument();
  });

  it("shows combined-filter empty-state copy when both the ready-to-start and priority filters are active", async () => {
    mockUseEpic.mockReturnValue({ data: makeEpic(), isLoading: false });
    mockUseStories.mockReturnValue({
      data: [makeStory({ priority: "medium", readiness: "BLOCKED", readyTaskCount: 0 })],
      isLoading: false,
    });
    renderWithProviders(<EpicDetailPage />);
    const user = userEvent.setup({ pointerEventsCheck: 0 });

    await user.click(screen.getByTestId("ready-to-start-toggle"));
    await user.click(screen.getByTestId("priority-filter-high"));

    expect(screen.getByText(/No stories match the current filters/)).toBeInTheDocument();
  });

  // --- Milestone badge & inline assignment ---

  it("renders the milestone badge when the epic has an assigned milestone", () => {
    mockUseEpic.mockReturnValue({
      data: makeEpic({ milestone: { id: "m1", name: "Q3 Launch" } }),
      isLoading: false,
    });
    mockUseStories.mockReturnValue({ data: [], isLoading: false });
    renderWithProviders(<EpicDetailPage />);
    expect(screen.getByTestId("epic-detail-milestone-badge")).toHaveTextContent("Q3 Launch");
  });

  it("renders no milestone badge when the epic is unassigned", () => {
    mockUseEpic.mockReturnValue({ data: makeEpic({ milestone: null }), isLoading: false });
    mockUseStories.mockReturnValue({ data: [], isLoading: false });
    renderWithProviders(<EpicDetailPage />);
    expect(screen.queryByTestId("epic-detail-milestone-badge")).not.toBeInTheDocument();
  });

  it("scopes the inline Milestone selector's options to the Epic's own software project", () => {
    mockUseEpic.mockReturnValue({ data: makeEpic(), isLoading: false });
    mockUseStories.mockReturnValue({ data: [], isLoading: false });
    renderWithProviders(<EpicDetailPage />);
    // makeEpic()'s softwareProject.id is "r1" (see fixture below).
    expect(mockUseMilestones).toHaveBeenCalledWith("r1");
  });

  it("issues an assign PATCH when a milestone is picked from the inline selector", async () => {
    mockUseMilestones.mockReturnValue({ data: { content: [{ id: "m1", name: "Q3 Launch" }] } });
    mockUseEpic.mockReturnValue({ data: makeEpic({ milestone: null }), isLoading: false });
    mockUseStories.mockReturnValue({ data: [], isLoading: false });
    renderWithProviders(<EpicDetailPage />);
    const user = userEvent.setup({ pointerEventsCheck: 0 });

    await user.click(screen.getByTestId("epic-detail-milestone-select"));
    await user.click(screen.getByText("Q3 Launch"));

    expect(mockAssignMilestoneMutate).toHaveBeenCalledWith({ id: "epic-1", milestoneId: "m1" });
  });

  it("issues an assign PATCH with a null milestoneId when None is picked", async () => {
    mockUseMilestones.mockReturnValue({ data: { content: [{ id: "m1", name: "Q3 Launch" }] } });
    mockUseEpic.mockReturnValue({
      data: makeEpic({ milestone: { id: "m1", name: "Q3 Launch" } }),
      isLoading: false,
    });
    mockUseStories.mockReturnValue({ data: [], isLoading: false });
    renderWithProviders(<EpicDetailPage />);
    const user = userEvent.setup({ pointerEventsCheck: 0 });

    await user.click(screen.getByTestId("epic-detail-milestone-select"));
    await user.click(screen.getByTestId("milestone-option-none"));

    expect(mockAssignMilestoneMutate).toHaveBeenCalledWith({ id: "epic-1", milestoneId: null });
  });
});
