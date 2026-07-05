import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { createTestHookWrapper } from "@/__tests__/test-utils";

vi.mock("@/lib/api", () => ({
  api: {
    get: vi.fn(),
    getText: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
  encodeArtifactPath: (filename: string) =>
    filename.split("/").map(encodeURIComponent).join("/"),
}));

import { api } from "@/lib/api";
import { useArtifacts, useArtifactContent, useArtifactsForGroups } from "@/hooks/useArtifacts";

const mockApi = api as unknown as {
  get: ReturnType<typeof vi.fn>;
  getText: ReturnType<typeof vi.fn>;
};

describe("useArtifacts hooks", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  describe("useArtifacts", () => {
    it("fetches artifacts for a node execution", async () => {
      const artifacts = [
        { name: "output.json", size: 1024, lastModified: "2026-01-01T00:00:00Z" },
      ];
      mockApi.get.mockResolvedValueOnce(artifacts);
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(
        () => useArtifacts("run-1", "exec-1"),
        { wrapper }
      );

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(result.current.data).toEqual(artifacts);
      expect(mockApi.get).toHaveBeenCalledWith(
        "/runs/run-1/node-executions/exec-1/artifacts"
      );
    });

    it("handles fetch error", async () => {
      mockApi.get.mockRejectedValueOnce(new Error("not found"));
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(
        () => useArtifacts("run-1", "exec-1"),
        { wrapper }
      );

      await waitFor(() => expect(result.current.isError).toBe(true));
    });
  });

  describe("useArtifactContent", () => {
    it("fetches artifact content as text", async () => {
      mockApi.getText.mockResolvedValueOnce("file contents here");
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(
        () => useArtifactContent("run-1", "exec-1", "output.txt"),
        { wrapper }
      );

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(result.current.data).toBe("file contents here");
      expect(mockApi.getText).toHaveBeenCalledWith(
        "/runs/run-1/node-executions/exec-1/artifacts/output.txt"
      );
    });

    it("does not fetch when filename is null", () => {
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(
        () => useArtifactContent("run-1", "exec-1", null),
        { wrapper }
      );

      expect(result.current.fetchStatus).toBe("idle");
      expect(mockApi.getText).not.toHaveBeenCalled();
    });

    it("preserves '/' as path separator and encodes special chars within segments", async () => {
      mockApi.getText.mockResolvedValueOnce("content");
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(
        () => useArtifactContent("run-1", "exec-1", "path/to file.txt"),
        { wrapper }
      );

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(mockApi.getText).toHaveBeenCalledWith(
        "/runs/run-1/node-executions/exec-1/artifacts/path/to%20file.txt"
      );
    });

    it("fetches a nested artifact (e.g. playwright report)", async () => {
      mockApi.getText.mockResolvedValueOnce("<html></html>");
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(
        () =>
          useArtifactContent("run-1", "exec-1", "playwright-report/index.html"),
        { wrapper }
      );

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(mockApi.getText).toHaveBeenCalledWith(
        "/runs/run-1/node-executions/exec-1/artifacts/playwright-report/index.html"
      );
    });
  });

  describe("useArtifactsForGroups", () => {
    it("returns results in stable index order for two groups", async () => {
      const artifactsA = [{ name: "fileA.md", size: 100, lastModified: "2026-01-01T00:00:00Z" }];
      const artifactsB = [{ name: "fileB.md", size: 200, lastModified: "2026-01-01T00:00:00Z" }];
      // Use mockImplementation keyed by URL so results are deterministic regardless of call order
      mockApi.get.mockImplementation((url: string) => {
        if (url.includes("exec-a")) return Promise.resolve(artifactsA);
        if (url.includes("exec-b")) return Promise.resolve(artifactsB);
        return Promise.resolve([]);
      });
      const { wrapper } = createTestHookWrapper();
      const groups = [
        { nodeExecutionId: "exec-a", nodeLabel: "Node A", artifacts: [] },
        { nodeExecutionId: "exec-b", nodeLabel: "Node B", artifacts: [] },
      ];

      const { result } = renderHook(
        () => useArtifactsForGroups("run-1", groups),
        { wrapper }
      );

      await waitFor(() => {
        expect(result.current).toHaveLength(2);
        expect(result.current[0].data).toEqual(artifactsA);
        expect(result.current[1].data).toEqual(artifactsB);
      });
      expect(mockApi.get).toHaveBeenCalledWith("/runs/run-1/node-executions/exec-a/artifacts");
      expect(mockApi.get).toHaveBeenCalledWith("/runs/run-1/node-executions/exec-b/artifacts");
    });

    it("returns empty array when groups is empty", () => {
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(
        () => useArtifactsForGroups("run-1", []),
        { wrapper }
      );

      expect(result.current).toEqual([]);
      expect(mockApi.get).not.toHaveBeenCalled();
    });
  });
});
