import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import EditProposalDialog from "@/components/roadmap/EditProposalDialog";
import type { FeatureProposalResponse } from "@/lib/types";

const mockMutate = vi.fn();
const mockReset = vi.fn();
vi.mock("@/hooks/useFeatureProposals", () => ({
  useUpdateFeatureProposal: () => ({
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

function makeProposal(
  overrides: Partial<FeatureProposalResponse> = {}
): FeatureProposalResponse {
  return {
    id: "prop-1",
    title: "Existing title",
    description: "Existing desc",
    motivation: null,
    status: "backlog",
    softwareProject: {
      id: "r1",
      type: "git_repo",
      name: "backend-api",
    },
    repos: [
      { id: "r1", url: "https://github.com/acme/backend-api.git", name: "backend-api" },
    ],
    workflowRunId: null,
    workflowRunStatus: null,
    createdAt: "2026-04-01T00:00:00Z",
    updatedAt: "2026-04-01T00:00:00Z",
    ...overrides,
  };
}

describe("EditProposalDialog", () => {
  it("pre-populates title, description, and software project from the proposal", () => {
    renderWithProviders(
      <EditProposalDialog
        proposal={makeProposal()}
        open={true}
        onOpenChange={() => {}}
      />
    );
    expect(screen.getByTestId("edit-proposal-title")).toHaveValue("Existing title");
    expect(screen.getByTestId("edit-proposal-description")).toHaveValue(
      "Existing desc"
    );
    // The currently selected SoftwareProject's name appears inside the trigger.
    expect(
      screen.getByTestId("edit-proposal-software-project-select")
    ).toHaveTextContent("backend-api");
  });

  it("Save is disabled when title or description is empty", async () => {
    renderWithProviders(
      <EditProposalDialog
        proposal={makeProposal({ description: "" })}
        open={true}
        onOpenChange={() => {}}
      />
    );
    expect(screen.getByTestId("edit-proposal-save")).toBeDisabled();
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    await user.type(screen.getByTestId("edit-proposal-description"), "Filled");
    expect(screen.getByTestId("edit-proposal-save")).toBeEnabled();
  });

  it("changing the software project and saving sends softwareProjectId in the body", async () => {
    const onOpenChange = vi.fn();
    renderWithProviders(
      <EditProposalDialog
        proposal={makeProposal()}
        open={true}
        onOpenChange={onOpenChange}
      />
    );
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    await user.click(screen.getByTestId("edit-proposal-software-project-select"));
    await user.click(screen.getByText("Backend Stack"));

    await user.click(screen.getByTestId("edit-proposal-save"));
    expect(mockMutate).toHaveBeenCalledTimes(1);
    const [payload] = mockMutate.mock.calls[0];
    expect(payload).toEqual({
      id: "prop-1",
      body: {
        title: "Existing title",
        description: "Existing desc",
        motivation: null,
        softwareProjectId: "g1",
      },
    });
  });
});
