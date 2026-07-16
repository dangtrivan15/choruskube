import { useQuery } from "@tanstack/react-query";
import { api } from "@/lib/api";
import type {
  AnalyticsOverviewResponse,
  RunTrendResponse,
  TemplateAnalyticsResponse,
  NodeAnalyticsResponse,
  BottleneckResponse,
  RoadmapStatusCountsResponse,
  RoadmapThroughputResponse,
} from "@/lib/types";

const ANALYTICS_STALE_TIME = 5 * 60 * 1000; // 5 minutes

export function useAnalyticsOverview(period: string) {
  return useQuery({
    queryKey: ["analytics", "overview", period],
    queryFn: () => api.get<AnalyticsOverviewResponse>(`/analytics/overview?period=${period}`),
    staleTime: ANALYTICS_STALE_TIME,
    placeholderData: (prev) => prev,
  });
}

export function useRunTrend(period: string) {
  return useQuery({
    queryKey: ["analytics", "runs", period],
    queryFn: () => api.get<RunTrendResponse>(`/analytics/runs?period=${period}`),
    staleTime: ANALYTICS_STALE_TIME,
    placeholderData: (prev) => prev,
  });
}

export function useTemplateAnalytics(period: string) {
  return useQuery({
    queryKey: ["analytics", "templates", period],
    queryFn: () => api.get<TemplateAnalyticsResponse>(`/analytics/templates?period=${period}`),
    staleTime: ANALYTICS_STALE_TIME,
    placeholderData: (prev) => prev,
  });
}

export function useNodeAnalytics(period: string) {
  return useQuery({
    queryKey: ["analytics", "nodes", period],
    queryFn: () => api.get<NodeAnalyticsResponse>(`/analytics/nodes?period=${period}`),
    staleTime: ANALYTICS_STALE_TIME,
    placeholderData: (prev) => prev,
  });
}

export function useBottlenecks(period: string) {
  return useQuery({
    queryKey: ["analytics", "bottlenecks", period],
    queryFn: () => api.get<BottleneckResponse>(`/analytics/bottlenecks?period=${period}`),
    staleTime: ANALYTICS_STALE_TIME,
    placeholderData: (prev) => prev,
  });
}

export function useRoadmapStatusCounts() {
  return useQuery({
    queryKey: ["analytics", "roadmap", "status-counts"],
    queryFn: () => api.get<RoadmapStatusCountsResponse>("/analytics/roadmap/status-counts"),
    staleTime: ANALYTICS_STALE_TIME,
    placeholderData: (prev) => prev,
  });
}

export function useRoadmapThroughput(period: string) {
  return useQuery({
    queryKey: ["analytics", "roadmap", "throughput", period],
    queryFn: () => api.get<RoadmapThroughputResponse>(`/analytics/roadmap/throughput?period=${period}`),
    staleTime: ANALYTICS_STALE_TIME,
    placeholderData: (prev) => prev,
  });
}
