import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import MilestonesPage from "@/pages/MilestonesPage";
import type {
  MilestoneResponse,
  MilestoneAtRiskItemsResponse,
  PageResponse,
  SoftwareProject,
} from "@/lib/types";

vi.mock("@/lib/api", () => ({
  api: {
    get: vi.fn(),
    getPage: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    patch: vi.fn(),
    delete: vi.fn(),
  },
}));

vi.mock("@/lib/toast-messages", () => ({
  showMutationToast: vi.fn((message: string, variant: string) => ({
    id: "mock-id",
    timestamp: Date.now(),
    message,
    variant,
  })),
}));

import { api } from "@/lib/api";

const mockApi = api as unknown as {
  get: ReturnType<typeof vi.fn>;
  getPage: ReturnType<typeof vi.fn>;
  post: ReturnType<typeof vi.fn>;
  put: ReturnType<typeof vi.fn>;
  patch: ReturnType<typeof vi.fn>;
  delete: ReturnType<typeof vi.fn>;
};

const softwareProjects: SoftwareProject[] = [
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
];

function makeMilestone(overrides: Partial<MilestoneResponse> = {}): MilestoneResponse {
  return {
    id: "m1",
    name: "Q3 Launch",
    description: null,
    softwareProjectId: "r1",
    targetDate: "2020-01-01",
    epicCount: 1,
    progress: { totalTasks: 4, doneTasks: 1, inProgressTasks: 1, notStartedTasks: 2 },
    atRisk: false,
    atRiskItemCount: 0,
    createdAt: "2026-01-01T00:00:00Z",
    updatedAt: "2026-01-01T00:00:00Z",
    ...overrides,
  };
}

function makeMilestonesPage(content: MilestoneResponse[]): PageResponse<MilestoneResponse> {
  return {
    content,
    totalElements: content.length,
    totalPages: 1,
    size: 100,
    number: 0,
    first: true,
    last: true,
    empty: content.length === 0,
  };
}

beforeEach(() => {
  vi.clearAllMocks();
  mockApi.get.mockImplementation((path: string) => {
    if (path === "/software-projects") return Promise.resolve(softwareProjects);
    return Promise.reject(new Error(`unexpected GET ${path}`));
  });
});

describe("MilestonesPage", () => {
  it("shows the at-risk badge and progress bar for an at-risk milestone", async () => {
    const atRisk = makeMilestone({
      id: "m-at-risk",
      name: "At Risk Milestone",
      atRisk: true,
      atRiskItemCount: 2,
    });
    mockApi.getPage.mockResolvedValueOnce(makeMilestonesPage([atRisk]));

    renderWithProviders(<MilestonesPage />);

    await waitFor(() => expect(screen.getByText("At Risk Milestone")).toBeInTheDocument());
    expect(screen.getByTestId("milestone-at-risk-badge")).toHaveTextContent("At Risk (2)");
    expect(screen.getByTestId("milestone-progress-bar")).toHaveAttribute("title", "1/4 tasks done");
    expect(screen.getByText("1/4")).toBeInTheDocument();
  });

  it("shows no at-risk badge or toggle for a milestone that is not at risk", async () => {
    const notAtRisk = makeMilestone({
      id: "m-safe",
      name: "Safe Milestone",
      atRisk: false,
      atRiskItemCount: 0,
    });
    mockApi.getPage.mockResolvedValueOnce(makeMilestonesPage([notAtRisk]));

    renderWithProviders(<MilestonesPage />);

    await waitFor(() => expect(screen.getByText("Safe Milestone")).toBeInTheDocument());
    expect(screen.queryByTestId("milestone-at-risk-badge")).not.toBeInTheDocument();
    expect(screen.queryByTestId("milestone-at-risk-toggle")).not.toBeInTheDocument();
  });

  it("expanding an at-risk milestone's row fetches and lists its at-risk items", async () => {
    const atRisk = makeMilestone({
      id: "m-drilldown",
      name: "Drilldown Milestone",
      atRisk: true,
      atRiskItemCount: 1,
    });
    mockApi.getPage.mockResolvedValueOnce(makeMilestonesPage([atRisk]));
    const atRiskItems: MilestoneAtRiskItemsResponse = {
      items: [
        {
          id: "epic-1",
          tier: "EPIC",
          title: "Overdue Epic",
          targetDate: "2020-01-01",
          status: "backlog",
        },
      ],
    };
    mockApi.get.mockImplementation((path: string) => {
      if (path === "/software-projects") return Promise.resolve(softwareProjects);
      if (path === "/milestones/m-drilldown/at-risk-items") return Promise.resolve(atRiskItems);
      return Promise.reject(new Error(`unexpected GET ${path}`));
    });

    renderWithProviders(<MilestonesPage />);
    await waitFor(() => expect(screen.getByText("Drilldown Milestone")).toBeInTheDocument());

    // Nothing is fetched for the drill-down until the row is expanded.
    expect(mockApi.get).not.toHaveBeenCalledWith("/milestones/m-drilldown/at-risk-items");

    const user = userEvent.setup();
    await user.click(screen.getByTestId("milestone-at-risk-toggle"));

    await waitFor(() =>
      expect(mockApi.get).toHaveBeenCalledWith("/milestones/m-drilldown/at-risk-items")
    );
    expect(await screen.findByText("Overdue Epic")).toBeInTheDocument();
    expect(screen.getByTestId("milestone-at-risk-item")).toHaveTextContent("EPIC");
  });
});
