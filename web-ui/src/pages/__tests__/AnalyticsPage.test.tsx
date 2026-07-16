import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import AnalyticsPage from "@/pages/AnalyticsPage";

vi.mock("@/hooks/useCurrentOrg", () => ({
  useCurrentOrg: () => "org-123",
}));

vi.mock("@/hooks/useUsage", () => ({
  useUsageSummary: vi.fn(() => ({
    data: {
      organizationId: "org-123",
      concurrentRuns: { current: 3, limit: 10 },
      repos: { current: 2, limit: 25 },
      monthlyRuns: { current: 99, limit: 500, periodStart: "2026-04-01T00:00:00Z" },
      monthlyNodeExecutions: { current: 387, limit: 5000, periodStart: "2026-04-01T00:00:00Z" },
      k8s: { maxPodsPerNamespace: 20, maxCpuPerNamespace: "8", maxMemoryPerNamespace: "16Gi" },
    },
    isLoading: false,
    error: null,
  })),
}));

vi.mock("@/hooks/useAnalytics", () => ({
  useAnalyticsOverview: vi.fn(),
  useRunTrend: vi.fn(),
  useTemplateAnalytics: vi.fn(),
  useNodeAnalytics: vi.fn(),
  useBottlenecks: vi.fn(),
  useRoadmapStatusCounts: vi.fn(),
  useRoadmapThroughput: vi.fn(),
}));

import {
  useAnalyticsOverview,
  useRunTrend,
  useTemplateAnalytics,
  useNodeAnalytics,
  useBottlenecks,
  useRoadmapStatusCounts,
  useRoadmapThroughput,
} from "@/hooks/useAnalytics";

const mockOverview = useAnalyticsOverview as ReturnType<typeof vi.fn>;
const mockRunTrend = useRunTrend as ReturnType<typeof vi.fn>;
const mockTemplateAnalytics = useTemplateAnalytics as ReturnType<typeof vi.fn>;
const mockNodeAnalytics = useNodeAnalytics as ReturnType<typeof vi.fn>;
const mockBottlenecks = useBottlenecks as ReturnType<typeof vi.fn>;
const mockRoadmapStatusCounts = useRoadmapStatusCounts as ReturnType<typeof vi.fn>;
const mockRoadmapThroughput = useRoadmapThroughput as ReturnType<typeof vi.fn>;

function setupMocks(overrides?: {
  overview?: Record<string, unknown>;
  trend?: Record<string, unknown>;
  templates?: Record<string, unknown>;
  nodes?: Record<string, unknown>;
  bottlenecks?: Record<string, unknown>;
  statusCounts?: Record<string, unknown>;
  throughput?: Record<string, unknown>;
}) {
  mockOverview.mockReturnValue({
    data: overrides?.overview ?? {
      totalRuns: 42,
      completedRuns: 35,
      failedRuns: 5,
      successRate: 83.33,
      avgDurationSeconds: 120.5,
      p50DurationSeconds: 100.0,
      p95DurationSeconds: 300.0,
    },
    isLoading: false,
  });
  mockRunTrend.mockReturnValue({
    data: overrides?.trend ?? { points: [] },
    isLoading: false,
  });
  mockTemplateAnalytics.mockReturnValue({
    data: overrides?.templates ?? { templates: [] },
    isLoading: false,
  });
  mockNodeAnalytics.mockReturnValue({
    data: overrides?.nodes ?? { nodes: [] },
    isLoading: false,
  });
  mockBottlenecks.mockReturnValue({
    data: overrides?.bottlenecks ?? { bottlenecks: [] },
    isLoading: false,
  });
  mockRoadmapStatusCounts.mockReturnValue({
    data: overrides?.statusCounts ?? { total: 0, statuses: [] },
    isLoading: false,
  });
  mockRoadmapThroughput.mockReturnValue({
    data: overrides?.throughput ?? { points: [] },
    isLoading: false,
  });
}

describe("AnalyticsPage", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("renders the page title", () => {
    setupMocks();
    renderWithProviders(<AnalyticsPage />, { initialEntries: ["/analytics"] });
    expect(screen.getByText("Analytics")).toBeInTheDocument();
  });

  it("renders overview stat cards", () => {
    setupMocks();
    renderWithProviders(<AnalyticsPage />, { initialEntries: ["/analytics"] });
    expect(screen.getByText("Total Runs")).toBeInTheDocument();
    expect(screen.getByText("42")).toBeInTheDocument();
    expect(screen.getByText("Completed")).toBeInTheDocument();
    expect(screen.getByText("35")).toBeInTheDocument();
    expect(screen.getByText("Failed")).toBeInTheDocument();
    expect(screen.getByText("5")).toBeInTheDocument();
    expect(screen.getByText("83.3%")).toBeInTheDocument();
  });

  it("renders period selector buttons", () => {
    setupMocks();
    renderWithProviders(<AnalyticsPage />, { initialEntries: ["/analytics"] });
    expect(screen.getByText("Last 24 hours")).toBeInTheDocument();
    expect(screen.getByText("Last 7 days")).toBeInTheDocument();
    expect(screen.getByText("Last 30 days")).toBeInTheDocument();
    expect(screen.getByText("Last 90 days")).toBeInTheDocument();
  });

  it("switches period when button is clicked", async () => {
    setupMocks();
    const user = userEvent.setup();
    renderWithProviders(<AnalyticsPage />, { initialEntries: ["/analytics"] });

    await user.click(screen.getByText("Last 7 days"));

    // The hooks should be called with the new period
    expect(mockOverview).toHaveBeenCalledWith("7d");
  });

  it("renders section headings", () => {
    setupMocks();
    renderWithProviders(<AnalyticsPage />, { initialEntries: ["/analytics"] });
    expect(screen.getByText("Run Trend")).toBeInTheDocument();
    expect(screen.getByText("Templates")).toBeInTheDocument();
    expect(screen.getByText("Bottlenecks")).toBeInTheDocument();
    expect(screen.getByText("Node Executions")).toBeInTheDocument();
    expect(screen.getByText("Roadmap")).toBeInTheDocument();
  });

  it("shows loading skeletons when data is loading", () => {
    mockOverview.mockReturnValue({ data: undefined, isLoading: true });
    mockRunTrend.mockReturnValue({ data: undefined, isLoading: true });
    mockTemplateAnalytics.mockReturnValue({ data: undefined, isLoading: true });
    mockNodeAnalytics.mockReturnValue({ data: undefined, isLoading: true });
    mockBottlenecks.mockReturnValue({ data: undefined, isLoading: true });
    mockRoadmapStatusCounts.mockReturnValue({ data: undefined, isLoading: true });
    mockRoadmapThroughput.mockReturnValue({ data: undefined, isLoading: true });

    renderWithProviders(<AnalyticsPage />, { initialEntries: ["/analytics"] });

    // Should still render the heading
    expect(screen.getByText("Analytics")).toBeInTheDocument();
    // StatCards should not be rendered
    expect(screen.queryByText("Total Runs")).not.toBeInTheDocument();
  });

  it("renders template table with data", () => {
    setupMocks({
      templates: {
        templates: [
          { templateName: "My Template", runCount: 10, completedCount: 8, failedCount: 1, successRate: 80.0 },
        ],
      },
    });
    renderWithProviders(<AnalyticsPage />, { initialEntries: ["/analytics"] });
    expect(screen.getByText("My Template")).toBeInTheDocument();
    expect(screen.getByText("80.0%")).toBeInTheDocument();
  });

  it("renders empty state messages when no data", () => {
    setupMocks();
    renderWithProviders(<AnalyticsPage />, { initialEntries: ["/analytics"] });
    expect(screen.getByText("No run data for this period")).toBeInTheDocument();
    expect(screen.getByText("No template data for this period")).toBeInTheDocument();
    expect(screen.getByText("No bottleneck data for this period")).toBeInTheDocument();
    expect(screen.getByText("No node execution data for this period")).toBeInTheDocument();
  });

  it("formats duration values correctly", () => {
    setupMocks({
      overview: {
        totalRuns: 1,
        completedRuns: 1,
        failedRuns: 0,
        successRate: 100.0,
        avgDurationSeconds: 45,
        p50DurationSeconds: 90,
        p95DurationSeconds: 7200,
      },
    });
    renderWithProviders(<AnalyticsPage />, { initialEntries: ["/analytics"] });
    expect(screen.getByText("45s")).toBeInTheDocument();
    expect(screen.getByText("1.5m")).toBeInTheDocument();
    expect(screen.getByText("2.0h")).toBeInTheDocument();
  });

  it("applies min-w-0 to the Templates and Bottlenecks grid items", () => {
    setupMocks();
    renderWithProviders(<AnalyticsPage />, { initialEntries: ["/analytics"] });

    // Templates card: walk up to the closest .rounded-lg ancestor.
    const templatesCard = screen.getByText("Templates").closest('[class*="rounded-lg"]');
    expect(templatesCard).not.toBeNull();
    expect(templatesCard?.className).toContain("min-w-0");

    // Bottlenecks card: same shape.
    const bottlenecksCard = screen.getByText("Bottlenecks").closest('[class*="rounded-lg"]');
    expect(bottlenecksCard).not.toBeNull();
    expect(bottlenecksCard?.className).toContain("min-w-0");
  });

  it("applies min-w-0 to the Roadmap two-column children", () => {
    setupMocks({ statusCounts: { total: 0, statuses: [] } });
    renderWithProviders(<AnalyticsPage />, { initialEntries: ["/analytics"] });

    // Each sub-heading lives directly inside its column wrapper, which we
    // expect to carry min-w-0 for grid-item-shrink behavior.
    const statusColumn = screen
      .getByText("Tasks by Status")
      .closest('[class*="min-w-0"]');
    expect(statusColumn).not.toBeNull();

    const throughputColumn = screen
      .getByText("Task Throughput")
      .closest('[class*="min-w-0"]');
    expect(throughputColumn).not.toBeNull();
  });
});
