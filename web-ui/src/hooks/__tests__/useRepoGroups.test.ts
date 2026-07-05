import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { createTestHookWrapper } from "@/__tests__/test-utils";

vi.mock("@/lib/api", () => ({
  api: {
    get: vi.fn(),
    getPage: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
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
  useRepoGroups,
  useRepoGroup,
  useCreateRepoGroup,
  useUpdateRepoGroup,
  useReplaceRepoGroupMembers,
  useDeleteRepoGroup,
} from "@/hooks/useRepoGroups";
import type { RepoGroup } from "@/lib/types";

const mockApi = api as unknown as {
  get: ReturnType<typeof vi.fn>;
  post: ReturnType<typeof vi.fn>;
  put: ReturnType<typeof vi.fn>;
  delete: ReturnType<typeof vi.fn>;
};

const sampleGroup: RepoGroup = {
  id: "g1",
  name: "group-one",
  agentImage: null,
  description: null,
  runtimeRequirements: { agentImage: null, enableDocker: false },
  members: [{ gitRepoId: "r1", name: "repo-one", position: 0 }],
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
};

describe("useRepoGroups hooks", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  describe("useRepoGroups", () => {
    it("fetches the flat repo-group list", async () => {
      mockApi.get.mockResolvedValueOnce([sampleGroup]);
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(() => useRepoGroups(), { wrapper });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(result.current.data).toEqual([sampleGroup]);
      expect(mockApi.get).toHaveBeenCalledWith("/repo-groups");
    });

    it("surfaces fetch errors", async () => {
      mockApi.get.mockRejectedValueOnce(new Error("server error"));
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(() => useRepoGroups(), { wrapper });

      await waitFor(() => expect(result.current.isError).toBe(true));
    });
  });

  describe("useRepoGroup", () => {
    it("fetches a single repo group by id", async () => {
      mockApi.get.mockResolvedValueOnce(sampleGroup);
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(() => useRepoGroup("g1"), { wrapper });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(result.current.data).toEqual(sampleGroup);
      expect(mockApi.get).toHaveBeenCalledWith("/repo-groups/g1");
    });

    it("does not fetch when id is undefined", () => {
      const { wrapper } = createTestHookWrapper();
      const { result } = renderHook(() => useRepoGroup(undefined), { wrapper });
      expect(result.current.isFetching).toBe(false);
      expect(mockApi.get).not.toHaveBeenCalled();
    });
  });

  describe("useCreateRepoGroup", () => {
    it("POSTs to /repo-groups and invalidates the list cache", async () => {
      mockApi.post.mockResolvedValueOnce(sampleGroup);
      const { wrapper, queryClient } = createTestHookWrapper();
      const invalidateSpy = vi.spyOn(queryClient, "invalidateQueries");

      const { result } = renderHook(() => useCreateRepoGroup(), { wrapper });

      const body = {
        name: "group-one",
        agentImage: null,
        description: null,
        memberRepoIds: ["r1"],
      };
      result.current.mutate(body);

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(mockApi.post).toHaveBeenCalledWith("/repo-groups", body);
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ["repo-groups"] });
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ["software-projects"] });
    });

    it("surfaces error state when create fails", async () => {
      mockApi.post.mockRejectedValueOnce(new Error("conflict"));
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(() => useCreateRepoGroup(), { wrapper });

      result.current.mutate({ name: "dup", memberRepoIds: ["r1"] });

      await waitFor(() => expect(result.current.isError).toBe(true));
    });
  });

  describe("useUpdateRepoGroup", () => {
    it("PUTs to /repo-groups/{id} and invalidates the list cache", async () => {
      mockApi.put.mockResolvedValueOnce(sampleGroup);
      const { wrapper, queryClient } = createTestHookWrapper();
      const invalidateSpy = vi.spyOn(queryClient, "invalidateQueries");

      const { result } = renderHook(() => useUpdateRepoGroup(), { wrapper });

      const body = {
        name: "renamed",
        agentImage: null,
        description: null,
        memberRepoIds: ["r1"],
      };
      result.current.mutate({ id: "g1", body });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(mockApi.put).toHaveBeenCalledWith("/repo-groups/g1", body);
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ["repo-groups"] });
    });
  });

  describe("useReplaceRepoGroupMembers", () => {
    it("PUTs to /repo-groups/{id}/members and invalidates the list cache", async () => {
      mockApi.put.mockResolvedValueOnce(sampleGroup);
      const { wrapper, queryClient } = createTestHookWrapper();
      const invalidateSpy = vi.spyOn(queryClient, "invalidateQueries");

      const { result } = renderHook(() => useReplaceRepoGroupMembers(), { wrapper });

      result.current.mutate({ id: "g1", memberRepoIds: ["r1", "r2"] });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(mockApi.put).toHaveBeenCalledWith("/repo-groups/g1/members", {
        memberRepoIds: ["r1", "r2"],
      });
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ["repo-groups"] });
    });
  });

  describe("useDeleteRepoGroup", () => {
    it("DELETEs /repo-groups/{id} and invalidates the list cache", async () => {
      mockApi.delete.mockResolvedValueOnce(undefined);
      const { wrapper, queryClient } = createTestHookWrapper();
      const invalidateSpy = vi.spyOn(queryClient, "invalidateQueries");

      const { result } = renderHook(() => useDeleteRepoGroup(), { wrapper });

      result.current.mutate("g1");

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(mockApi.delete).toHaveBeenCalledWith("/repo-groups/g1");
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ["repo-groups"] });
    });

    it("surfaces error state when delete fails", async () => {
      mockApi.delete.mockRejectedValueOnce(new Error("conflict"));
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(() => useDeleteRepoGroup(), { wrapper });

      result.current.mutate("g1");

      await waitFor(() => expect(result.current.isError).toBe(true));
    });
  });
});
