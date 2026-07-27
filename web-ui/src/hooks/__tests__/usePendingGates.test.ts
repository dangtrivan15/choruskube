import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { createTestHookWrapper } from "@/__tests__/test-utils";

vi.mock("@/lib/api", () => ({
  api: {
    get: vi.fn(),
    getPage: vi.fn(),
    getText: vi.fn(),
    post: vi.fn(),
    postForm: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
  ApiError: class ApiError extends Error {
    status: number;
    body: unknown;
    constructor(status: number, body: unknown) {
      super(`API error ${status}`);
      this.status = status;
      this.body = body;
    }
  },
}));

import { api } from "@/lib/api";
import {
  usePendingGates,
  usePendingGatesCount,
  useSignalFromDashboard,
} from "@/hooks/usePendingGates";

const mockApi = api as unknown as {
  get: ReturnType<typeof vi.fn>;
  getPage: ReturnType<typeof vi.fn>;
  post: ReturnType<typeof vi.fn>;
  postForm: ReturnType<typeof vi.fn>;
};

describe("usePendingGates hooks", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  describe("usePendingGates", () => {
    it("fetches pending gates", async () => {
      const page = {
        content: [
          {
            nodeExecutionId: "exec-1",
            runId: "run-1",
            runStatus: "awaiting_human",
            status: "awaiting_human",
            runName: "Test Workflow",
            nodeLabel: "Review Gate",
            iteration: 1,
            timeoutSeconds: 3600,
            waitingSince: "2026-03-29T10:00:00Z",
            predecessorOutputs: [],
          },
        ],
        totalElements: 1,
        totalPages: 1,
        number: 0,
      };
      mockApi.getPage.mockResolvedValueOnce(page);
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(() => usePendingGates(), { wrapper });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(result.current.data).toEqual(page);
      expect(mockApi.getPage).toHaveBeenCalledWith("/pending-gates", undefined);
    });

    it("handles empty list", async () => {
      const page = { content: [], totalElements: 0, totalPages: 0, number: 0 };
      mockApi.getPage.mockResolvedValueOnce(page);
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(() => usePendingGates(), { wrapper });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(result.current.data?.content).toEqual([]);
    });

    it("handles fetch error", async () => {
      mockApi.getPage.mockRejectedValueOnce(new Error("Network error"));
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(() => usePendingGates(), { wrapper });

      await waitFor(() => expect(result.current.isError).toBe(true));
      expect(result.current.error?.message).toBe("Network error");
    });
  });

  describe("usePendingGatesCount", () => {
    it("fetches pending gate count", async () => {
      mockApi.get.mockResolvedValueOnce({ count: 3 });
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(() => usePendingGatesCount(), { wrapper });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(result.current.data).toEqual({ count: 3 });
      expect(mockApi.get).toHaveBeenCalledWith("/pending-gates/count");
    });

    it("handles zero count", async () => {
      mockApi.get.mockResolvedValueOnce({ count: 0 });
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(() => usePendingGatesCount(), { wrapper });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(result.current.data?.count).toBe(0);
    });
  });

  describe("useSignalFromDashboard", () => {
    it("calls api.post with correct parameters", async () => {
      mockApi.post.mockResolvedValueOnce(undefined);
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(() => useSignalFromDashboard(), { wrapper });

      result.current.mutate({
        runId: "run-1",
        nodeExecId: "exec-1",
        decision: "approved",
        feedback: "looks good",
      });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(mockApi.post).toHaveBeenCalledWith("/runs/run-1/nodes/exec-1/signal", {
        decision: "approved",
        feedback: "looks good",
        attachmentRefs: undefined,
      });
    });

    it("calls api.post for rejection with feedback", async () => {
      mockApi.post.mockResolvedValueOnce(undefined);
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(() => useSignalFromDashboard(), { wrapper });

      result.current.mutate({
        runId: "run-2",
        nodeExecId: "exec-2",
        decision: "rejected",
        feedback: "needs changes",
      });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(mockApi.post).toHaveBeenCalledWith("/runs/run-2/nodes/exec-2/signal", {
        decision: "rejected",
        feedback: "needs changes",
        attachmentRefs: undefined,
      });
    });

    it("calls api.postForm first then api.post with attachmentRefs when files provided", async () => {
      const attachmentRefs = '{"report.pdf":"org/runs/uuid/gate-attachments/exec-1/report.pdf"}';
      mockApi.postForm.mockResolvedValueOnce({ attachmentRefs });
      mockApi.post.mockResolvedValueOnce(undefined);
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(() => useSignalFromDashboard(), { wrapper });

      const file = new File(["data"], "report.pdf", { type: "application/pdf" });
      result.current.mutate({
        runId: "run-1",
        nodeExecId: "exec-1",
        decision: "approved",
        feedback: "see report",
        files: [file],
      });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));

      expect(mockApi.postForm).toHaveBeenCalledOnce();
      const [postFormPath, formData] = mockApi.postForm.mock.calls[0];
      expect(postFormPath).toBe("/runs/run-1/nodes/exec-1/attachments");
      expect(formData).toBeInstanceOf(FormData);

      expect(mockApi.post).toHaveBeenCalledWith("/runs/run-1/nodes/exec-1/signal", {
        decision: "approved",
        feedback: "see report",
        attachmentRefs,
      });
    });

    it("does not call api.postForm when no files provided", async () => {
      mockApi.post.mockResolvedValueOnce(undefined);
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(() => useSignalFromDashboard(), { wrapper });

      result.current.mutate({
        runId: "run-1",
        nodeExecId: "exec-1",
        decision: "approved",
        feedback: "looks good",
      });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(mockApi.postForm).not.toHaveBeenCalled();
    });

    it("propagates api.postForm error when file upload fails", async () => {
      mockApi.postForm.mockRejectedValueOnce(new Error("Upload failed"));
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(() => useSignalFromDashboard(), { wrapper });

      const file = new File(["data"], "report.pdf", { type: "application/pdf" });
      result.current.mutate({
        runId: "run-1",
        nodeExecId: "exec-1",
        decision: "approved",
        feedback: "see report",
        files: [file],
      });

      await waitFor(() => expect(result.current.isError).toBe(true));
      expect(result.current.error?.message).toBe("Upload failed");
      expect(mockApi.post).not.toHaveBeenCalled();
    });

    it("includes editedCandidates in the request body when provided", async () => {
      mockApi.post.mockResolvedValueOnce(undefined);
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(() => useSignalFromDashboard(), { wrapper });

      const editedCandidates = [
        {
          title: "Epic A",
          description: "desc",
          motivation: "why",
          repos: null,
          priority: null,
          stories: [],
        },
      ];
      result.current.mutate({
        runId: "run-1",
        nodeExecId: "exec-1",
        decision: "approved",
        feedback: "looks good",
        editedCandidates,
      });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(mockApi.post).toHaveBeenCalledWith("/runs/run-1/nodes/exec-1/signal", {
        decision: "approved",
        feedback: "looks good",
        attachmentRefs: undefined,
        editedCandidates,
      });
    });

    it("omits editedCandidates from the request body when not provided", async () => {
      mockApi.post.mockResolvedValueOnce(undefined);
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(() => useSignalFromDashboard(), { wrapper });

      result.current.mutate({
        runId: "run-1",
        nodeExecId: "exec-1",
        decision: "approved",
        feedback: "looks good",
      });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      const body = mockApi.post.mock.calls[0][1] as Record<string, unknown>;
      expect(Object.prototype.hasOwnProperty.call(body, "editedCandidates")).toBe(false);
    });
  });
});
