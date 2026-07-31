import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import RoadmapGraphDetailPanel, {
  type RoadmapDetailItem,
  type BlockableItemRef,
} from "@/components/roadmap/RoadmapGraphDetailPanel";
import type {
  EpicResponse,
  StoryResponse,
  TaskResponse,
  ExternalBlockerRef,
  DependencyEdgeResponse,
} from "@/lib/types";

vi.mock("@/lib/api", () => ({
  api: {
    get: vi.fn(),
    getPage: vi.fn().mockResolvedValue({ content: [], totalElements: 0, totalPages: 0, number: 0 }),
    post: vi.fn(),
    put: vi.fn(),
    patch: vi.fn(),
    delete: vi.fn(),
  },
}));

import { api } from "@/lib/api";

const mockApi = api as unknown as {
  get: ReturnType<typeof vi.fn>;
  post: ReturnType<typeof vi.fn>;
  delete: ReturnType<typeof vi.fn>;
  getPage: ReturnType<typeof vi.fn>;
};

const epic: EpicResponse = {
  id: "epic-1",
  title: "Add dark mode",
  description: "Epic description",
  motivation: null,
  status: "in_progress",
  stage: "in_progress",
  progress: { totalTasks: 2, doneTasks: 1 },
  softwareProject: { id: "r1", type: "git_repo", name: "backend-api" },
  repos: [],
  createdAt: "2026-04-01T00:00:00Z",
  updatedAt: "2026-04-01T00:00:00Z",
};

const story: StoryResponse = {
  id: "story-1",
  epicId: "epic-1",
  title: "Dark theme toggle",
  description: "Story description",
  status: "backlog",
  readiness: "READY",
  progress: { totalTasks: 1, doneTasks: 0 },
  createdAt: "2026-04-01T00:00:00Z",
  updatedAt: "2026-04-01T00:00:00Z",
};

const task: TaskResponse = {
  id: "task-1",
  storyId: "story-1",
  title: "Implement toggle component",
  description: "Task description",
  status: "done",
  softwareProject: { id: "r1", type: "git_repo", name: "backend-api" },
  repos: [],
  latestRunId: "run-1",
  latestRunStatus: "completed",
  readiness: "READY",
  recentRuns: [],
  totalRunCount: 0,
  createdAt: "2026-04-01T00:00:00Z",
  updatedAt: "2026-04-01T00:00:00Z",
};

const otherTask: TaskResponse = {
  ...task,
  id: "task-2",
  title: "Wire theme context",
};

const externalBlocker: ExternalBlockerRef = {
  itemType: "task",
  itemId: "other-task-1",
  title: "Migrate auth service",
  epicId: "other-epic-1",
  epicTitle: "Auth Overhaul",
  direction: "BLOCKING",
  internalItemId: "task-1",
};

const blockableItems: BlockableItemRef[] = [
  { id: story.id, itemType: "story", title: story.title },
  { id: task.id, itemType: "task", title: task.title },
  { id: otherTask.id, itemType: "task", title: otherTask.title },
];

function renderPanel(props: {
  detail: RoadmapDetailItem;
  dependencies?: DependencyEdgeResponse[];
  externalBlockers?: ExternalBlockerRef[];
}) {
  return renderWithProviders(
    <RoadmapGraphDetailPanel
      detail={props.detail}
      epicId="epic-1"
      dependencies={props.dependencies ?? []}
      blockableItems={blockableItems}
      externalBlockers={props.externalBlockers ?? []}
    />,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe("RoadmapGraphDetailPanel", () => {
  it.each<[string, RoadmapDetailItem]>([
    ["epic", { itemType: "epic", item: epic }],
    ["story", { itemType: "story", item: story }],
    ["task", { itemType: "task", item: task }],
  ])("shows title, status, and description for a %s node", (_label, detail) => {
    renderPanel({ detail });

    expect(screen.getByTestId("roadmap-detail-title")).toHaveTextContent(detail.item.title);
    expect(screen.getByTestId("roadmap-detail-status")).toHaveTextContent(
      detail.item.status.replace("_", " "),
    );
    expect(screen.getByTestId("roadmap-detail-description")).toHaveTextContent(
      detail.item.description,
    );
  });

  it("renders the run-history component only for a Task node", async () => {
    const { rerender } = renderPanel({ detail: { itemType: "epic", item: epic } });
    expect(screen.queryByTestId("task-run-history-list")).not.toBeInTheDocument();

    rerender(
      <RoadmapGraphDetailPanel
        detail={{ itemType: "story", item: story }}
        epicId="epic-1"
        dependencies={[]}
        blockableItems={blockableItems}
        externalBlockers={[]}
      />,
    );
    expect(screen.queryByTestId("task-run-history-list")).not.toBeInTheDocument();

    rerender(
      <RoadmapGraphDetailPanel
        detail={{ itemType: "task", item: task }}
        epicId="epic-1"
        dependencies={[]}
        blockableItems={blockableItems}
        externalBlockers={[]}
      />,
    );
    await waitFor(() =>
      expect(
        screen.getByText("No runs yet. Start the task to launch the first one."),
      ).toBeInTheDocument(),
    );
  });

  it("shows a readiness badge for a BLOCKED Story/Task but not an Epic", () => {
    const blockedTask: TaskResponse = { ...task, readiness: "BLOCKED" };
    renderPanel({ detail: { itemType: "task", item: blockedTask } });
    expect(screen.getByTestId("roadmap-detail-readiness-badge")).toBeInTheDocument();
  });

  it("shows no readiness badge for a READY Story/Task", () => {
    renderPanel({ detail: { itemType: "task", item: task } });
    expect(screen.queryByTestId("roadmap-detail-readiness-badge")).not.toBeInTheDocument();
  });

  it("renders the embedded recentRuns directly, with no follow-up runs request", async () => {
    const taskWithRuns: TaskResponse = {
      ...task,
      recentRuns: [
        {
          id: "run-1",
          graphTemplateId: "tmpl-1",
          templateName: "Feature Development",
          name: null,
          status: "completed",
          startedAt: "2026-04-01T00:00:00Z",
          completedAt: "2026-04-01T01:00:00Z",
          createdAt: "2026-04-01T00:00:00Z",
          softwareProject: null,
        },
      ],
      totalRunCount: 1,
    };
    renderPanel({ detail: { itemType: "task", item: taskWithRuns } });

    expect(await screen.findByTestId("task-run-history-item")).toBeInTheDocument();
    expect(mockApi.getPage).not.toHaveBeenCalled();
  });

  it("shows the truncated-history count when totalRunCount exceeds the embedded recentRuns", () => {
    const taskWithMoreRuns: TaskResponse = { ...task, recentRuns: [], totalRunCount: 7 };
    renderPanel({ detail: { itemType: "task", item: taskWithMoreRuns } });

    expect(screen.getByTestId("roadmap-detail-run-history-total")).toHaveTextContent("showing 0 of 7");
  });

  it("renders no external-blocker badges when externalBlockers is empty", () => {
    renderPanel({ detail: { itemType: "task", item: task } });
    expect(screen.queryByTestId("roadmap-external-blockers")).not.toBeInTheDocument();
    expect(screen.queryByTestId("roadmap-external-blocker-badge")).not.toBeInTheDocument();
  });

  it("renders an external-blocker badge per entry when externalBlockers is non-empty", () => {
    renderPanel({ detail: { itemType: "task", item: task }, externalBlockers: [externalBlocker] });
    expect(screen.getByTestId("roadmap-external-blockers")).toBeInTheDocument();
    expect(screen.getByTestId("roadmap-external-blocker-badge")).toHaveTextContent(
      "Migrate auth service",
    );
    expect(screen.getByTestId("roadmap-external-blocker-badge")).toHaveTextContent("Auth Overhaul");
    expect(screen.getByTestId("roadmap-external-blocker-badge")).toHaveAttribute(
      "href",
      "/roadmap/epics/other-epic-1",
    );
  });

  it("links the external-blocker badge to its owning Epic's detail route", async () => {
    const user = userEvent.setup();
    renderPanel({ detail: { itemType: "task", item: task }, externalBlockers: [externalBlocker] });

    const link = screen.getByTestId("roadmap-external-blocker-badge");
    expect(link.tagName).toBe("A");
    await user.click(link);

    // MemoryRouter has no <Routes> configured in this test's provider tree, so
    // there's no page content to assert post-navigation — the href itself is
    // the navigation target the click resolves to.
    expect(link).toHaveAttribute("href", `/roadmap/epics/${externalBlocker.epicId}`);
  });

  it("filters external blockers to the ones touching the selected Story/Task's internalItemId", () => {
    // Same external item ("other-task-1") touching two different in-Epic
    // items — the shape RoadmapGraphServiceTest's
    // getGraph_externalBlockerTouchingMultipleInternalItems_eachRefHasDistinctInternalItemId
    // proves the backend now emits. Selecting task-1 must show only the ref
    // whose internalItemId is task-1, not task-2's.
    const blockerForOtherTask: ExternalBlockerRef = { ...externalBlocker, internalItemId: "task-2" };
    renderPanel({
      detail: { itemType: "task", item: task },
      externalBlockers: [externalBlocker, blockerForOtherTask],
    });

    expect(screen.getAllByTestId("roadmap-external-blocker-badge")).toHaveLength(1);
  });

  it("shows no external blockers for a Story/Task when none target its internalItemId", () => {
    renderPanel({ detail: { itemType: "task", item: otherTask }, externalBlockers: [externalBlocker] });
    expect(screen.queryByTestId("roadmap-external-blockers")).not.toBeInTheDocument();
  });

  it("shows every external blocker for an Epic node regardless of internalItemId", () => {
    const blockerForOtherTask: ExternalBlockerRef = { ...externalBlocker, internalItemId: "task-2" };
    renderPanel({
      detail: { itemType: "epic", item: epic },
      externalBlockers: [externalBlocker, blockerForOtherTask],
    });

    expect(screen.getAllByTestId("roadmap-external-blocker-badge")).toHaveLength(2);
  });

  it("groups external blockers under 'Blocked by' when the external item blocks the selected item", () => {
    renderPanel({ detail: { itemType: "task", item: task }, externalBlockers: [externalBlocker] });
    expect(screen.getByText("Blocked by (other Epics)")).toBeInTheDocument();
    expect(screen.queryByText("Blocking (other Epics)")).not.toBeInTheDocument();
  });

  it("groups external blockers under 'Blocking' — not 'Blocked by' — when the selected item blocks the external item", () => {
    // Regression test: direction === "BLOCKED" means the in-Epic item blocks
    // the external one, the reverse of `externalBlocker`. Before this fix,
    // every entry rendered under one undifferentiated "External blockers"
    // heading regardless of direction, so a BLOCKED-direction entry read as
    // if the external item were blocking the selected item — the opposite
    // of what actually happened.
    const weBlockThem: ExternalBlockerRef = { ...externalBlocker, direction: "BLOCKED" };
    renderPanel({ detail: { itemType: "task", item: task }, externalBlockers: [weBlockThem] });

    expect(screen.getByText("Blocking (other Epics)")).toBeInTheDocument();
    expect(screen.queryByText("Blocked by (other Epics)")).not.toBeInTheDocument();
    expect(screen.getByTestId("roadmap-external-blocker-badge")).toHaveTextContent(
      "Migrate auth service",
    );
  });

  it("shows both direction groups when the selected item has blockers in both directions", () => {
    const weBlockThem: ExternalBlockerRef = {
      ...externalBlocker,
      itemId: "other-task-2",
      title: "Rework billing export",
      direction: "BLOCKED",
    };
    renderPanel({
      detail: { itemType: "task", item: task },
      externalBlockers: [externalBlocker, weBlockThem],
    });

    expect(screen.getByText("Blocked by (other Epics)")).toBeInTheDocument();
    expect(screen.getByText("Blocking (other Epics)")).toBeInTheDocument();
    expect(screen.getAllByTestId("roadmap-external-blocker-badge")).toHaveLength(2);
  });

  it("does not render a 'Blocked by' section for an Epic node", () => {
    renderPanel({ detail: { itemType: "epic", item: epic } });
    expect(screen.queryByTestId("roadmap-blocking-dependencies")).not.toBeInTheDocument();
  });

  it("lists an existing blocking dependency for a Task node", () => {
    renderPanel({
      detail: { itemType: "task", item: task },
      dependencies: [
        {
          id: "dep-1",
          blockingItemType: "task",
          blockingItemId: otherTask.id,
          blockedItemType: "task",
          blockedItemId: task.id,
          createdAt: "2026-04-01T00:00:00Z",
        },
      ],
    });

    expect(screen.getByTestId("roadmap-blocking-dependency-badge")).toHaveTextContent(
      "Wire theme context",
    );
  });

  it("creates a new blocking dependency via the picker + Add blocker button", async () => {
    mockApi.post.mockResolvedValue({
      id: "dep-new",
      blockingItemType: "story",
      blockingItemId: story.id,
      blockedItemType: "task",
      blockedItemId: task.id,
      createdAt: "2026-04-01T00:00:00Z",
    });
    renderPanel({ detail: { itemType: "task", item: task } });

    const user = userEvent.setup();
    await user.click(screen.getByTestId("roadmap-add-blocker-select"));
    await user.click(await screen.findByText("Dark theme toggle (story)"));
    await user.click(screen.getByTestId("roadmap-add-blocker-submit"));

    await waitFor(() =>
      expect(mockApi.post).toHaveBeenCalledWith("/dependencies", {
        blockingItemType: "story",
        blockingItemId: story.id,
        blockedItemType: "task",
        blockedItemId: task.id,
      }),
    );
  });

  it("excludes an item already listed as a blocker from the add-blocker picker", async () => {
    renderPanel({
      detail: { itemType: "task", item: task },
      dependencies: [
        {
          id: "dep-1",
          blockingItemType: "task",
          blockingItemId: otherTask.id,
          blockedItemType: "task",
          blockedItemId: task.id,
          createdAt: "2026-04-01T00:00:00Z",
        },
      ],
    });

    const user = userEvent.setup();
    await user.click(screen.getByTestId("roadmap-add-blocker-select"));

    // otherTask is already a blocker of task-1, so it must not be offered again.
    expect(screen.queryByText("Wire theme context (task)")).not.toBeInTheDocument();
    // story-1 isn't blocking task-1 yet, so it's still a valid option.
    expect(await screen.findByText("Dark theme toggle (story)")).toBeInTheDocument();
  });

  it("removes a blocking dependency via its remove button", async () => {
    mockApi.delete.mockResolvedValue(undefined);
    renderPanel({
      detail: { itemType: "task", item: task },
      dependencies: [
        {
          id: "dep-1",
          blockingItemType: "task",
          blockingItemId: otherTask.id,
          blockedItemType: "task",
          blockedItemId: task.id,
          createdAt: "2026-04-01T00:00:00Z",
        },
      ],
    });

    const user = userEvent.setup();
    await user.click(screen.getByTestId("roadmap-blocking-dependency-remove"));

    await waitFor(() => expect(mockApi.delete).toHaveBeenCalledWith("/dependencies/dep-1"));
  });

  it("resets the uncommitted add-blocker picker selection when a different node is selected", async () => {
    const { rerender } = renderPanel({ detail: { itemType: "task", item: task } });

    const user = userEvent.setup();
    await user.click(screen.getByTestId("roadmap-add-blocker-select"));
    await user.click(await screen.findByText("Dark theme toggle (story)"));
    expect(screen.getByTestId("roadmap-add-blocker-submit")).toBeEnabled();

    // Switch to a different node (task-2) without ever clicking "Add blocker".
    // The uncommitted selection for task-1 must not leak into task-2's picker
    // and silently enable submitting an edge the user never chose here.
    rerender(
      <RoadmapGraphDetailPanel
        detail={{ itemType: "task", item: otherTask }}
        epicId="epic-1"
        dependencies={[]}
        blockableItems={blockableItems}
        externalBlockers={[]}
      />,
    );

    expect(screen.getByTestId("roadmap-add-blocker-submit")).toBeDisabled();
    expect(screen.queryByText("Dark theme toggle (story)")).not.toBeInTheDocument();
  });

  it("disables the remove button while the delete is pending, preventing a duplicate request", async () => {
    let resolveDelete: () => void = () => {};
    mockApi.delete.mockReturnValue(
      new Promise<void>((resolve) => {
        resolveDelete = resolve;
      }),
    );
    renderPanel({
      detail: { itemType: "task", item: task },
      dependencies: [
        {
          id: "dep-1",
          blockingItemType: "task",
          blockingItemId: otherTask.id,
          blockedItemType: "task",
          blockedItemId: task.id,
          createdAt: "2026-04-01T00:00:00Z",
        },
      ],
    });

    const user = userEvent.setup();
    const removeButton = screen.getByTestId("roadmap-blocking-dependency-remove");
    await user.click(removeButton);

    await waitFor(() => expect(removeButton).toBeDisabled());
    // A second click while the first delete is still in flight must not fire a second request.
    await user.click(removeButton);
    expect(mockApi.delete).toHaveBeenCalledTimes(1);

    resolveDelete();
    await waitFor(() => expect(removeButton).not.toBeDisabled());
  });

  describe("blocking chain section", () => {
    it("renders for a Task/Story with readiness BLOCKED, fetching from the blocking-chain endpoint", async () => {
      const blockedTask: TaskResponse = { ...task, readiness: "BLOCKED" };
      mockApi.get.mockResolvedValue({
        itemType: "task",
        itemId: blockedTask.id,
        title: blockedTask.title,
        status: blockedTask.status,
        readiness: "BLOCKED",
        blockedBy: [
          {
            itemType: "story",
            itemId: "story-2",
            title: "Upstream blocker",
            status: "in_progress",
            blockedBy: [],
          },
        ],
        truncated: false,
      });

      renderPanel({ detail: { itemType: "task", item: blockedTask } });

      expect(await screen.findByTestId("roadmap-blocking-chain")).toBeInTheDocument();
      expect(mockApi.get).toHaveBeenCalledWith(`/tasks/${blockedTask.id}/blocking-chain`);
    });

    it("is absent for a READY item, and never calls the blocking-chain endpoint", () => {
      renderPanel({ detail: { itemType: "task", item: task } });

      expect(screen.queryByTestId("roadmap-blocking-chain")).not.toBeInTheDocument();
      expect(screen.queryByTestId("roadmap-blocking-chain-loading")).not.toBeInTheDocument();
      expect(mockApi.get).not.toHaveBeenCalled();
    });

    it("is absent for an Epic, which has no readiness field at all", () => {
      renderPanel({ detail: { itemType: "epic", item: epic } });

      expect(screen.queryByTestId("roadmap-blocking-chain")).not.toBeInTheDocument();
      expect(screen.queryByTestId("roadmap-blocking-chain-loading")).not.toBeInTheDocument();
      expect(mockApi.get).not.toHaveBeenCalled();
    });

    it("shows a loading state while the blocking-chain query is in flight", async () => {
      const blockedStory: StoryResponse = { ...story, readiness: "BLOCKED" };
      let resolveGet: (value: unknown) => void = () => {};
      mockApi.get.mockReturnValue(
        new Promise((resolve) => {
          resolveGet = resolve;
        }),
      );

      renderPanel({ detail: { itemType: "story", item: blockedStory } });

      expect(await screen.findByTestId("roadmap-blocking-chain-loading")).toBeInTheDocument();
      expect(screen.queryByTestId("roadmap-blocking-chain")).not.toBeInTheDocument();

      resolveGet({
        itemType: "story",
        itemId: blockedStory.id,
        title: blockedStory.title,
        status: blockedStory.status,
        readiness: "BLOCKED",
        blockedBy: [],
        truncated: false,
      });
    });
  });
});
