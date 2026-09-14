import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import EditStoryDialog from "@/components/roadmap/EditStoryDialog";
import { ApiError } from "@/lib/api";
import type { StoryResponse } from "@/lib/types";

const mockMutate = vi.fn();
const mockReset = vi.fn();
let mockError: unknown = null;

vi.mock("@/hooks/useStories", () => ({
  useUpdateStory: () => ({
    mutate: mockMutate,
    isPending: false,
    isError: mockError !== null,
    error: mockError,
    reset: mockReset,
  }),
}));

beforeEach(() => {
  mockMutate.mockReset();
  mockReset.mockReset();
  mockError = null;
});

function makeStory(overrides: Partial<StoryResponse> = {}): StoryResponse {
  return {
    id: "story-1",
    epicId: "epic-1",
    title: "Existing title",
    description: "Existing desc",
    stage: "backlog",
    priority: "medium",
    targetDate: null,
    readiness: null,
    readyTaskCount: null,
    progress: { totalTasks: 0, doneTasks: 0, startedTasks: 0 },
    createdAt: "2026-04-01T00:00:00Z",
    updatedAt: "2026-04-01T00:00:00Z",
    ...overrides,
  };
}

describe("EditStoryDialog", () => {
  it("pre-populates title and description from the story", () => {
    renderWithProviders(
      <EditStoryDialog story={makeStory()} open={true} onOpenChange={() => {}} />
    );
    expect(screen.getByTestId("edit-story-title")).toHaveValue("Existing title");
    expect(screen.getByTestId("edit-story-description")).toHaveValue("Existing desc");
  });

  it("Save is disabled when title is cleared, enabled once it's filled again", async () => {
    renderWithProviders(
      <EditStoryDialog story={makeStory()} open={true} onOpenChange={() => {}} />
    );
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    await user.clear(screen.getByTestId("edit-story-title"));
    expect(screen.getByTestId("edit-story-save")).toBeDisabled();

    await user.type(screen.getByTestId("edit-story-title"), "New title");
    expect(screen.getByTestId("edit-story-save")).toBeEnabled();
  });

  it("Save sends the id and { title, description } body", async () => {
    renderWithProviders(
      <EditStoryDialog story={makeStory()} open={true} onOpenChange={() => {}} />
    );
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    await user.click(screen.getByTestId("edit-story-save"));

    expect(mockMutate).toHaveBeenCalledTimes(1);
    const [payload] = mockMutate.mock.calls[0];
    expect(payload).toEqual({
      id: "story-1",
      body: { title: "Existing title", description: "Existing desc" },
    });
  });

  it("closes the dialog on a successful save", async () => {
    const onOpenChange = vi.fn();
    renderWithProviders(
      <EditStoryDialog story={makeStory()} open={true} onOpenChange={onOpenChange} />
    );
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    await user.click(screen.getByTestId("edit-story-save"));

    const [, options] = mockMutate.mock.calls[0];
    options.onSuccess();

    expect(onOpenChange).toHaveBeenCalledWith(false);
  });

  it("renders the backend's actual 409 guard text, not a generic message", () => {
    mockError = new ApiError(
      409,
      "Can only update a Story while all of its Tasks are still in backlog"
    );
    renderWithProviders(
      <EditStoryDialog story={makeStory()} open={true} onOpenChange={() => {}} />
    );
    expect(screen.getByTestId("edit-story-error")).toHaveTextContent(
      "Can only update a Story while all of its Tasks are still in backlog"
    );
  });

  it("falls back to a generic message for a non-409 / non-string-body error", () => {
    mockError = new ApiError(500, { code: "INTERNAL" });
    renderWithProviders(
      <EditStoryDialog story={makeStory()} open={true} onOpenChange={() => {}} />
    );
    expect(screen.getByTestId("edit-story-error")).toHaveTextContent("Failed to update Story.");
  });
});
