import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { createTestHookWrapper } from "@/__tests__/test-utils";

vi.mock("@/lib/api", () => ({
  api: {
    get: vi.fn(),
    getPage: vi.fn(),
    getText: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
}));

import { api } from "@/lib/api";
import {
  useAnalyticsOverview,
  useRunTrend,
  useTemplateAnalytics,
  useNodeAnalytics,
  useBottlenecks,
} from "@/hooks/useAnalytics";

const mockApi = api as unknown as {
  get: ReturnType<typeof vi.fn>;
};

describe("useAnalytics hooks", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  describe("useAnalyticsOverview", () => {
    it("fetches overview for given period", async () => {
      const data = {
        totalRuns: 10,
        completedRuns: 7,
        failedRuns: 2,
        successRate: 70.0,
        avgDurationSeconds: 120.5,
        p50DurationSeconds: 100.0,
        p95DurationSeconds: 300.0,
      };
      mockApi.get.mockResolvedValueOnce(data);
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(() => useAnalyticsOverview("30d"), { wrapper });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(result.current.data).toEqual(data);
      expect(mockApi.get).toHaveBeenCalledWith("/analytics/overview?period=30d");
    });

    it("handles fetch error", async () => {
      mockApi.get.mockRejectedValueOnce(new Error("server error"));
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(() => useAnalyticsOverview("7d"), { wrapper });

      await waitFor(() => expect(result.current.isError).toBe(true));
    });
  });

  describe("useRunTrend", () => {
    it("fetches trend data", async () => {
      const data = {
        points: [
          { date: "2026-03-01", total: 5, completed: 3, failed: 1 },
          { date: "2026-03-02", total: 8, completed: 6, failed: 2 },
        ],
      };
      mockApi.get.mockResolvedValueOnce(data);
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(() => useRunTrend("7d"), { wrapper });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(result.current.data?.points).toHaveLength(2);
      expect(mockApi.get).toHaveBeenCalledWith("/analytics/runs?period=7d");
    });
  });

  describe("useTemplateAnalytics", () => {
    it("fetches template analytics", async () => {
      const data = {
        templates: [
          { templateId: "t1", templateName: "Template 1", runCount: 10, completedCount: 8, failedCount: 1, successRate: 80.0 },
        ],
      };
      mockApi.get.mockResolvedValueOnce(data);
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(() => useTemplateAnalytics("30d"), { wrapper });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(result.current.data?.templates).toHaveLength(1);
      expect(mockApi.get).toHaveBeenCalledWith("/analytics/templates?period=30d");
    });
  });

  describe("useNodeAnalytics", () => {
    it("fetches node analytics", async () => {
      const data = {
        nodes: [
          { label: "ai_draft", executionCount: 20, completedCount: 18, failedCount: 2, successRate: 90.0 },
        ],
      };
      mockApi.get.mockResolvedValueOnce(data);
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(() => useNodeAnalytics("30d"), { wrapper });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(result.current.data?.nodes).toHaveLength(1);
      expect(mockApi.get).toHaveBeenCalledWith("/analytics/nodes?period=30d");
    });
  });

  describe("useBottlenecks", () => {
    it("fetches bottleneck data", async () => {
      const data = {
        bottlenecks: [
          { label: "slow_node", avgDurationSeconds: 600.12, p50DurationSeconds: 500.0, p95DurationSeconds: 900.0, sampleSize: 15 },
        ],
      };
      mockApi.get.mockResolvedValueOnce(data);
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(() => useBottlenecks("7d"), { wrapper });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(result.current.data?.bottlenecks).toHaveLength(1);
      expect(mockApi.get).toHaveBeenCalledWith("/analytics/bottlenecks?period=7d");
    });
  });
});
