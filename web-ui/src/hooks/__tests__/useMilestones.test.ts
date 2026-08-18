import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { createTestHookWrapper } from "@/__tests__/test-utils";

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
import {
  useMilestones,
  useMilestone,
  useCreateMilestone,
  useUpdateMilestone,
  useDeleteMilestone,
  useAssignEpicMilestone,
} from "@/hooks/useMilestones";
import type { MilestoneResponse, EpicResponse } from "@/lib/types";

const mockApi = api as unknown as {
  get: ReturnType<typeof vi.fn>;
  getPage: ReturnType<typeof vi.fn>;
  post: ReturnType<typeof vi.fn>;
  put: ReturnType<typeof vi.fn>;
  patch: ReturnType<typeof vi.fn>;
  delete: ReturnType<typeof vi.fn>;
};

function makeMilestone(overrides: Partial<MilestoneResponse> = {}): MilestoneResponse {
  return {
    id: "m1",
    name: "Q3 Launch",
    description: null,
    softwareProjectId: "r1",
    targetDate: null,
    epicCount: 0,
    createdAt: "2026-01-01T00:00:00Z",
    updatedAt: "2026-01-01T00:00:00Z",
    ...overrides,
  };
}

function makeEpic(overrides: Partial<EpicResponse> = {}): EpicResponse {
  return {
    id: "epic-1",
    title: "Add dark mode",
    description: "Add a dark theme",
    motivation: null,
    stage: "backlog",
    priority: "medium",
    targetDate: null,
    progress: { totalTasks: 0, doneTasks: 0, startedTasks: 0 },
    softwareProject: { id: "r1", type: "git_repo", name: "backend-api" },
    repos: [],
    createdAt: "2026-04-01T00:00:00Z",
    updatedAt: "2026-04-01T00:00:00Z",
    readyItemCount: 0,
    milestone: null,
    ...overrides,
  };
}

const emptyPage = {
  content: [] as MilestoneResponse[],
  totalElements: 0,
  totalPages: 1,
  size: 100,
  number: 0,
  first: true,
  last: true,
  empty: true,
};

describe("useMilestones hooks", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  describe("useMilestones", () => {
    it("fetches the unscoped list via GET /milestones", async () => {
      mockApi.getPage.mockResolvedValueOnce({ ...emptyPage, content: [makeMilestone()] });
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(() => useMilestones(), { wrapper });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(mockApi.getPage).toHaveBeenCalledWith("/milestones", { size: 100 });
    });

    it("scopes the query with ?softwareProjectId= when passed", async () => {
      mockApi.getPage.mockResolvedValueOnce(emptyPage);
      const { wrapper } = createTestHookWrapper();

      renderHook(() => useMilestones("r1"), { wrapper });

      await waitFor(() =>
        expect(mockApi.getPage).toHaveBeenCalledWith("/milestones?softwareProjectId=r1", {
          size: 100,
        })
      );
    });
  });

  describe("useMilestone", () => {
    it("fetches a single Milestone by id", async () => {
      mockApi.get.mockResolvedValueOnce(makeMilestone());
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(() => useMilestone("m1"), { wrapper });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(mockApi.get).toHaveBeenCalledWith("/milestones/m1");
    });

    it("does not fetch when id is undefined", () => {
      const { wrapper } = createTestHookWrapper();
      const { result } = renderHook(() => useMilestone(undefined), { wrapper });
      expect(result.current.isFetching).toBe(false);
      expect(mockApi.get).not.toHaveBeenCalled();
    });
  });

  describe("useCreateMilestone", () => {
    it("POSTs to /milestones and invalidates the milestones cache", async () => {
      mockApi.post.mockResolvedValueOnce(makeMilestone());
      const { wrapper, queryClient } = createTestHookWrapper();
      const invalidateSpy = vi.spyOn(queryClient, "invalidateQueries");

      const { result } = renderHook(() => useCreateMilestone(), { wrapper });

      const body = { name: "Q3 Launch", description: null, softwareProjectId: "r1", targetDate: null };
      result.current.mutate(body);

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(mockApi.post).toHaveBeenCalledWith("/milestones", body);
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ["milestones"] });
    });
  });

  describe("useUpdateMilestone", () => {
    it("PUTs to /milestones/{id} and invalidates milestones and epics", async () => {
      mockApi.put.mockResolvedValueOnce(makeMilestone({ name: "Renamed" }));
      const { wrapper, queryClient } = createTestHookWrapper();
      const invalidateSpy = vi.spyOn(queryClient, "invalidateQueries");

      const { result } = renderHook(() => useUpdateMilestone(), { wrapper });

      const body = { name: "Renamed", description: null, targetDate: null };
      result.current.mutate({ id: "m1", body });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(mockApi.put).toHaveBeenCalledWith("/milestones/m1", body);
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ["milestones"] });
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ["epics"] });
    });
  });

  describe("useDeleteMilestone", () => {
    it("DELETEs /milestones/{id} and invalidates milestones and epics", async () => {
      mockApi.delete.mockResolvedValueOnce(undefined);
      const { wrapper, queryClient } = createTestHookWrapper();
      const invalidateSpy = vi.spyOn(queryClient, "invalidateQueries");

      const { result } = renderHook(() => useDeleteMilestone(), { wrapper });

      result.current.mutate("m1");

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(mockApi.delete).toHaveBeenCalledWith("/milestones/m1");
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ["milestones"] });
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ["epics"] });
    });
  });

  describe("useAssignEpicMilestone", () => {
    it("PATCHes /epics/{id}/milestone and invalidates epics and milestones", async () => {
      mockApi.patch.mockResolvedValueOnce(makeEpic({ milestone: { id: "m1", name: "Q3 Launch" } }));
      const { wrapper, queryClient } = createTestHookWrapper();
      const invalidateSpy = vi.spyOn(queryClient, "invalidateQueries");

      const { result } = renderHook(() => useAssignEpicMilestone(), { wrapper });

      result.current.mutate({ id: "epic-1", milestoneId: "m1" });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(mockApi.patch).toHaveBeenCalledWith("/epics/epic-1/milestone", { milestoneId: "m1" });
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ["epics"] });
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ["milestones"] });
    });

    it("PATCHes a null milestoneId to clear the assignment", async () => {
      mockApi.patch.mockResolvedValueOnce(makeEpic({ milestone: null }));
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(() => useAssignEpicMilestone(), { wrapper });

      result.current.mutate({ id: "epic-1", milestoneId: null });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(mockApi.patch).toHaveBeenCalledWith("/epics/epic-1/milestone", { milestoneId: null });
    });
  });
});
