import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/__tests__/test-utils";
import UsageDashboard from "@/components/analytics/UsageDashboard";

vi.mock("@/hooks/useCurrentOrg", () => ({
  useCurrentOrg: () => "org-123",
}));

vi.mock("@/hooks/useUsage", () => ({
  useUsageSummary: vi.fn(),
}));

import { useUsageSummary } from "@/hooks/useUsage";

const mockUseUsageSummary = useUsageSummary as ReturnType<typeof vi.fn>;

const defaultUsageData = {
  organizationId: "org-123",
  concurrentRuns: { current: 3, limit: 10 },
  repos: { current: 2, limit: 25 },
  monthlyRuns: { current: 42, limit: 500, periodStart: "2026-04-01T00:00:00Z" },
  monthlyNodeExecutions: { current: 387, limit: 5000, periodStart: "2026-04-01T00:00:00Z" },
  k8s: { maxPodsPerNamespace: 20, maxCpuPerNamespace: "8", maxMemoryPerNamespace: "16Gi" },
  k8sAggregate: {
    totalCpuAllocated: "24",
    maxCpuPerOrg: "32",
    totalMemoryAllocated: "66Gi",
    maxMemoryPerOrg: "64Gi",
    enforced: false,
  },
};

describe("UsageDashboard", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("renders all four usage metrics", () => {
    mockUseUsageSummary.mockReturnValue({ data: defaultUsageData, isLoading: false, error: null });
    renderWithProviders(<UsageDashboard />);

    expect(screen.getByText("Resource Usage")).toBeInTheDocument();
    expect(screen.getByText("Concurrent Runs")).toBeInTheDocument();
    expect(screen.getByText("Repositories")).toBeInTheDocument();
    expect(screen.getByText("Monthly Runs")).toBeInTheDocument();
    expect(screen.getByText("Monthly Executions")).toBeInTheDocument();
  });

  it("displays current/limit values correctly", () => {
    mockUseUsageSummary.mockReturnValue({ data: defaultUsageData, isLoading: false, error: null });
    renderWithProviders(<UsageDashboard />);

    // Check concurrent runs values
    const concurrentRunsCard = screen.getByTestId("quota-concurrent-runs");
    expect(concurrentRunsCard).toHaveTextContent("3");
    expect(concurrentRunsCard).toHaveTextContent("/ 10");
  });

  it("shows warning badge when usage exceeds 80%", () => {
    const highUsage = {
      ...defaultUsageData,
      concurrentRuns: { current: 9, limit: 10 },
    };
    mockUseUsageSummary.mockReturnValue({ data: highUsage, isLoading: false, error: null });
    renderWithProviders(<UsageDashboard />);

    expect(screen.getAllByText("Critical").length).toBeGreaterThanOrEqual(1);
  });

  it("shows warning badge at 80% usage", () => {
    const warningUsage = {
      ...defaultUsageData,
      repos: { current: 21, limit: 25 },
    };
    mockUseUsageSummary.mockReturnValue({ data: warningUsage, isLoading: false, error: null });
    renderWithProviders(<UsageDashboard />);

    expect(screen.getByText("Warning")).toBeInTheDocument();
  });

  it("handles loading state", () => {
    mockUseUsageSummary.mockReturnValue({ data: undefined, isLoading: true, error: null });
    renderWithProviders(<UsageDashboard />);

    // Should not render any metric titles when loading
    expect(screen.queryByText("Concurrent Runs")).not.toBeInTheDocument();
  });

  it("handles error state", () => {
    mockUseUsageSummary.mockReturnValue({ data: undefined, isLoading: false, error: new Error("fail") });
    renderWithProviders(<UsageDashboard />);

    expect(screen.getByText("Failed to load usage data.")).toBeInTheDocument();
  });

  it("renders cluster resources section", () => {
    mockUseUsageSummary.mockReturnValue({ data: defaultUsageData, isLoading: false, error: null });
    renderWithProviders(<UsageDashboard />);

    expect(screen.getByText("Cluster Resources")).toBeInTheDocument();
    expect(screen.getByText("CPU (cores)")).toBeInTheDocument();
    expect(screen.getByText("Memory")).toBeInTheDocument();
  });

  it("shows error when k8sAggregate is absent", () => {
    const { k8sAggregate: _, ...noAggregate } = defaultUsageData;
    mockUseUsageSummary.mockReturnValue({ data: noAggregate, isLoading: false, error: null });
    renderWithProviders(<UsageDashboard />);

    expect(screen.getByText("Cluster Resources")).toBeInTheDocument();
    expect(screen.getByText("Unable to reach cluster metrics")).toBeInTheDocument();
  });

  it("shows monitoring only badge when not enforced", () => {
    mockUseUsageSummary.mockReturnValue({ data: defaultUsageData, isLoading: false, error: null });
    renderWithProviders(<UsageDashboard />);

    expect(screen.getByText("Monitoring Only")).toBeInTheDocument();
  });

  it("shows warning for aggregate CPU at 80%+", () => {
    const highCpu = {
      ...defaultUsageData,
      k8sAggregate: { ...defaultUsageData.k8sAggregate, totalCpuAllocated: "28", maxCpuPerOrg: "32", enforced: true },
    };
    mockUseUsageSummary.mockReturnValue({ data: highCpu, isLoading: false, error: null });
    renderWithProviders(<UsageDashboard />);

    expect(screen.getAllByText("Warning").length).toBeGreaterThanOrEqual(1);
  });

  it("displays 'Unlimited' text for quota metrics when limit is -1 (system-tier sentinel)", () => {
    const unlimitedUsage = {
      ...defaultUsageData,
      concurrentRuns: { current: 3, limit: -1 },
    };
    mockUseUsageSummary.mockReturnValue({ data: unlimitedUsage, isLoading: false, error: null });
    renderWithProviders(<UsageDashboard />);

    const concurrentRunsCard = screen.getByTestId("quota-concurrent-runs");
    expect(concurrentRunsCard).toHaveTextContent("Unlimited");
    // Should NOT show a warning/critical badge for unlimited orgs
    expect(concurrentRunsCard).not.toHaveTextContent("Warning");
    expect(concurrentRunsCard).not.toHaveTextContent("Critical");
  });
});
