import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import EditEpicDialog from "@/components/roadmap/EditEpicDialog";
import type { EpicResponse } from "@/lib/types";

const mockMutate = vi.fn();
const mockReset = vi.fn();
vi.mock("@/hooks/useEpics", () => ({
  useUpdateEpic: () => ({
    mutate: mockMutate,
    isPending: false,
    isError: false,
    reset: mockReset,
  }),
}));

const mockAssignMutate = vi.fn();
const mockUseMilestones = vi.fn();
vi.mock("@/hooks/useMilestones", () => ({
  useMilestones: (...args: unknown[]) => mockUseMilestones(...args),
  useAssignEpicMilestone: () => ({
    mutate: mockAssignMutate,
    isPending: false,
    isError: false,
    reset: vi.fn(),
  }),
}));

vi.mock("@/hooks/useSoftwareProjects", () => ({
  useSoftwareProjects: () => ({
    data: [
      {
        id: "g1",
        name: "Backend Stack",
        type: "repo_group",
        organizationId: "o1",
        agentImage: null,
        description: null,
        runtimeRequirements: { agentImage: null, enableDocker: false },
        createdAt: "2026-01-01",
        updatedAt: "2026-01-01",
      },
      {
        id: "r1",
        name: "backend-api",
        type: "git_repo",
        organizationId: "o1",
        agentImage: null,
        description: null,
        runtimeRequirements: { agentImage: null, enableDocker: false },
        createdAt: "2026-01-01",
        updatedAt: "2026-01-01",
      },
      {
        id: "r2",
        name: "web-ui",
        type: "git_repo",
        organizationId: "o1",
        agentImage: null,
        description: null,
        runtimeRequirements: { agentImage: null, enableDocker: false },
        createdAt: "2026-01-01",
        updatedAt: "2026-01-01",
      },
    ],
  }),
}));

beforeEach(() => {
  mockMutate.mockReset();
  mockReset.mockReset();
  mockAssignMutate.mockReset();
  mockUseMilestones.mockReset();
  mockUseMilestones.mockReturnValue({ data: { content: [] } });
});

function makeEpic(overrides: Partial<EpicResponse> = {}): EpicResponse {
  return {
    id: "epic-1",
    title: "Existing title",
    description: "Existing desc",
    motivation: null,
    status: "backlog",
    stage: "backlog",
    priority: "medium",
    targetDate: null,
    progress: { totalTasks: 0, doneTasks: 0 },
    softwareProject: {
      id: "r1",
      type: "git_repo",
      name: "backend-api",
    },
    repos: [
      { id: "r1", url: "https://github.com/acme/backend-api.git", name: "backend-api" },
    ],
    createdAt: "2026-04-01T00:00:00Z",
    updatedAt: "2026-04-01T00:00:00Z",
    readyItemCount: 0,
    milestone: null,
    ...overrides,
  };
}

describe("EditEpicDialog", () => {
  it("pre-populates title, description, and software project from the epic", () => {
    renderWithProviders(
      <EditEpicDialog
        epic={makeEpic()}
        open={true}
        onOpenChange={() => {}}
      />
    );
    expect(screen.getByTestId("edit-epic-title")).toHaveValue("Existing title");
    expect(screen.getByTestId("edit-epic-description")).toHaveValue(
      "Existing desc"
    );
    // The currently selected SoftwareProject's name appears inside the trigger.
    expect(
      screen.getByTestId("edit-epic-software-project-select")
    ).toHaveTextContent("backend-api");
  });

  it("Save is disabled when title or description is empty", async () => {
    renderWithProviders(
      <EditEpicDialog
        epic={makeEpic({ description: "" })}
        open={true}
        onOpenChange={() => {}}
      />
    );
    expect(screen.getByTestId("edit-epic-save")).toBeDisabled();
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    await user.type(screen.getByTestId("edit-epic-description"), "Filled");
    expect(screen.getByTestId("edit-epic-save")).toBeEnabled();
  });

  it("changing the software project and saving sends softwareProjectId in the body", async () => {
    const onOpenChange = vi.fn();
    renderWithProviders(
      <EditEpicDialog
        epic={makeEpic()}
        open={true}
        onOpenChange={onOpenChange}
      />
    );
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    await user.click(screen.getByTestId("edit-epic-software-project-select"));
    await user.click(screen.getByText("Backend Stack"));

    await user.click(screen.getByTestId("edit-epic-save"));
    expect(mockMutate).toHaveBeenCalledTimes(1);
    const [payload] = mockMutate.mock.calls[0];
    expect(payload).toEqual({
      id: "epic-1",
      body: {
        title: "Existing title",
        description: "Existing desc",
        motivation: null,
        softwareProjectId: "g1",
      },
    });
  });

  // --- Milestone assignment (post-PUT PATCH chain) ---

  it("pre-populates the milestone selector from the epic's current assignment", () => {
    mockUseMilestones.mockReturnValue({ data: { content: [{ id: "m1", name: "Q3 Launch" }] } });
    renderWithProviders(
      <EditEpicDialog
        epic={makeEpic({ milestone: { id: "m1", name: "Q3 Launch" } })}
        open={true}
        onOpenChange={() => {}}
      />
    );
    expect(screen.getByTestId("edit-epic-milestone-select")).toHaveTextContent("Q3 Launch");
  });

  it("issues an assign PATCH after save when the milestone selection changed", async () => {
    mockUseMilestones.mockReturnValue({ data: { content: [{ id: "m1", name: "Q3 Launch" }] } });
    renderWithProviders(
      <EditEpicDialog epic={makeEpic({ milestone: null })} open={true} onOpenChange={() => {}} />
    );
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    await user.click(screen.getByTestId("edit-epic-milestone-select"));
    await user.click(screen.getByText("Q3 Launch"));

    await user.click(screen.getByTestId("edit-epic-save"));

    expect(mockMutate).toHaveBeenCalledTimes(1);
    const [, options] = mockMutate.mock.calls[0];
    options.onSuccess();

    expect(mockAssignMutate).toHaveBeenCalledWith(
      { id: "epic-1", milestoneId: "m1" },
      expect.anything()
    );
  });

  it("clears the milestone selection when the software project changes and un-tags on save", async () => {
    // Regression: re-pointing the Epic to a different project must not silently retain the
    // previously-chosen Milestone (scoped to the old project). The picker resets to "None" and
    // the post-PUT PATCH clears the assignment, keeping the UI and the saved state in agreement.
    mockUseMilestones.mockReturnValue({ data: { content: [{ id: "m1", name: "Q3 Launch" }] } });
    renderWithProviders(
      <EditEpicDialog
        epic={makeEpic({ milestone: { id: "m1", name: "Q3 Launch" } })}
        open={true}
        onOpenChange={() => {}}
      />
    );
    const user = userEvent.setup({ pointerEventsCheck: 0 });

    await user.click(screen.getByTestId("edit-epic-software-project-select"));
    await user.click(screen.getByText("Backend Stack"));
    expect(screen.getByTestId("edit-epic-milestone-select")).toHaveTextContent("None");

    await user.click(screen.getByTestId("edit-epic-save"));
    const [, options] = mockMutate.mock.calls[0];
    options.onSuccess();

    expect(mockAssignMutate).toHaveBeenCalledWith(
      { id: "epic-1", milestoneId: null },
      expect.anything()
    );
  });

  it("does not issue an assign PATCH after save when the milestone selection is unchanged", async () => {
    mockUseMilestones.mockReturnValue({ data: { content: [{ id: "m1", name: "Q3 Launch" }] } });
    renderWithProviders(
      <EditEpicDialog
        epic={makeEpic({ milestone: { id: "m1", name: "Q3 Launch" } })}
        open={true}
        onOpenChange={() => {}}
      />
    );
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    await user.click(screen.getByTestId("edit-epic-save"));

    const [, options] = mockMutate.mock.calls[0];
    options.onSuccess();

    expect(mockAssignMutate).not.toHaveBeenCalled();
  });
});
