import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { createTestHookWrapper } from "@/__tests__/test-utils";
import type { RoadmapTimelineResponse } from "@/lib/types";

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

import { api } from "@/lib/api";
import { useRoadmapTimeline } from "@/hooks/useRoadmapTimeline";

const mockApi = api as unknown as {
  get: ReturnType<typeof vi.fn>;
};

function makeResponse(): RoadmapTimelineResponse {
  return {
    epics: [
      {
        id: "epic-1",
        title: "Add dark mode",
        stage: "in_progress",
        priority: "medium",
        createdAt: "2026-04-01T00:00:00Z",
        updatedAt: "2026-04-01T00:00:00Z",
        stalled: false,
        milestone: null,
        stories: [
          {
            id: "story-1",
            epicId: "epic-1",
            title: "Dark theme toggle",
            stage: "backlog",
            priority: "medium",
            createdAt: "2026-04-01T00:00:00Z",
            updatedAt: "2026-04-01T00:00:00Z",
            readiness: "READY",
            stalled: false,
          },
        ],
      },
    ],
  };
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe("useRoadmapTimeline", () => {
  it("fetches from /roadmap/timeline using the ['epics', 'timeline'] query key", async () => {
    mockApi.get.mockResolvedValue(makeResponse());
    const { wrapper, queryClient } = createTestHookWrapper();

    const { result } = renderHook(() => useRoadmapTimeline(), { wrapper });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(mockApi.get).toHaveBeenCalledWith("/roadmap/timeline");
    expect(queryClient.getQueryData(["epics", "timeline"])).toEqual(makeResponse());
  });

  it("starts in a loading state before the fetch resolves", () => {
    mockApi.get.mockReturnValue(new Promise(() => {})); // never resolves
    const { wrapper } = createTestHookWrapper();

    const { result } = renderHook(() => useRoadmapTimeline(), { wrapper });

    expect(result.current.isLoading).toBe(true);
    expect(result.current.data).toBeUndefined();
  });

  it("surfaces a fetch error via isError", async () => {
    mockApi.get.mockRejectedValue(new Error("boom"));
    const { wrapper } = createTestHookWrapper();

    const { result } = renderHook(() => useRoadmapTimeline(), { wrapper });

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.data).toBeUndefined();
  });
});
