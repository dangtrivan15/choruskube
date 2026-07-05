import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import StartRunDialog from "../StartRunDialog";

const mockMutate = vi.fn();

vi.mock("@/hooks/useTemplates", () => ({
  useTemplates: vi.fn(),
}));

vi.mock("@/hooks/useGitRepos", () => ({
  useGitRepos: vi.fn(() => ({
    data: { content: [], totalElements: 0, totalPages: 1, number: 0, size: 100, first: true, last: true, empty: true },
  })),
}));

vi.mock("@/hooks/useSoftwareProjects", () => ({
  useSoftwareProjects: vi.fn(() => ({ data: [], isLoading: false })),
}));

vi.mock("@/hooks/useRuns", () => ({
  useStartRun: vi.fn(() => ({
    mutate: mockMutate,
    isPending: false,
  })),
}));

import { useTemplates } from "@/hooks/useTemplates";
import { useSoftwareProjects } from "@/hooks/useSoftwareProjects";

const mockUseTemplates = useTemplates as ReturnType<typeof vi.fn>;
const mockUseSoftwareProjects = useSoftwareProjects as ReturnType<typeof vi.fn>;

describe("StartRunDialog", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    const templates = [
        {
          id: "tpl-1",
          name: "Template A",
          version: 1,
          inputSchema: [
            { name: "prompt", label: "Prompt", type: "text", required: true },
          ],
        },
        {
          id: "tpl-2",
          name: "Template B",
          version: 2,
          inputSchema: [],
        },
      ];
    mockUseTemplates.mockReturnValue({
      data: { content: templates, totalElements: 2, totalPages: 1, number: 0, size: 100, first: true, last: true, empty: false },
      isLoading: false,
    });
  });

  it("renders the Start Run trigger button", () => {
    renderWithProviders(<StartRunDialog />);
    expect(screen.getByText("Start Run")).toBeInTheDocument();
  });

  it("opens the dialog when trigger is clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(<StartRunDialog />);

    await user.click(screen.getByText("Start Run"));

    expect(screen.getByText("Start a New Run")).toBeInTheDocument();
    expect(
      screen.getByText("Select a graph template to start a new orchestration run.")
    ).toBeInTheDocument();
  });

  it("passes latestOnly=true to useTemplates", () => {
    renderWithProviders(<StartRunDialog />);
    expect(mockUseTemplates).toHaveBeenCalledWith(true, undefined, { size: 100 });
  });

  it("renders a textarea for fields with type 'textarea'", async () => {
    const templates = [
        {
          id: "tpl-textarea",
          name: "Textarea Template",
          version: 1,
          inputSchema: [
            { name: "feature_request", label: "Feature Request", type: "textarea", required: true },
          ],
        },
      ];
    mockUseTemplates.mockReturnValue({
      data: { content: templates, totalElements: 1, totalPages: 1, number: 0, size: 100, first: true, last: true, empty: false },
      isLoading: false,
    });

    const user = userEvent.setup({ pointerEventsCheck: 0 });
    renderWithProviders(<StartRunDialog />);
    await user.click(screen.getByText("Start Run"));

    // Select the template
    await user.click(screen.getByText("Select a template..."));
    await user.click(screen.getByText("Textarea Template"));

    const textarea = screen.getByLabelText(/Feature Request/);
    expect(textarea.tagName).toBe("TEXTAREA");
  });

  it("renders a git repo select for fields with type 'git_repo'", { timeout: 15000 }, async () => {
    const templates = [
        {
          id: "tpl-repo",
          name: "Repo Template",
          version: 1,
          inputSchema: [
            { name: "git_repo_id", label: "Git Repository", type: "git_repo", required: true },
          ],
        },
      ];
    mockUseTemplates.mockReturnValue({
      data: { content: templates, totalElements: 1, totalPages: 1, number: 0, size: 100, first: true, last: true, empty: false },
      isLoading: false,
    });

    const user = userEvent.setup({ pointerEventsCheck: 0 });
    renderWithProviders(<StartRunDialog />);
    await user.click(screen.getByText("Start Run"));

    // Select the template
    await user.click(screen.getByText("Select a template..."));
    await user.click(screen.getByText("Repo Template"));

    // The git_repo field should render a GitRepoSelect with the repo placeholder
    expect(screen.getByText("Select a repository...")).toBeInTheDocument();
  });

  it("renders an input for fields with type 'text'", async () => {
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    renderWithProviders(<StartRunDialog />);
    await user.click(screen.getByText("Start Run"));

    // Select Template A which has a text field
    await user.click(screen.getByText("Select a template..."));
    await user.click(screen.getByText("Template A"));

    const input = screen.getByLabelText(/Prompt/);
    expect(input.tagName).toBe("INPUT");
  });

  it("renders grouped Software Project dropdown when template input is software_project_id", { timeout: 15000 }, async () => {
    const templates = [
      {
        id: "tpl-sp",
        name: "SP Template",
        version: 1,
        inputSchema: [
          { name: "project_id", label: "Software Project", type: "software_project_id", required: true },
        ],
      },
    ];
    mockUseTemplates.mockReturnValue({
      data: { content: templates, totalElements: 1, totalPages: 1, number: 0, size: 100, first: true, last: true, empty: false },
      isLoading: false,
    });
    mockUseSoftwareProjects.mockReturnValue({
      data: [
        {
          id: "g1",
          name: "Group A",
          type: "repo_group",
          organizationId: "o",
          agentImage: null,
          description: null,
          runtimeRequirements: { agentImage: null, enableDocker: false },
          createdAt: "2026-01-01",
          updatedAt: "2026-01-01",
        },
        {
          id: "r1",
          name: "repo-a",
          type: "git_repo",
          organizationId: "o",
          agentImage: null,
          description: null,
          runtimeRequirements: { agentImage: null, enableDocker: false },
          createdAt: "2026-01-01",
          updatedAt: "2026-01-01",
        },
      ],
      isLoading: false,
    });

    const user = userEvent.setup({ pointerEventsCheck: 0 });
    renderWithProviders(<StartRunDialog />);
    await user.click(screen.getByText("Start Run"));

    // Select the SP template
    await user.click(screen.getByText("Select a template..."));
    await user.click(screen.getByText("SP Template"));

    // Open the software-project dropdown
    await user.click(screen.getByText("Select a software project..."));

    expect(screen.getByText("Repo Groups")).toBeInTheDocument();
    expect(screen.getByText("Repositories")).toBeInTheDocument();

    const groupItem = screen.getByText("Group A");
    const repoItem = screen.getByText("repo-a");
    expect(groupItem).toBeInTheDocument();
    expect(repoItem).toBeInTheDocument();

    // Group A must appear before repo-a in DOM order
    const position = groupItem.compareDocumentPosition(repoItem);
    expect(position & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it("caps the run-name input at 30 characters", async () => {
    const user = userEvent.setup();
    renderWithProviders(<StartRunDialog />);

    await user.click(screen.getByText("Start Run"));
    await user.click(screen.getByText("Select a template..."));
    await user.click(screen.getByText("Template A"));

    const nameInput = screen.getByTestId("start-run-name-input");
    expect(nameInput).toHaveAttribute("maxLength", "30");
  });
});
