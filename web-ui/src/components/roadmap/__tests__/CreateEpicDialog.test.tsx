import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import CreateEpicDialog from "@/components/roadmap/CreateEpicDialog";

const mockMutate = vi.fn();
const mockReset = vi.fn();
vi.mock("@/hooks/useEpics", () => ({
  useCreateEpic: () => ({
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

describe("CreateEpicDialog", () => {
  it("renders the SoftwareProject selector trigger when open", () => {
    renderWithProviders(<CreateEpicDialog open={true} onOpenChange={() => {}} />);
    expect(
      screen.getByTestId("create-epic-software-project-select")
    ).toBeInTheDocument();
  });

  it("Create button is disabled until title, description, and a software project are set", async () => {
    renderWithProviders(<CreateEpicDialog open={true} onOpenChange={() => {}} />);
    const submit = screen.getByTestId("create-epic-submit");
    expect(submit).toBeDisabled();

    const user = userEvent.setup({ pointerEventsCheck: 0 });
    await user.type(screen.getByTestId("create-epic-title"), "Feature");
    await user.type(screen.getByTestId("create-epic-description"), "Desc");
    // Still no software project selected — should remain disabled.
    expect(submit).toBeDisabled();

    // Open the dropdown and select a repo group.
    await user.click(
      screen.getByTestId("create-epic-software-project-select")
    );
    await user.click(screen.getByText("Backend Stack"));
    expect(submit).toBeEnabled();
  });

  it("posts softwareProjectId on submit", async () => {
    renderWithProviders(<CreateEpicDialog open={true} onOpenChange={() => {}} />);
    // delay: null makes typing instantaneous to prevent 5000ms timeout in slow CI environments
    const user = userEvent.setup({ pointerEventsCheck: 0, delay: null });
    await user.type(screen.getByTestId("create-epic-title"), "Feature X");
    await user.type(
      screen.getByTestId("create-epic-description"),
      "Desc Y"
    );
    await user.click(
      screen.getByTestId("create-epic-software-project-select")
    );
    await user.click(screen.getByText("backend-api"));

    await user.click(screen.getByTestId("create-epic-submit"));

    expect(mockMutate).toHaveBeenCalledTimes(1);
    const [payload] = mockMutate.mock.calls[0];
    expect(payload).toEqual({
      title: "Feature X",
      description: "Desc Y",
      motivation: null,
      softwareProjectId: "r1",
      // Defaults to "medium" when the priority picker is left untouched.
      priority: "medium",
    });
  });

  it("includes the chosen priority in the post body", async () => {
    renderWithProviders(<CreateEpicDialog open={true} onOpenChange={() => {}} />);
    const user = userEvent.setup({ pointerEventsCheck: 0, delay: null });
    await user.type(screen.getByTestId("create-epic-title"), "Feature X");
    await user.type(screen.getByTestId("create-epic-description"), "Desc Y");
    await user.click(screen.getByTestId("create-epic-software-project-select"));
    await user.click(screen.getByText("backend-api"));

    // Change priority away from the "medium" default to "High".
    await user.click(screen.getByTestId("create-epic-priority-select"));
    await user.click(screen.getByTestId("priority-option-high"));

    await user.click(screen.getByTestId("create-epic-submit"));

    expect(mockMutate).toHaveBeenCalledTimes(1);
    const [payload] = mockMutate.mock.calls[0];
    expect(payload.priority).toBe("high");
  });

  it("preserves a non-empty motivation in the post body", async () => {
    renderWithProviders(<CreateEpicDialog open={true} onOpenChange={() => {}} />);
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    await user.type(screen.getByTestId("create-epic-title"), "F");
    await user.type(screen.getByTestId("create-epic-description"), "D");
    await user.type(
      screen.getByTestId("create-epic-motivation"),
      "Why this matters"
    );
    await user.click(
      screen.getByTestId("create-epic-software-project-select")
    );
    await user.click(screen.getByText("web-ui"));
    await user.click(screen.getByTestId("create-epic-submit"));

    expect(mockMutate).toHaveBeenCalledTimes(1);
    const [payload] = mockMutate.mock.calls[0];
    expect(payload.motivation).toBe("Why this matters");
    expect(payload.softwareProjectId).toBe("r2");
  });
});
