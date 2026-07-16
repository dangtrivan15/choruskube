import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import CreateTaskDialog from "@/components/roadmap/CreateTaskDialog";

const mockMutate = vi.fn();
const mockReset = vi.fn();
const mockUseCreateTask = vi.fn((_storyId: string) => ({
  mutate: mockMutate,
  isPending: false,
  isError: false,
  reset: mockReset,
}));

vi.mock("@/hooks/useTasks", () => ({
  useCreateTask: (storyId: string) => mockUseCreateTask(storyId),
}));

beforeEach(() => {
  mockMutate.mockReset();
  mockReset.mockReset();
  mockUseCreateTask.mockClear();
});

describe("CreateTaskDialog", () => {
  it("passes the storyId through to useCreateTask", () => {
    renderWithProviders(
      <CreateTaskDialog storyId="story-1" open={true} onOpenChange={() => {}} />
    );
    expect(mockUseCreateTask).toHaveBeenCalledWith("story-1");
  });

  it("Create button is disabled until title and description are set", async () => {
    renderWithProviders(
      <CreateTaskDialog storyId="story-1" open={true} onOpenChange={() => {}} />
    );
    const submit = screen.getByTestId("create-task-submit");
    expect(submit).toBeDisabled();

    const user = userEvent.setup({ pointerEventsCheck: 0 });
    await user.type(screen.getByTestId("create-task-title"), "Task title");
    expect(submit).toBeDisabled();
    await user.type(screen.getByTestId("create-task-description"), "Task desc");
    expect(submit).toBeEnabled();
  });

  it("posts title and description on submit", async () => {
    renderWithProviders(
      <CreateTaskDialog storyId="story-1" open={true} onOpenChange={() => {}} />
    );
    const user = userEvent.setup({ pointerEventsCheck: 0, delay: null });
    await user.type(screen.getByTestId("create-task-title"), "Task title");
    await user.type(screen.getByTestId("create-task-description"), "Task desc");
    await user.click(screen.getByTestId("create-task-submit"));

    expect(mockMutate).toHaveBeenCalledTimes(1);
    const [payload] = mockMutate.mock.calls[0];
    expect(payload).toEqual({ title: "Task title", description: "Task desc" });
  });
});
