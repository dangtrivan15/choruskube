import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, within } from "@testing-library/react";
import { renderWithProviders } from "@/__tests__/test-utils";
import AwaitStoryRevisionNotice from "../AwaitStoryRevisionNotice";
import type { StoryConflictResponse } from "@/lib/types";

const mockUseStoryConflicts = vi.fn();

vi.mock("@/hooks/useStorySpec", () => ({
  useStoryConflicts: (id: string | undefined) => mockUseStoryConflicts(id),
}));

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

beforeEach(() => {
  mockUseStoryConflicts.mockReset();
  mockUseStoryConflicts.mockReturnValue({ data: [] });
});

describe("AwaitStoryRevisionNotice", () => {
  it("links the revision run answering this run's conflict, and the Story", () => {
    mockUseStoryConflicts.mockReturnValue({
      data: [conflict({ id: "other", taskRunId: "run-2", revisionRunId: "unrelated-run" }), conflict()],
    });

    renderWithProviders(<AwaitStoryRevisionNotice runId="run-1" storyId="story-1" epicId="epic-1" />);

    expect(mockUseStoryConflicts).toHaveBeenCalledWith("story-1");
    expect(hrefs()).toEqual(["/runs/revision-run", "/roadmap/epics/epic-1/stories/story-1"]);
  });

  it("says the revision has not started yet when no run answers the conflict", () => {
    mockUseStoryConflicts.mockReturnValue({ data: [conflict({ revisionRunId: null })] });

    renderWithProviders(<AwaitStoryRevisionNotice runId="run-1" storyId="story-1" epicId="epic-1" />);

    const notice = screen.getByTestId("await-story-revision-notice");
    expect(notice).toHaveTextContent(/starts automatically/i);
    expect(hrefs()).toEqual(["/roadmap/epics/epic-1/stories/story-1"]);
  });

  it("still explains the wait for a run with no Story", () => {
    renderWithProviders(<AwaitStoryRevisionNotice runId="run-1" storyId={null} epicId={null} />);

    expect(mockUseStoryConflicts).toHaveBeenCalledWith(undefined);
    expect(screen.getByTestId("await-story-revision-notice")).toHaveTextContent(/resumes/i);
    expect(hrefs()).toEqual([]);
  });
});
