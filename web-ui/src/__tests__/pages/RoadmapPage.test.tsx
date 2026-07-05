import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import RoadmapPage from "@/pages/RoadmapPage";
import type { FeatureProposalResponse } from "@/lib/types";

const mockUseMobileBreakpoint = vi.fn().mockReturnValue(false);
vi.mock("@/hooks/useMobileBreakpoint", () => ({
  useMobileBreakpoint: () => mockUseMobileBreakpoint(),
}));

const mockUseFeatureProposals = vi.fn();
const mockUseDeleteFeatureProposal = vi.fn();
const mockUseStartFeatureProposal = vi.fn();
const mockUseRollOutFeatureProposal = vi.fn();

vi.mock("@/hooks/useFeatureProposals", () => ({
  useFeatureProposals: () => mockUseFeatureProposals(),
  useDeleteFeatureProposal: () => mockUseDeleteFeatureProposal(),
  useStartFeatureProposal: () => mockUseStartFeatureProposal(),
  useRollOutFeatureProposal: () => mockUseRollOutFeatureProposal(),
  useCreateFeatureProposal: () => ({
    mutate: vi.fn(),
    isPending: false,
    isError: false,
    reset: vi.fn(),
  }),
  useUpdateFeatureProposal: () => ({
    mutate: vi.fn(),
    isPending: false,
    isError: false,
    reset: vi.fn(),
  }),
}));

vi.mock("@/hooks/useFeatureProposalSubscription", () => ({
  useFeatureProposalSubscription: vi.fn(),
}));

// SoftwareProjectSelect is rendered inside CreateProposalDialog / EditProposalDialog;
// since those dialogs are conditionally mounted, we mock the underlying hook too.
vi.mock("@/hooks/useSoftwareProjects", () => ({
  useSoftwareProjects: () => ({ data: [] }),
}));

// Mock useRun so the proposal detail view can render without hitting the API. The default
// returns an empty run payload; individual tests override the returned data when needed.
const mockUseRun = vi.fn().mockReturnValue({ data: undefined });
vi.mock("@/hooks/useRuns", () => ({
  useRun: (id: string) => mockUseRun(id),
}));

const mutationDefaults = {
  mutate: vi.fn(),
  isPending: false,
  isError: false,
  reset: vi.fn(),
};

beforeEach(() => {
  mockUseDeleteFeatureProposal.mockReturnValue(mutationDefaults);
  mockUseStartFeatureProposal.mockReturnValue(mutationDefaults);
  mockUseRollOutFeatureProposal.mockReturnValue(mutationDefaults);
  mockUseMobileBreakpoint.mockReturnValue(false);
  mockUseRun.mockReturnValue({ data: undefined });
});

const makeProposal = (
  overrides: Partial<FeatureProposalResponse> = {}
): FeatureProposalResponse => ({
  id: "1",
  title: "Add login page",
  description: "Build OAuth login",
  motivation: null,
  status: "backlog",
  softwareProject: {
    id: "sp-1",
    type: "git_repo",
    name: "repo",
  },
  repos: [{ id: "r1", url: "https://github.com/example/repo", name: "repo" }],
  workflowRunId: null,
  workflowRunStatus: null,
  createdAt: "2026-04-01T00:00:00Z",
  updatedAt: "2026-04-01T00:00:00Z",
  ...overrides,
});

describe("RoadmapPage", () => {
  it("renders empty state when no proposals", () => {
    mockUseFeatureProposals.mockReturnValue({ data: { content: [], totalElements: 0, totalPages: 1, number: 0, size: 20, first: true, last: true, empty: true }, isLoading: false });
    renderWithProviders(<RoadmapPage />, { initialEntries: ["/roadmap"] });
    expect(screen.getByText(/no proposals yet/i)).toBeInTheDocument();
  });

  it("renders proposal list", () => {
    const proposals = [makeProposal()];
    mockUseFeatureProposals.mockReturnValue({
      data: { content: proposals, totalElements: 1, totalPages: 1, number: 0, size: 20, first: true, last: true, empty: false },
      isLoading: false,
    });
    renderWithProviders(<RoadmapPage />, { initialEntries: ["/roadmap"] });
    expect(screen.getAllByText("Add login page").length).toBeGreaterThan(0);
    expect(screen.getAllByText("backlog").length).toBeGreaterThan(0);
  });

  it("shows loading skeletons", () => {
    mockUseFeatureProposals.mockReturnValue({ data: undefined, isLoading: true });
    const { container } = renderWithProviders(<RoadmapPage />, {
      initialEntries: ["/roadmap"],
    });
    expect(container.querySelectorAll("[data-slot='skeleton']").length).toBeGreaterThan(0);
  });

  it("renders Roadmap heading", () => {
    mockUseFeatureProposals.mockReturnValue({ data: { content: [], totalElements: 0, totalPages: 1, number: 0, size: 20, first: true, last: true, empty: true }, isLoading: false });
    renderWithProviders(<RoadmapPage />, { initialEntries: ["/roadmap"] });
    expect(screen.getByText("Roadmap")).toBeInTheDocument();
  });

  // -------------------------------------------------------------------------
  // SoftwareProject + repo-pill rendering in the detail panel
  // -------------------------------------------------------------------------

  it("renders the software project chip and one repo pill for a single-repo proposal", () => {
    const proposal = makeProposal();
    mockUseFeatureProposals.mockReturnValue({
      data: { content: [proposal], totalElements: 1, totalPages: 1, number: 0, size: 20, first: true, last: true, empty: false },
      isLoading: false,
    });
    renderWithProviders(<RoadmapPage />, { initialEntries: ["/roadmap"] });
    const sp = screen.getByTestId("proposal-software-project");
    expect(sp).toBeInTheDocument();
    expect(sp).toHaveTextContent("repo");
    const pills = screen.getAllByTestId("proposal-repo-pill");
    expect(pills).toHaveLength(1);
    expect(pills[0]).toHaveAttribute("href", "https://github.com/example/repo");
  });

  it("renders multiple repo pills for a repo-group-backed proposal", () => {
    const proposal = makeProposal({
      softwareProject: { id: "sp-2", type: "repo_group", name: "Backend Stack" },
      repos: [
        { id: "r1", url: "https://github.com/example/backend-api.git", name: "backend-api" },
        { id: "r2", url: "https://github.com/example/web-ui.git", name: "web-ui" },
      ],
    });
    mockUseFeatureProposals.mockReturnValue({
      data: { content: [proposal], totalElements: 1, totalPages: 1, number: 0, size: 20, first: true, last: true, empty: false },
      isLoading: false,
    });
    renderWithProviders(<RoadmapPage />, { initialEntries: ["/roadmap"] });
    expect(screen.getByTestId("proposal-software-project")).toHaveTextContent(
      "Backend Stack"
    );
    const pills = screen.getAllByTestId("proposal-repo-pill");
    expect(pills).toHaveLength(2);
    expect(pills[0]).toHaveTextContent("backend-api");
    expect(pills[1]).toHaveTextContent("web-ui");
  });

  it("renders PullRequestLinks sourced from useRun(workflowRunId).pullRequests", () => {
    const proposal = makeProposal({
      workflowRunId: "run-1",
      workflowRunStatus: "completed",
    });
    mockUseFeatureProposals.mockReturnValue({
      data: { content: [proposal], totalElements: 1, totalPages: 1, number: 0, size: 20, first: true, last: true, empty: false },
      isLoading: false,
    });
    mockUseRun.mockReturnValue({
      data: {
        id: "run-1",
        pullRequests: [
          {
            id: "pr-1",
            workflowRunId: "run-1",
            gitRepoId: "r1",
            nodeExecutionId: null,
            prUrl: "https://github.com/example/repo/pull/42",
            prNumber: 42,
            title: "Add login page",
            repoName: "repo",
            repoUrl: "https://github.com/example/repo",
            createdAt: "2026-04-02T00:00:00Z",
          },
        ],
      },
    });
    renderWithProviders(<RoadmapPage />, { initialEntries: ["/roadmap"] });
    // The PR link should be rendered via the existing PullRequestLinks component.
    // Its distinguishing marker is the "Pull Requests" heading.
    expect(screen.getByText("Pull Requests")).toBeInTheDocument();
    // And the PR anchor points to the mocked pr URL.
    const anchor = screen.getByRole("link", { name: /Add login page/i });
    expect(anchor).toHaveAttribute("href", "https://github.com/example/repo/pull/42");
  });

  // -------------------------------------------------------------------------
  // Mobile layout tests
  // -------------------------------------------------------------------------

  const proposals = [
    makeProposal(),
    makeProposal({
      id: "2",
      title: "Add dashboard",
      description: "Build analytics dashboard",
      createdAt: "2026-04-02T00:00:00Z",
      updatedAt: "2026-04-02T00:00:00Z",
    }),
  ];

  const proposalPageData = {
    content: proposals,
    totalElements: 2,
    totalPages: 1,
    number: 0,
    size: 20,
    first: true,
    last: true,
    empty: false,
  };

  it("mobile: shows only list when no proposal selected", () => {
    mockUseMobileBreakpoint.mockReturnValue(true);
    mockUseFeatureProposals.mockReturnValue({ data: proposalPageData, isLoading: false });
    renderWithProviders(<RoadmapPage />, { initialEntries: ["/roadmap"] });

    // List is visible
    expect(screen.getByTestId("proposal-list")).toBeInTheDocument();
    // Detail is not visible (no auto-select on mobile)
    expect(screen.queryByTestId("proposal-detail-title")).not.toBeInTheDocument();
  });

  it("mobile: shows detail with back button when proposal selected", async () => {
    mockUseMobileBreakpoint.mockReturnValue(true);
    mockUseFeatureProposals.mockReturnValue({ data: proposalPageData, isLoading: false });
    const user = userEvent.setup();
    renderWithProviders(<RoadmapPage />, { initialEntries: ["/roadmap"] });

    // Click the first proposal
    const items = screen.getAllByTestId("proposal-item");
    await user.click(items[0]);

    // Detail is now visible with back button
    expect(screen.getByTestId("proposal-detail-title")).toBeInTheDocument();
    expect(screen.getByTestId("proposal-back-button")).toBeInTheDocument();
    // List is hidden
    expect(screen.queryByTestId("proposal-list")).not.toBeInTheDocument();
  });

  it("mobile: back button returns to list view", async () => {
    mockUseMobileBreakpoint.mockReturnValue(true);
    mockUseFeatureProposals.mockReturnValue({ data: proposalPageData, isLoading: false });
    const user = userEvent.setup();
    renderWithProviders(<RoadmapPage />, { initialEntries: ["/roadmap"] });

    // Click a proposal to go to detail
    const items = screen.getAllByTestId("proposal-item");
    await user.click(items[0]);
    expect(screen.getByTestId("proposal-detail-title")).toBeInTheDocument();

    // Click back
    await user.click(screen.getByTestId("proposal-back-button"));

    // List is visible again, detail is hidden
    expect(screen.getByTestId("proposal-list")).toBeInTheDocument();
    expect(screen.queryByTestId("proposal-detail-title")).not.toBeInTheDocument();
  });

  it("desktop: shows both panels simultaneously", () => {
    mockUseMobileBreakpoint.mockReturnValue(false);
    mockUseFeatureProposals.mockReturnValue({ data: proposalPageData, isLoading: false });
    renderWithProviders(<RoadmapPage />, { initialEntries: ["/roadmap"] });

    // Both list and detail are visible (auto-selects first on desktop)
    expect(screen.getByTestId("proposal-list")).toBeInTheDocument();
    expect(screen.getByTestId("proposal-detail-title")).toBeInTheDocument();
    // No back button on desktop
    expect(screen.queryByTestId("proposal-back-button")).not.toBeInTheDocument();
  });

  // -------------------------------------------------------------------------
  // Mobile wrapping regression guards
  //
  // These assertions check className strings only — jsdom does no layout, so
  // visual correctness (no horizontal scroll, ellipsis appearing, etc.) is
  // verified by the Playwright spec at e2e/specs/roadmap-mobile-layout.spec.ts.
  // The unit tests here exist to prevent the className strings from being
  // silently dropped in a future refactor.
  //
  // The "truncate AND min-w-0" pair is asserted together intentionally: in
  // a flex parent, `truncate` (white-space: nowrap) without `min-w-0`
  // resolves to min-content sizing and never visually truncates — checking
  // only `truncate` would be a tautology that passes even when truncation
  // is broken.
  // -------------------------------------------------------------------------
  describe("mobile wrapping", () => {
    it("title has break-words and the closest header column has min-w-0", () => {
      const proposal = makeProposal({
        title:
          "supercalifragilisticexpialidociousVeryLongUnbreakableTitleString",
      });
      mockUseFeatureProposals.mockReturnValue({
        data: {
          content: [proposal],
          totalElements: 1,
          totalPages: 1,
          number: 0,
          size: 20,
          first: true,
          last: true,
          empty: false,
        },
        isLoading: false,
      });
      renderWithProviders(<RoadmapPage />, { initialEntries: ["/roadmap"] });
      const title = screen.getByTestId("proposal-detail-title");
      expect(title.className).toMatch(/break-words/);
      // The header column is the closest div.flex.flex-col ancestor.
      const headerCol = title.closest("div.flex.flex-col");
      expect(headerCol).not.toBeNull();
      expect(headerCol?.className).toMatch(/min-w-0/);
    });

    it("chip inner span has BOTH truncate and min-w-0 (necessary together to actually truncate)", () => {
      const proposal = makeProposal({
        softwareProject: {
          id: "sp-long",
          type: "repo_group",
          name: "an-extremely-long-software-project-name-that-must-truncate-on-a-narrow-viewport",
        },
      });
      mockUseFeatureProposals.mockReturnValue({
        data: {
          content: [proposal],
          totalElements: 1,
          totalPages: 1,
          number: 0,
          size: 20,
          first: true,
          last: true,
          empty: false,
        },
        isLoading: false,
      });
      renderWithProviders(<RoadmapPage />, { initialEntries: ["/roadmap"] });
      const chip = screen.getByTestId("proposal-software-project-chip");
      expect(chip).toHaveAttribute("title", proposal.softwareProject.name);
      // The label span (the truncating descendant) must have BOTH classes.
      // truncate alone is a tautology — without min-w-0 in a flex parent it
      // does not visually truncate. Both checks together prevent the
      // regression mode iteration 1's review flagged.
      const label = chip.querySelector("span.truncate");
      expect(label).not.toBeNull();
      expect(label?.className).toMatch(/truncate/);
      expect(label?.className).toMatch(/min-w-0/);
      // And the chip's outer inline-flex itself carries min-w-0 + max-w-full.
      expect(chip.className).toMatch(/min-w-0/);
      expect(chip.className).toMatch(/max-w-full/);
    });

    it("repo pill anchor truncates a long repo name (inline-block + max-w-full + truncate, with title attr)", () => {
      const longName =
        "an-extremely-long-repository-name-that-must-truncate-not-overflow";
      const proposal = makeProposal({
        repos: [{ id: "r1", url: "https://example.invalid", name: longName }],
      });
      mockUseFeatureProposals.mockReturnValue({
        data: {
          content: [proposal],
          totalElements: 1,
          totalPages: 1,
          number: 0,
          size: 20,
          first: true,
          last: true,
          empty: false,
        },
        isLoading: false,
      });
      renderWithProviders(<RoadmapPage />, { initialEntries: ["/roadmap"] });
      const pill = screen.getByTestId("proposal-repo-pill");
      expect(pill).toHaveAttribute("title", longName);
      expect(pill.textContent).toBe(longName);
      expect(pill.className).toMatch(/truncate/);
      expect(pill.className).toMatch(/max-w-full/);
      expect(pill.className).toMatch(/inline-block/);
    });
  });
});
