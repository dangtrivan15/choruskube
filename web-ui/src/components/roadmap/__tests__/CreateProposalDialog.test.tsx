import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import CreateProposalDialog from "@/components/roadmap/CreateProposalDialog";

const mockMutate = vi.fn();
const mockReset = vi.fn();
vi.mock("@/hooks/useFeatureProposals", () => ({
  useCreateFeatureProposal: () => ({
    mutate: mockMutate,
    isPending: false,
    isError: false,
    reset: mockReset,
  }),
}));

// SoftwareProjectSelect is sourced from useSoftwareProjects(); fixture covers
// one repo group and two repos.
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
});

describe("CreateProposalDialog", () => {
  it("renders the SoftwareProject selector trigger when open", () => {
    renderWithProviders(<CreateProposalDialog open={true} onOpenChange={() => {}} />);
    expect(
      screen.getByTestId("create-proposal-software-project-select")
    ).toBeInTheDocument();
  });

  it("Create button is disabled until title, description, and a software project are set", async () => {
    renderWithProviders(<CreateProposalDialog open={true} onOpenChange={() => {}} />);
    const submit = screen.getByTestId("create-proposal-submit");
    expect(submit).toBeDisabled();

    const user = userEvent.setup({ pointerEventsCheck: 0 });
    await user.type(screen.getByTestId("create-proposal-title"), "Feature");
    await user.type(screen.getByTestId("create-proposal-description"), "Desc");
    // Still no software project selected — should remain disabled.
    expect(submit).toBeDisabled();

    // Open the dropdown and select a repo group.
    await user.click(
      screen.getByTestId("create-proposal-software-project-select")
    );
    await user.click(screen.getByText("Backend Stack"));
    expect(submit).toBeEnabled();
  });

  it("posts softwareProjectId on submit", async () => {
    renderWithProviders(<CreateProposalDialog open={true} onOpenChange={() => {}} />);
    // delay: null makes typing instantaneous to prevent 5000ms timeout in slow CI environments
    const user = userEvent.setup({ pointerEventsCheck: 0, delay: null });
    await user.type(screen.getByTestId("create-proposal-title"), "Feature X");
    await user.type(
      screen.getByTestId("create-proposal-description"),
      "Desc Y"
    );
    await user.click(
      screen.getByTestId("create-proposal-software-project-select")
    );
    await user.click(screen.getByText("backend-api"));

    await user.click(screen.getByTestId("create-proposal-submit"));

    expect(mockMutate).toHaveBeenCalledTimes(1);
    const [payload] = mockMutate.mock.calls[0];
    expect(payload).toEqual({
      title: "Feature X",
      description: "Desc Y",
      motivation: null,
      softwareProjectId: "r1",
    });
  });

  it("preserves a non-empty motivation in the post body", async () => {
    renderWithProviders(<CreateProposalDialog open={true} onOpenChange={() => {}} />);
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    await user.type(screen.getByTestId("create-proposal-title"), "F");
    await user.type(screen.getByTestId("create-proposal-description"), "D");
    await user.type(
      screen.getByTestId("create-proposal-motivation"),
      "Why this matters"
    );
    await user.click(
      screen.getByTestId("create-proposal-software-project-select")
    );
    await user.click(screen.getByText("web-ui"));
    await user.click(screen.getByTestId("create-proposal-submit"));

    expect(mockMutate).toHaveBeenCalledTimes(1);
    const [payload] = mockMutate.mock.calls[0];
    expect(payload.motivation).toBe("Why this matters");
    expect(payload.softwareProjectId).toBe("r2");
  });
});
