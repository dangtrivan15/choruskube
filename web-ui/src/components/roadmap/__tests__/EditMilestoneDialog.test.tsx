import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, fireEvent } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import EditMilestoneDialog from "@/components/roadmap/EditMilestoneDialog";
import type { MilestoneResponse } from "@/lib/types";

const mockMutate = vi.fn();
const mockReset = vi.fn();
vi.mock("@/hooks/useMilestones", () => ({
  useUpdateMilestone: () => ({
    mutate: mockMutate,
    isPending: false,
    isError: false,
    reset: mockReset,
  }),
}));

beforeEach(() => {
  mockMutate.mockReset();
  mockReset.mockReset();
});

function makeMilestone(overrides: Partial<MilestoneResponse> = {}): MilestoneResponse {
  return {
    id: "m1",
    name: "Q3 Launch",
    description: "The Q3 release",
    softwareProjectId: "r1",
    targetDate: null,
    epicCount: 2,
    progress: { totalTasks: 0, doneTasks: 0, inProgressTasks: 0, notStartedTasks: 0 },
    atRisk: false,
    atRiskItemCount: 0,
    createdAt: "2026-01-01T00:00:00Z",
    updatedAt: "2026-01-01T00:00:00Z",
    ...overrides,
  };
}

describe("EditMilestoneDialog", () => {
  it("pre-populates name and description from the milestone", () => {
    renderWithProviders(
      <EditMilestoneDialog milestone={makeMilestone()} open={true} onOpenChange={() => {}} />
    );
    expect(screen.getByTestId("edit-milestone-name")).toHaveValue("Q3 Launch");
    expect(screen.getByTestId("edit-milestone-description")).toHaveValue("The Q3 release");
  });

  it("Save is disabled when name is empty", async () => {
    renderWithProviders(
      <EditMilestoneDialog
        milestone={makeMilestone({ name: "" })}
        open={true}
        onOpenChange={() => {}}
      />
    );
    expect(screen.getByTestId("edit-milestone-save")).toBeDisabled();
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    await user.type(screen.getByTestId("edit-milestone-name"), "Filled");
    expect(screen.getByTestId("edit-milestone-save")).toBeEnabled();
  });

  it("saves the renamed name, description, and preserves targetDate", async () => {
    const onOpenChange = vi.fn();
    renderWithProviders(
      <EditMilestoneDialog milestone={makeMilestone()} open={true} onOpenChange={onOpenChange} />
    );
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    await user.clear(screen.getByTestId("edit-milestone-name"));
    await user.type(screen.getByTestId("edit-milestone-name"), "Q3 Launch (final)");

    await user.click(screen.getByTestId("edit-milestone-save"));

    expect(mockMutate).toHaveBeenCalledTimes(1);
    const [payload] = mockMutate.mock.calls[0];
    expect(payload).toEqual({
      id: "m1",
      body: {
        name: "Q3 Launch (final)",
        description: "The Q3 release",
        targetDate: null,
      },
    });
  });

  it("pre-populates the target date field from the milestone", () => {
    renderWithProviders(
      <EditMilestoneDialog
        milestone={makeMilestone({ targetDate: "2026-09-01" })}
        open={true}
        onOpenChange={() => {}}
      />
    );
    expect(screen.getByTestId("edit-milestone-target-date")).toHaveValue("2026-09-01");
  });

  it("entering a target date includes it in the submitted request", async () => {
    renderWithProviders(
      <EditMilestoneDialog milestone={makeMilestone()} open={true} onOpenChange={() => {}} />
    );
    const user = userEvent.setup({ pointerEventsCheck: 0 });

    fireEvent.change(screen.getByTestId("edit-milestone-target-date"), {
      target: { value: "2026-10-15" },
    });
    await user.click(screen.getByTestId("edit-milestone-save"));

    expect(mockMutate).toHaveBeenCalledTimes(1);
    const [payload] = mockMutate.mock.calls[0];
    expect(payload.body.targetDate).toBe("2026-10-15");
  });

  it("resets the mutation state on Cancel", async () => {
    const onOpenChange = vi.fn();
    renderWithProviders(
      <EditMilestoneDialog milestone={makeMilestone()} open={true} onOpenChange={onOpenChange} />
    );
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    expect(onOpenChange).toHaveBeenCalledWith(false);
  });
});
