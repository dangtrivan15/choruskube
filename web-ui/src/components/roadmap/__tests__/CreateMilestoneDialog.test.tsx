import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import CreateMilestoneDialog from "@/components/roadmap/CreateMilestoneDialog";

const mockMutate = vi.fn();
const mockReset = vi.fn();
vi.mock("@/hooks/useMilestones", () => ({
  useCreateMilestone: () => ({
    mutate: mockMutate,
    isPending: false,
    isError: false,
    reset: mockReset,
  }),
}));

vi.mock("@/hooks/useSoftwareProjects", () => ({
  useSoftwareProjects: () => ({
    data: [
      {
        id: "g1",
        name: "Backend Stack",
        type: "repo_group",
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
});

describe("CreateMilestoneDialog", () => {
  it("Create button is disabled until name and a software project are set", async () => {
    renderWithProviders(<CreateMilestoneDialog open={true} onOpenChange={() => {}} />);
    const submit = screen.getByTestId("create-milestone-submit");
    expect(submit).toBeDisabled();

    const user = userEvent.setup({ pointerEventsCheck: 0 });
    await user.type(screen.getByTestId("create-milestone-name"), "Q3 Launch");
    expect(submit).toBeDisabled();

    await user.click(screen.getByTestId("create-milestone-software-project-select"));
    await user.click(screen.getByText("backend-api"));
    expect(submit).toBeEnabled();
  });

  it("posts name, description, and softwareProjectId on submit", async () => {
    renderWithProviders(<CreateMilestoneDialog open={true} onOpenChange={() => {}} />);
    const user = userEvent.setup({ pointerEventsCheck: 0, delay: null });
    await user.type(screen.getByTestId("create-milestone-name"), "Q3 Launch");
    await user.type(screen.getByTestId("create-milestone-description"), "The Q3 release");
    await user.click(screen.getByTestId("create-milestone-software-project-select"));
    await user.click(screen.getByText("backend-api"));

    await user.click(screen.getByTestId("create-milestone-submit"));

    expect(mockMutate).toHaveBeenCalledTimes(1);
    const [payload] = mockMutate.mock.calls[0];
    expect(payload).toEqual({
      name: "Q3 Launch",
      description: "The Q3 release",
      softwareProjectId: "r1",
      targetDate: null,
    });
  });

  it("omits an empty description as null", async () => {
    renderWithProviders(<CreateMilestoneDialog open={true} onOpenChange={() => {}} />);
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    await user.type(screen.getByTestId("create-milestone-name"), "Q4 Launch");
    await user.click(screen.getByTestId("create-milestone-software-project-select"));
    await user.click(screen.getByText("Backend Stack"));
    await user.click(screen.getByTestId("create-milestone-submit"));

    const [payload] = mockMutate.mock.calls[0];
    expect(payload.description).toBeNull();
  });

  it("resets the form and mutation state when the dialog is dismissed via Escape", async () => {
    renderWithProviders(<CreateMilestoneDialog open={true} onOpenChange={() => {}} />);
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    await user.type(screen.getByTestId("create-milestone-name"), "Draft name");

    await user.keyboard("{Escape}");

    expect(mockReset).toHaveBeenCalled();
  });
});
