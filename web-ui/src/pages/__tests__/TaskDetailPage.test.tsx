import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import TaskDetailPage from "@/pages/TaskDetailPage";
import type { TaskResponse } from "@/lib/types";

const mockUseTask = vi.fn();
const mockUseStory = vi.fn();
const mockUseTaskRuns = vi.fn();
const mockUseBlockingChain = vi.fn();
const mockDeleteMutate = vi.fn();
const mockStartMutate = vi.fn();
const mockCompleteMutate = vi.fn();
const mockUpdateTaskMutate = vi.fn();
const mockDesignMutate = vi.fn();
const mockCloseDeliveredMutate = vi.fn();
let mockStartTaskError: unknown = null;
let mockCloseDeliveredError: unknown = null;
let mockCloseDeliveredPending = false;

vi.mock("@/hooks/useTasks", () => ({
  useTask: (id: string) => mockUseTask(id),
  useDeleteTask: () => ({
    mutate: mockDeleteMutate,
    isPending: false,
    isError: false,
    reset: vi.fn(),
  }),
  useUpdateTask: () => ({
    mutate: mockUpdateTaskMutate,
    isPending: false,
    isError: false,
    error: null,
    reset: vi.fn(),
  }),
  useStartTask: () => ({
    mutate: mockStartMutate,
    isPending: false,
    isError: mockStartTaskError != null,
    error: mockStartTaskError,
    reset: vi.fn(),
  }),
  useCompleteTask: () => ({
    mutate: mockCompleteMutate,
    isPending: false,
    isError: false,
    reset: vi.fn(),
  }),
  useCloseTaskAsDelivered: () => ({
    mutate: mockCloseDeliveredMutate,
    isPending: mockCloseDeliveredPending,
    isError: mockCloseDeliveredError != null,
    error: mockCloseDeliveredError,
    reset: vi.fn(),
  }),
}));

vi.mock("@/hooks/useStories", () => ({
  useStory: (id: string | undefined) => mockUseStory(id),
}));

vi.mock("@/hooks/useStorySpec", () => ({
  useDesignStory: () => ({ mutate: mockDesignMutate, isPending: false }),
}));

vi.mock("@/hooks/useTaskRuns", () => ({
  useTaskRuns: (id: string) => mockUseTaskRuns(id),
}));

vi.mock("@/hooks/useBlockingChain", () => ({
  useBlockingChain: (itemType: string, itemId: string, enabled: boolean) =>
    mockUseBlockingChain(itemType, itemId, enabled),
}));

vi.mock("@/hooks/useRoadmapSubscription", () => ({
  useRoadmapSubscription: vi.fn(),
}));

vi.mock("react-router", async (importOriginal) => {
  const actual = await importOriginal<typeof import("react-router")>();
  return {
    ...actual,
    useParams: () => ({ id: "task-1" }),
    useNavigate: () => vi.fn(),
  };
});

beforeEach(() => {
  mockUseTask.mockReset();
  mockUseStory.mockReset();
  mockUseTaskRuns.mockReset();
  mockUseBlockingChain.mockReset();
  mockDeleteMutate.mockReset();
  mockStartMutate.mockReset();
  mockCompleteMutate.mockReset();
  mockUpdateTaskMutate.mockReset();
  mockDesignMutate.mockReset();
  mockCloseDeliveredMutate.mockReset();
  mockStartTaskError = null;
  mockCloseDeliveredError = null;
  mockCloseDeliveredPending = false;
  mockUseTaskRuns.mockReturnValue({ data: undefined, isLoading: false });
  mockUseStory.mockReturnValue({ data: undefined });
  mockUseBlockingChain.mockReturnValue({ data: undefined, isLoading: false });
});

function makeTask(overrides: Partial<TaskResponse> = {}): TaskResponse {
  return {
    id: "task-1",
    storyId: "story-1",
    title: "Implement toggle switch",
    description: "desc",
    status: "backlog",
    softwareProject: { id: "r1", type: "git_repo", name: "backend-api" },
    repos: [],
    latestRunId: null,
    latestRunStatus: null,
    readiness: null,
    recentRuns: [],
    totalRunCount: 0,
    createdAt: "2026-04-01T00:00:00Z",
    updatedAt: "2026-04-01T00:00:00Z",
    priority: "medium",
    ...overrides,
  };
}

describe("TaskDetailPage", () => {
  it("renders task title and status", () => {
    mockUseTask.mockReturnValue({ data: makeTask(), isLoading: false });
    renderWithProviders(<TaskDetailPage />);
    expect(screen.getByTestId("task-detail-title")).toHaveTextContent("Implement toggle switch");
    expect(screen.getByTestId("task-detail-status")).toHaveTextContent("backlog");
  });

  it("renders the Task LevelBadge", () => {
    mockUseTask.mockReturnValue({ data: makeTask(), isLoading: false });
    renderWithProviders(<TaskDetailPage />);
    expect(screen.getByTestId("level-badge-task")).toHaveTextContent("Task");
  });

  it('renders a "Back to Story" link to the parent Story once it resolves', () => {
    mockUseTask.mockReturnValue({ data: makeTask(), isLoading: false });
    mockUseStory.mockReturnValue({
      data: { id: "story-1", epicId: "epic-1", title: "Parent story" },
    });
    renderWithProviders(<TaskDetailPage />);
    const link = screen.getByRole("link", { name: /Back to Story/ });
    expect(link).toHaveAttribute("href", "/roadmap/epics/epic-1/stories/story-1");
  });

  it('falls back the "Back to Story" link to /roadmap while the parent Story is pending', () => {
    mockUseTask.mockReturnValue({ data: makeTask(), isLoading: false });
    mockUseStory.mockReturnValue({ data: undefined });
    renderWithProviders(<TaskDetailPage />);
    const link = screen.getByRole("link", { name: /Back to Story/ });
    expect(link).toHaveAttribute("href", "/roadmap");
  });

  it("shows Start, Edit, and Delete buttons for a backlog task", () => {
    mockUseTask.mockReturnValue({ data: makeTask({ status: "backlog" }), isLoading: false });
    renderWithProviders(<TaskDetailPage />);
    expect(screen.getByTestId("task-start-button")).toBeInTheDocument();
    expect(screen.getByTestId("task-edit-button")).toBeInTheDocument();
    expect(screen.getByTestId("task-delete-button")).toBeInTheDocument();
  });

  it("hides the Edit button once a task has left backlog", () => {
    mockUseTask.mockReturnValue({ data: makeTask({ status: "in_progress" }), isLoading: false });
    renderWithProviders(<TaskDetailPage />);
    expect(screen.queryByTestId("task-edit-button")).not.toBeInTheDocument();
  });

  it("hides the Edit button for a done task", () => {
    mockUseTask.mockReturnValue({
      data: makeTask({ status: "done", latestRunStatus: "completed" }),
      isLoading: false,
    });
    renderWithProviders(<TaskDetailPage />);
    expect(screen.queryByTestId("task-edit-button")).not.toBeInTheDocument();
  });

  it("clicking Edit opens the Edit Task dialog pre-populated from the task", async () => {
    mockUseTask.mockReturnValue({ data: makeTask({ status: "backlog" }), isLoading: false });
    renderWithProviders(<TaskDetailPage />);

    const user = userEvent.setup();
    await user.click(screen.getByTestId("task-edit-button"));

    expect(screen.getByTestId("edit-task-title")).toHaveValue("Implement toggle switch");
  });

  it("fetches the blocking chain for the current task id, not a `readiness` field on the Task response", () => {
    // The obvious-but-wrong approach: DefaultTaskService documents that `readiness` is always
    // null on this single-item read path (get/create/update/start), so keying the button off
    // `task.readiness` would never disable it. This asserts the actual source of truth is used.
    mockUseTask.mockReturnValue({ data: makeTask({ status: "backlog" }), isLoading: false });
    renderWithProviders(<TaskDetailPage />);
    expect(mockUseBlockingChain).toHaveBeenCalledWith("task", "task-1", true);
  });

  it("enables the Start button when the blocking chain resolves READY", () => {
    mockUseTask.mockReturnValue({ data: makeTask({ status: "backlog" }), isLoading: false });
    mockUseBlockingChain.mockReturnValue({
      data: {
        itemType: "task",
        itemId: "task-1",
        title: "Implement toggle switch",
        status: "backlog",
        readiness: "READY",
        blockedBy: [],
        truncated: false,
      },
      isLoading: false,
    });
    renderWithProviders(<TaskDetailPage />);
    expect(screen.getByTestId("task-start-button")).toBeEnabled();
    expect(screen.queryByTestId("task-start-blocked-tooltip")).not.toBeInTheDocument();
  });

  it("disables the Start button and shows the blocker titles in a tooltip when the blocking chain resolves BLOCKED", async () => {
    mockUseTask.mockReturnValue({ data: makeTask({ status: "backlog" }), isLoading: false });
    mockUseBlockingChain.mockReturnValue({
      data: {
        itemType: "task",
        itemId: "task-1",
        title: "Implement toggle switch",
        status: "backlog",
        readiness: "BLOCKED",
        blockedBy: [
          { itemType: "task", itemId: "task-2", title: "Wire up API client", status: "in_progress", blockedBy: [] },
          { itemType: "story", itemId: "story-2", title: "Design the settings panel", status: "backlog", blockedBy: [] },
        ],
        truncated: false,
      },
      isLoading: false,
    });
    renderWithProviders(<TaskDetailPage />);
    const user = userEvent.setup();

    const startButton = screen.getByTestId("task-start-button");
    expect(startButton).toBeDisabled();

    await user.hover(screen.getByTestId("task-start-button-tooltip-trigger"));

    await waitFor(() => {
      const tooltip = screen.getByTestId("task-start-blocked-tooltip");
      expect(tooltip).toHaveTextContent("Wire up API client");
      expect(tooltip).toHaveTextContent("Design the settings panel");
    });
  });

  it("also disables Restart (same requireReady gate as Start) when the task became BLOCKED after it started", () => {
    mockUseTask.mockReturnValue({
      data: makeTask({ status: "in_progress", latestRunStatus: "failed" }),
      isLoading: false,
    });
    mockUseBlockingChain.mockReturnValue({
      data: {
        itemType: "task",
        itemId: "task-1",
        title: "Implement toggle switch",
        status: "in_progress",
        readiness: "BLOCKED",
        blockedBy: [
          { itemType: "task", itemId: "task-2", title: "Wire up API client", status: "in_progress", blockedBy: [] },
        ],
        truncated: false,
      },
      isLoading: false,
    });
    renderWithProviders(<TaskDetailPage />);
    expect(screen.getByTestId("task-restart-button")).toBeDisabled();
  });

  describe("when the parent Story's spec keeps the Task from starting", () => {
    const readyChain = {
      data: {
        itemType: "task",
        itemId: "task-1",
        title: "Implement toggle switch",
        status: "backlog",
        readiness: "READY",
        blockedBy: [],
        truncated: false,
      },
      isLoading: false,
    };

    function parentStory(overrides: Record<string, unknown>) {
      return {
        data: {
          id: "story-1",
          epicId: "epic-1",
          title: "Parent story",
          specVersion: 1,
          specUnderRevision: false,
          openDesignRunId: null,
          ...overrides,
        },
      };
    }

    beforeEach(() => {
      mockUseBlockingChain.mockReturnValue(readyChain);
    });

    it("enables Start once the Story has an approved spec and no revision", () => {
      mockUseTask.mockReturnValue({ data: makeTask({ status: "backlog" }), isLoading: false });
      mockUseStory.mockReturnValue(parentStory({}));
      renderWithProviders(<TaskDetailPage />);
      expect(screen.getByTestId("task-start-button")).toBeEnabled();
    });

    it('disables Start with "Story spec not approved yet" while the Story has no spec', async () => {
      const user = userEvent.setup();
      mockUseTask.mockReturnValue({ data: makeTask({ status: "backlog" }), isLoading: false });
      mockUseStory.mockReturnValue(parentStory({ specVersion: null }));
      renderWithProviders(<TaskDetailPage />);

      expect(screen.getByTestId("task-start-button")).toBeDisabled();
      await user.hover(screen.getByTestId("task-start-button-tooltip-trigger"));

      await waitFor(() => {
        expect(screen.getByTestId("task-start-blocked-tooltip")).toHaveTextContent("Story spec not approved yet");
      });
    });

    it('disables Start with "Story spec under revision" while a revision is open', async () => {
      const user = userEvent.setup();
      mockUseTask.mockReturnValue({ data: makeTask({ status: "backlog" }), isLoading: false });
      mockUseStory.mockReturnValue(
        parentStory({ specVersion: 2, specUnderRevision: true, openDesignRunId: "run-9" })
      );
      renderWithProviders(<TaskDetailPage />);

      expect(screen.getByTestId("task-start-button")).toBeDisabled();
      await user.hover(screen.getByTestId("task-start-button-tooltip-trigger"));

      await waitFor(() => {
        const tooltip = screen.getByTestId("task-start-blocked-tooltip");
        expect(tooltip).toHaveTextContent("Story spec under revision");
        expect(tooltip).not.toHaveTextContent("Blocked by");
      });
    });

    it("also disables Restart while the Story's spec is under revision", () => {
      mockUseTask.mockReturnValue({
        data: makeTask({ status: "in_progress", latestRunStatus: "failed" }),
        isLoading: false,
      });
      mockUseStory.mockReturnValue(
        parentStory({ specVersion: 2, specUnderRevision: true, openDesignRunId: "run-9" })
      );
      renderWithProviders(<TaskDetailPage />);
      expect(screen.getByTestId("task-restart-button")).toBeDisabled();
    });

    it("names both the spec reason and the blockers when both apply", async () => {
      const user = userEvent.setup();
      mockUseTask.mockReturnValue({ data: makeTask({ status: "backlog" }), isLoading: false });
      mockUseStory.mockReturnValue(parentStory({ specVersion: null }));
      mockUseBlockingChain.mockReturnValue({
        data: {
          ...readyChain.data,
          readiness: "BLOCKED",
          blockedBy: [
            { itemType: "task", itemId: "task-2", title: "Wire up API client", status: "in_progress", blockedBy: [] },
          ],
        },
        isLoading: false,
      });
      renderWithProviders(<TaskDetailPage />);

      await user.hover(screen.getByTestId("task-start-button-tooltip-trigger"));

      await waitFor(() => {
        const tooltip = screen.getByTestId("task-start-blocked-tooltip");
        expect(tooltip).toHaveTextContent("Story spec not approved yet");
        expect(tooltip).toHaveTextContent("Wire up API client");
      });
    });

    it("shows the server's reason when it refuses a start the page allowed", async () => {
      const user = userEvent.setup();
      const { ApiError } = await import("@/lib/api");
      mockStartTaskError = new ApiError(409, "Cannot start this Task: Story spec under revision");
      mockUseTask.mockReturnValue({ data: makeTask({ status: "backlog" }), isLoading: false });
      mockUseStory.mockReturnValue(parentStory({}));
      renderWithProviders(<TaskDetailPage />);

      await user.click(screen.getByTestId("task-start-button"));

      expect(screen.getByTestId("start-task-error")).toHaveTextContent(
        "Cannot start this Task: Story spec under revision"
      );
    });

    it("says inline why Start is disabled, as the button's description", () => {
      mockUseTask.mockReturnValue({ data: makeTask({ status: "backlog" }), isLoading: false });
      mockUseStory.mockReturnValue(parentStory({ specVersion: null }));
      renderWithProviders(<TaskDetailPage />);

      expect(screen.getByTestId("task-start-reason")).toHaveTextContent("Story spec not approved yet");
      expect(screen.getByTestId("task-start-button")).toHaveAccessibleDescription(
        /Story spec not approved yet/
      );
    });

    it("offers to design the Story when its spec is not approved and no design is running", async () => {
      const user = userEvent.setup();
      mockUseTask.mockReturnValue({ data: makeTask({ status: "backlog" }), isLoading: false });
      mockUseStory.mockReturnValue(parentStory({ specVersion: null }));
      renderWithProviders(<TaskDetailPage />);

      await user.click(screen.getByTestId("task-design-story-button"));

      expect(mockDesignMutate).toHaveBeenCalledWith("story-1");
    });

    it("links the running design instead of offering another one", () => {
      mockUseTask.mockReturnValue({ data: makeTask({ status: "backlog" }), isLoading: false });
      mockUseStory.mockReturnValue(parentStory({ specVersion: null, openDesignRunId: "run-7" }));
      renderWithProviders(<TaskDetailPage />);

      expect(screen.queryByTestId("task-design-story-button")).not.toBeInTheDocument();
      expect(screen.getByRole("link", { name: "Story Design run" })).toHaveAttribute("href", "/runs/run-7");
    });

    it("links the revision run while the spec is under revision", () => {
      mockUseTask.mockReturnValue({ data: makeTask({ status: "backlog" }), isLoading: false });
      mockUseStory.mockReturnValue(
        parentStory({ specVersion: 2, specUnderRevision: true, openDesignRunId: "run-9" })
      );
      renderWithProviders(<TaskDetailPage />);

      expect(screen.getByTestId("task-start-reason")).toHaveTextContent("Story spec under revision");
      expect(screen.queryByTestId("task-design-story-button")).not.toBeInTheDocument();
      expect(screen.getByRole("link", { name: "revision run" })).toHaveAttribute("href", "/runs/run-9");
    });

    it("names the blockers inline too", () => {
      mockUseTask.mockReturnValue({ data: makeTask({ status: "backlog" }), isLoading: false });
      mockUseStory.mockReturnValue(parentStory({}));
      mockUseBlockingChain.mockReturnValue({
        data: {
          ...readyChain.data,
          readiness: "BLOCKED",
          blockedBy: [
            { itemType: "task", itemId: "task-2", title: "Wire up API client", status: "in_progress", blockedBy: [] },
          ],
        },
        isLoading: false,
      });
      renderWithProviders(<TaskDetailPage />);

      expect(screen.getByTestId("task-start-reason")).toHaveTextContent("Blocked by: Wire up API client");
      expect(screen.queryByTestId("task-design-story-button")).not.toBeInTheDocument();
    });

    it("shows no reason while Start is enabled", () => {
      mockUseTask.mockReturnValue({ data: makeTask({ status: "backlog" }), isLoading: false });
      mockUseStory.mockReturnValue(parentStory({}));
      renderWithProviders(<TaskDetailPage />);

      expect(screen.queryByTestId("task-start-reason")).not.toBeInTheDocument();
      expect(screen.getByTestId("task-start-button")).not.toHaveAttribute("aria-describedby");
    });

    it("does not hold Start back on the spec while the Story is still loading", () => {
      mockUseTask.mockReturnValue({ data: makeTask({ status: "backlog" }), isLoading: false });
      mockUseStory.mockReturnValue({ data: undefined });
      renderWithProviders(<TaskDetailPage />);
      expect(screen.getByTestId("task-start-button")).toBeEnabled();
    });
  });

  it("shows a disabled Complete button while the run is not yet terminal-completed", () => {
    mockUseTask.mockReturnValue({
      data: makeTask({ status: "in_progress", latestRunStatus: "running" }),
      isLoading: false,
    });
    renderWithProviders(<TaskDetailPage />);
    expect(screen.getByTestId("task-complete-button")).toBeDisabled();
    expect(screen.queryByTestId("task-restart-button")).not.toBeInTheDocument();
  });

  it("enables Complete once the latest run has completed", () => {
    mockUseTask.mockReturnValue({
      data: makeTask({ status: "in_progress", latestRunStatus: "completed" }),
      isLoading: false,
    });
    renderWithProviders(<TaskDetailPage />);
    expect(screen.getByTestId("task-complete-button")).toBeEnabled();
  });

  it("shows Restart when the latest run failed", () => {
    mockUseTask.mockReturnValue({
      data: makeTask({ status: "in_progress", latestRunStatus: "failed" }),
      isLoading: false,
    });
    renderWithProviders(<TaskDetailPage />);
    expect(screen.getByTestId("task-restart-button")).toBeInTheDocument();
  });

  it("renders run history from useTaskRuns", () => {
    mockUseTask.mockReturnValue({ data: makeTask(), isLoading: false });
    mockUseTaskRuns.mockReturnValue({
      data: {
        content: [
          {
            id: "run-1",
            graphTemplateId: "tpl-1",
            templateName: "Feature Dev",
            name: null,
            status: "completed",
            startedAt: null,
            completedAt: null,
            createdAt: "2026-04-01T00:00:00Z",
            softwareProject: null,
          },
        ],
        totalElements: 1,
        totalPages: 1,
        number: 0,
        size: 20,
        first: true,
        last: true,
        empty: false,
      },
      isLoading: false,
    });
    renderWithProviders(<TaskDetailPage />);
    expect(screen.getByTestId("task-run-history-list")).toBeInTheDocument();
    expect(screen.getAllByTestId("task-run-history-item")).toHaveLength(1);
  });
  describe("Close as delivered", () => {
    const MERGED_PR = "https://github.com/acme/backend-api/pull/12";
    const OTHER_PR = "https://github.com/acme/backend-api/pull/13";

    async function openDialog(task: Partial<TaskResponse> = {}) {
      mockUseTask.mockReturnValue({ data: makeTask(task), isLoading: false });
      renderWithProviders(<TaskDetailPage />);
      const user = userEvent.setup({ pointerEventsCheck: 0 });
      await user.click(screen.getByTestId("task-close-delivered-button"));
      expect(screen.getByTestId("task-close-delivered-dialog")).toBeInTheDocument();
      return user;
    }

    it.each([
      ["a backlog Task", { status: "backlog" as const, latestRunStatus: null }],
      ["an in-progress Task whose run completed", { status: "in_progress" as const, latestRunStatus: "completed" }],
      ["an in-progress Task whose run failed", { status: "in_progress" as const, latestRunStatus: "failed" }],
      ["an in-progress Task whose run was cancelled", { status: "in_progress" as const, latestRunStatus: "cancelled" }],
    ])("is offered for %s", (_label, overrides) => {
      mockUseTask.mockReturnValue({ data: makeTask(overrides), isLoading: false });
      renderWithProviders(<TaskDetailPage />);
      expect(screen.getByTestId("task-close-delivered-button")).toBeEnabled();
    });

    it.each([
      ["a done Task", { status: "done" as const, latestRunStatus: "completed" }],
      ["an in-progress Task whose run is still running", { status: "in_progress" as const, latestRunStatus: "running" }],
      ["an in-progress Task whose run awaits a decision", { status: "in_progress" as const, latestRunStatus: "awaiting_human" }],
      ["an in-progress Task with no run status yet", { status: "in_progress" as const, latestRunStatus: null }],
    ])("is not offered for %s", (_label, overrides) => {
      mockUseTask.mockReturnValue({ data: makeTask(overrides), isLoading: false });
      renderWithProviders(<TaskDetailPage />);
      expect(screen.queryByTestId("task-close-delivered-button")).not.toBeInTheDocument();
    });

    it("explains what the check does", async () => {
      await openDialog();
      expect(screen.getByTestId("task-close-delivered-dialog")).toHaveTextContent(
        "checks that each pull request is merged into its repository’s default branch, then marks the Task done"
      );
    });

    it("keeps submit disabled until there is a pull request URL and a non-blank note", async () => {
      const user = await openDialog();
      const submit = screen.getByTestId("task-close-delivered-submit");
      expect(submit).toBeDisabled();

      await user.type(screen.getByTestId("task-close-delivered-prs"), "  {Enter}");
      await user.type(screen.getByTestId("task-close-delivered-note"), "Shipped in the hotfix train");
      expect(submit).toBeDisabled();

      await user.type(screen.getByTestId("task-close-delivered-prs"), MERGED_PR);
      expect(submit).toBeEnabled();

      await user.clear(screen.getByTestId("task-close-delivered-note"));
      await user.type(screen.getByTestId("task-close-delivered-note"), "   ");
      expect(submit).toBeDisabled();
    });

    it("keeps submit disabled while the request is pending", async () => {
      mockCloseDeliveredPending = true;
      const user = await openDialog();
      await user.type(screen.getByTestId("task-close-delivered-prs"), MERGED_PR);
      await user.type(screen.getByTestId("task-close-delivered-note"), "Shipped elsewhere");
      expect(screen.getByTestId("task-close-delivered-submit")).toBeDisabled();
    });

    it("sends one trimmed URL per non-blank line and the trimmed note", async () => {
      const user = await openDialog({ status: "in_progress", latestRunStatus: "failed" });
      await user.type(
        screen.getByTestId("task-close-delivered-prs"),
        `  ${MERGED_PR}  {Enter}{Enter}   {Enter}${OTHER_PR}{Enter}`
      );
      await user.type(screen.getByTestId("task-close-delivered-note"), "  Landed via the release branch  ");
      await user.click(screen.getByTestId("task-close-delivered-submit"));

      expect(mockCloseDeliveredMutate).toHaveBeenCalledTimes(1);
      const [vars] = mockCloseDeliveredMutate.mock.calls[0];
      expect(vars).toEqual({
        id: "task-1",
        body: { pullRequestUrls: [MERGED_PR, OTHER_PR], note: "Landed via the release branch" },
      });
    });

    it("shows the server's refusal inline", async () => {
      const { ApiError } = await import("@/lib/api");
      mockCloseDeliveredError = new ApiError(
        400,
        "https://github.com/other/repo/pull/1 is not a repository of this Task's software project"
      );
      await openDialog();
      expect(screen.getByTestId("task-close-delivered-error")).toHaveTextContent(
        "https://github.com/other/repo/pull/1 is not a repository of this Task's software project"
      );
    });

    it("falls back to a generic message when the refusal has no text", async () => {
      const { ApiError } = await import("@/lib/api");
      mockCloseDeliveredError = new ApiError(500, null);
      await openDialog();
      expect(screen.getByTestId("task-close-delivered-error")).toHaveTextContent(
        "Failed to start the delivery check."
      );
    });

    it("closes the dialog once the check has started", async () => {
      mockCloseDeliveredMutate.mockImplementation(
        (_vars: unknown, options?: { onSuccess?: () => void }) => options?.onSuccess?.()
      );
      const user = await openDialog();
      await user.type(screen.getByTestId("task-close-delivered-prs"), MERGED_PR);
      await user.type(screen.getByTestId("task-close-delivered-note"), "Shipped elsewhere");
      await user.click(screen.getByTestId("task-close-delivered-submit"));

      await waitFor(() => {
        expect(screen.queryByTestId("task-close-delivered-dialog")).not.toBeInTheDocument();
      });
    });

    it("keeps the dialog open when the server refuses", async () => {
      const user = await openDialog();
      await user.type(screen.getByTestId("task-close-delivered-prs"), MERGED_PR);
      await user.type(screen.getByTestId("task-close-delivered-note"), "Shipped elsewhere");
      await user.click(screen.getByTestId("task-close-delivered-submit"));

      expect(mockCloseDeliveredMutate).toHaveBeenCalledTimes(1);
      expect(screen.getByTestId("task-close-delivered-dialog")).toBeInTheDocument();
    });
  });
});
