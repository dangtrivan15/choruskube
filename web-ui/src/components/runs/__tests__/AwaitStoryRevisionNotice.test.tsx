import { describe, it, expect, vi, beforeEach } from "vitest";
import { act, screen, waitFor, within } from "@testing-library/react";
import type { IMessage } from "@stomp/stompjs";
import { renderWithProviders } from "@/__tests__/test-utils";
import AwaitStoryRevisionNotice from "../AwaitStoryRevisionNotice";
import type { StoryConflictResponse } from "@/lib/types";

// The real conflicts query runs against a mocked api, so a STOMP event's invalidation is what
// brings in new data — the same path as on the Run page, where no roadmap page is mounted.
vi.mock("@/lib/api", () => ({
  api: { get: vi.fn() },
  ApiError: class extends Error {},
  apiErrorMessage: (_err: unknown, fallback: string) => fallback,
}));

const stompListeners = new Map<string, (message: IMessage) => void>();
vi.mock("@/hooks/useStompSubscription", () => ({
  useStompSubscription: (topic: string | null, onMessage: (message: IMessage) => void) => {
    if (topic) stompListeners.set(topic, onMessage);
  },
}));

import { api } from "@/lib/api";

const mockGet = api.get as unknown as ReturnType<typeof vi.fn>;

function conflict(overrides: Partial<StoryConflictResponse> = {}): StoryConflictResponse {
  return {
    id: "conflict-1",
    taskRunId: "run-1",
    taskId: "task-1",
    taskTitle: "Wire the payment form",
    raisedAgainstVersion: 2,
    revisionRunId: "revision-run",
    createdAt: "2026-10-25T00:00:00Z",
    ...overrides,
  };
}

function hrefs() {
  return within(screen.getByTestId("await-story-revision-notice"))
    .queryAllByRole("link")
    .map((link) => link.getAttribute("href"));
}

function storyEvent(itemId: string) {
  act(() => {
    stompListeners.get("/topic/roadmap-items")?.({
      body: JSON.stringify({ itemType: "story_changed", itemId, status: "backlog" }),
    } as IMessage);
  });
}

beforeEach(() => {
  vi.clearAllMocks();
  stompListeners.clear();
  mockGet.mockResolvedValue([]);
});

describe("AwaitStoryRevisionNotice", () => {
  it("links the revision run answering this run's conflict, and the Story", async () => {
    mockGet.mockResolvedValue([
      conflict({ id: "other", taskRunId: "run-2", revisionRunId: "unrelated-run" }),
      conflict(),
    ]);

    renderWithProviders(<AwaitStoryRevisionNotice runId="run-1" storyId="story-1" epicId="epic-1" />);

    await waitFor(() =>
      expect(hrefs()).toEqual(["/runs/revision-run", "/roadmap/epics/epic-1/stories/story-1"])
    );
    expect(mockGet).toHaveBeenCalledWith("/stories/story-1/conflicts");
  });

  it("says the revision has not started yet when no run answers the conflict", async () => {
    mockGet.mockResolvedValue([conflict({ revisionRunId: null })]);

    renderWithProviders(<AwaitStoryRevisionNotice runId="run-1" storyId="story-1" epicId="epic-1" />);

    await waitFor(() => expect(mockGet).toHaveBeenCalled());
    expect(screen.getByTestId("await-story-revision-notice")).toHaveTextContent(/starts automatically/i);
    expect(hrefs()).toEqual(["/roadmap/epics/epic-1/stories/story-1"]);
  });

  it("links the revision run once its Story's roadmap event arrives, with no roadmap page mounted", async () => {
    mockGet.mockResolvedValueOnce([conflict({ revisionRunId: null })]);
    renderWithProviders(<AwaitStoryRevisionNotice runId="run-1" storyId="story-1" epicId="epic-1" />);
    await waitFor(() => expect(screen.getByTestId("await-story-revision-notice")).toHaveTextContent(/starts automatically/i));

    mockGet.mockResolvedValueOnce([conflict({ revisionRunId: "revision-run" })]);
    storyEvent("story-1");

    await waitFor(() => expect(hrefs()).toContain("/runs/revision-run"));
  });

  it("ignores another Story's roadmap event", async () => {
    mockGet.mockResolvedValue([conflict({ revisionRunId: null })]);
    renderWithProviders(<AwaitStoryRevisionNotice runId="run-1" storyId="story-1" epicId="epic-1" />);
    await waitFor(() => expect(mockGet).toHaveBeenCalledTimes(1));

    storyEvent("story-2");

    expect(mockGet).toHaveBeenCalledTimes(1);
  });

  it("still explains the wait for a run with no Story", () => {
    renderWithProviders(<AwaitStoryRevisionNotice runId="run-1" storyId={null} epicId={null} />);

    expect(mockGet).not.toHaveBeenCalled();
    expect(stompListeners.size).toBe(0);
    expect(screen.getByTestId("await-story-revision-notice")).toHaveTextContent(/resumes/i);
    expect(hrefs()).toEqual([]);
  });
});
