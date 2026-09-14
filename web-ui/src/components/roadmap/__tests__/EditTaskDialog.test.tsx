import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import EditTaskDialog from "@/components/roadmap/EditTaskDialog";
import { ApiError } from "@/lib/api";
import type { TaskResponse } from "@/lib/types";

const mockMutate = vi.fn();
const mockReset = vi.fn();
let mockError: unknown = null;

vi.mock("@/hooks/useTasks", () => ({
  useUpdateTask: () => ({
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

function makeTask(overrides: Partial<TaskResponse> = {}): TaskResponse {
  return {
    id: "task-1",
    storyId: "story-1",
    title: "Existing title",
    description: "Existing desc",
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

describe("EditTaskDialog", () => {
  it("pre-populates title and description from the task", () => {
    renderWithProviders(
      <EditTaskDialog task={makeTask()} open={true} onOpenChange={() => {}} />
    );
    expect(screen.getByTestId("edit-task-title")).toHaveValue("Existing title");
    expect(screen.getByTestId("edit-task-description")).toHaveValue("Existing desc");
  });

  it("does not render a priority control — priority is fixed at creation", () => {
    renderWithProviders(
      <EditTaskDialog task={makeTask()} open={true} onOpenChange={() => {}} />
    );
    expect(screen.queryByText(/priority/i)).not.toBeInTheDocument();
  });

  it("Save is disabled when title is cleared, enabled once it's filled again", async () => {
    renderWithProviders(
      <EditTaskDialog task={makeTask()} open={true} onOpenChange={() => {}} />
    );
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    await user.clear(screen.getByTestId("edit-task-title"));
    expect(screen.getByTestId("edit-task-save")).toBeDisabled();

    await user.type(screen.getByTestId("edit-task-title"), "New title");
    expect(screen.getByTestId("edit-task-save")).toBeEnabled();
  });

  it("Save sends the id and { title, description } body, with no priority key", async () => {
    renderWithProviders(
      <EditTaskDialog task={makeTask()} open={true} onOpenChange={() => {}} />
    );
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    await user.click(screen.getByTestId("edit-task-save"));

    expect(mockMutate).toHaveBeenCalledTimes(1);
    const [payload] = mockMutate.mock.calls[0];
    expect(payload).toEqual({
      id: "task-1",
      body: { title: "Existing title", description: "Existing desc" },
    });
    expect(payload.body).not.toHaveProperty("priority");
  });

  it("closes the dialog on a successful save", async () => {
    const onOpenChange = vi.fn();
    renderWithProviders(
      <EditTaskDialog task={makeTask()} open={true} onOpenChange={onOpenChange} />
    );
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    await user.click(screen.getByTestId("edit-task-save"));

    const [, options] = mockMutate.mock.calls[0];
    options.onSuccess();

    expect(onOpenChange).toHaveBeenCalledWith(false);
  });

  it("renders the backend's actual 409 guard text, not a generic message", () => {
    mockError = new ApiError(409, "Can only update tasks in backlog status");
    renderWithProviders(
      <EditTaskDialog task={makeTask()} open={true} onOpenChange={() => {}} />
    );
    expect(screen.getByTestId("edit-task-error")).toHaveTextContent(
      "Can only update tasks in backlog status"
    );
  });

  it("falls back to a generic message for a non-409 / non-string-body error", () => {
    mockError = new ApiError(500, { code: "INTERNAL" });
    renderWithProviders(
      <EditTaskDialog task={makeTask()} open={true} onOpenChange={() => {}} />
    );
    expect(screen.getByTestId("edit-task-error")).toHaveTextContent("Failed to update Task.");
  });
});
