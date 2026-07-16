import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import CreateStoryDialog from "@/components/roadmap/CreateStoryDialog";

const mockMutate = vi.fn();
const mockReset = vi.fn();
const mockUseCreateStory = vi.fn((_epicId: string) => ({
  mutate: mockMutate,
  isPending: false,
  isError: false,
  reset: mockReset,
}));

vi.mock("@/hooks/useStories", () => ({
  useCreateStory: (epicId: string) => mockUseCreateStory(epicId),
}));

beforeEach(() => {
  mockMutate.mockReset();
  mockReset.mockReset();
  mockUseCreateStory.mockClear();
});

describe("CreateStoryDialog", () => {
  it("passes the epicId through to useCreateStory", () => {
    renderWithProviders(
      <CreateStoryDialog epicId="epic-1" open={true} onOpenChange={() => {}} />
    );
    expect(mockUseCreateStory).toHaveBeenCalledWith("epic-1");
  });

  it("Create button is disabled until title and description are set", async () => {
    renderWithProviders(
      <CreateStoryDialog epicId="epic-1" open={true} onOpenChange={() => {}} />
    );
    const submit = screen.getByTestId("create-story-submit");
    expect(submit).toBeDisabled();

    const user = userEvent.setup({ pointerEventsCheck: 0 });
    await user.type(screen.getByTestId("create-story-title"), "Story title");
    expect(submit).toBeDisabled();
    await user.type(screen.getByTestId("create-story-description"), "Story desc");
    expect(submit).toBeEnabled();
  });

  it("posts title and description on submit", async () => {
    renderWithProviders(
      <CreateStoryDialog epicId="epic-1" open={true} onOpenChange={() => {}} />
    );
    const user = userEvent.setup({ pointerEventsCheck: 0, delay: null });
    await user.type(screen.getByTestId("create-story-title"), "Story title");
    await user.type(screen.getByTestId("create-story-description"), "Story desc");
    await user.click(screen.getByTestId("create-story-submit"));

    expect(mockMutate).toHaveBeenCalledTimes(1);
    const [payload] = mockMutate.mock.calls[0];
    expect(payload).toEqual({ title: "Story title", description: "Story desc" });
  });
});
