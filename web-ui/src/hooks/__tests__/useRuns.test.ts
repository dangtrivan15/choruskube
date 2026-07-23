import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { createTestHookWrapper } from "@/__tests__/test-utils";

// Mock the api module
vi.mock("@/lib/api", () => ({
  api: {
    get: vi.fn(),
    getPage: vi.fn(),
    getText: vi.fn(),
    post: vi.fn(),
    postForm: vi.fn(),
    put: vi.fn(),
    patch: vi.fn(),
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

vi.mock("@/lib/toast-messages", () => ({
  showMutationToast: vi.fn((message: string, variant: string) => ({
    id: "mock-toast-id",
    timestamp: Date.now(),
    message,
    variant,
  })),
}));

import { api } from "@/lib/api";
import { showMutationToast } from "@/lib/toast-messages";
import {
  useRuns,
  useRun,
  useNodeLogs,
  useReviewHistory,
  useStartRun,
  usePauseRun,
  useResumeRun,
  useCancelRun,
  useSignalNode,
  useRenameRun,
} from "@/hooks/useRuns";

const mockApi = api as unknown as {
  get: ReturnType<typeof vi.fn>;
  getPage: ReturnType<typeof vi.fn>;
  post: ReturnType<typeof vi.fn>;
  postForm: ReturnType<typeof vi.fn>;
  put: ReturnType<typeof vi.fn>;
  patch: ReturnType<typeof vi.fn>;
};

describe("useRuns hooks", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  describe("useRuns", () => {
    it("fetches all runs without status filter", async () => {
      const page = { content: [{ id: "r1", status: "running" }], totalElements: 1, totalPages: 1, number: 0 };
      mockApi.getPage.mockResolvedValueOnce(page);
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(() => useRuns(), { wrapper });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(result.current.data).toEqual(page);
      expect(mockApi.getPage).toHaveBeenCalledWith("/runs", undefined);
    });

    it("fetches runs filtered by status", async () => {
      const page = { content: [{ id: "r1", status: "completed" }], totalElements: 1, totalPages: 1, number: 0 };
      mockApi.getPage.mockResolvedValueOnce(page);
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(() => useRuns("completed"), { wrapper });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(mockApi.getPage).toHaveBeenCalledWith("/runs?status=completed", undefined);
    });

    it("handles fetch error", async () => {
      mockApi.getPage.mockRejectedValueOnce(new Error("Network error"));
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(() => useRuns(), { wrapper });

      await waitFor(() => expect(result.current.isError).toBe(true));
      expect(result.current.error?.message).toBe("Network error");
    });
  });

  describe("useRun", () => {
    it("fetches a single run by id", async () => {
      const run = { id: "run-abc", status: "running", nodeExecutions: [] };
      mockApi.get.mockResolvedValueOnce(run);
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(() => useRun("run-abc"), { wrapper });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(result.current.data).toEqual(run);
      expect(mockApi.get).toHaveBeenCalledWith("/runs/run-abc");
    });
  });

  describe("useNodeLogs", () => {
    it("fetches logs when enabled and nodeExecId provided", async () => {
      const logs = [{ id: "l1", level: "info", message: "hello", timestamp: "2026-01-01T00:00:00Z" }];
      mockApi.get.mockResolvedValueOnce(logs);
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(
        () => useNodeLogs("run-1", "exec-1", true),
        { wrapper }
      );

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(result.current.data).toEqual(logs);
      expect(mockApi.get).toHaveBeenCalledWith("/runs/run-1/nodes/exec-1/logs");
    });

    it("does not fetch when nodeExecId is null", () => {
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(
        () => useNodeLogs("run-1", null, true),
        { wrapper }
      );

      expect(result.current.fetchStatus).toBe("idle");
      expect(mockApi.get).not.toHaveBeenCalled();
    });

    it("does not fetch when enabled is false", () => {
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(
        () => useNodeLogs("run-1", "exec-1", false),
        { wrapper }
      );

      expect(result.current.fetchStatus).toBe("idle");
    });
  });

  describe("useReviewHistory", () => {
    it("fetches review history for a loop group", async () => {
      const history = [{ id: "rh1", loopGroup: "lg1", iteration: 1, decision: "approved" }];
      mockApi.get.mockResolvedValueOnce(history);
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(
        () => useReviewHistory("run-1", "lg1"),
        { wrapper }
      );

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(mockApi.get).toHaveBeenCalledWith("/runs/run-1/review-history?loopGroup=lg1");
    });

    it("does not fetch when loopGroup is null", () => {
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(
        () => useReviewHistory("run-1", null),
        { wrapper }
      );

      expect(result.current.fetchStatus).toBe("idle");
    });
  });

  describe("useStartRun", () => {
    it("calls api.post with template id and inputs", async () => {
      const newRun = { id: "run-new" };
      mockApi.post.mockResolvedValueOnce(newRun);
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(() => useStartRun(), { wrapper });

      result.current.mutate({
        graphTemplateId: "tpl-1",
        inputs: { name: "test" },
      });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(mockApi.post).toHaveBeenCalledWith("/runs", {
        graphTemplateId: "tpl-1",
        inputs: { name: "test" },
        name: undefined,
        inputAttachmentRefs: undefined,
      });
    });

    it("calls api.post with name when provided", async () => {
      const newRun = { id: "run-new" };
      mockApi.post.mockResolvedValueOnce(newRun);
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(() => useStartRun(), { wrapper });

      result.current.mutate({
        graphTemplateId: "tpl-1",
        inputs: { prompt: "hello" },
        name: "My custom run",
      });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(mockApi.post).toHaveBeenCalledWith("/runs", {
        graphTemplateId: "tpl-1",
        inputs: { prompt: "hello" },
        name: "My custom run",
        inputAttachmentRefs: undefined,
      });
    });

    it("calls api.postForm first then api.post with stagingRefs when inputFiles provided", async () => {
      const stagingRefs = '{"doc.txt":"org/staging/uuid/doc.txt"}';
      mockApi.postForm.mockResolvedValueOnce({ stagingRefs });
      const newRun = { id: "run-new" };
      mockApi.post.mockResolvedValueOnce(newRun);
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(() => useStartRun(), { wrapper });

      const file = new File(["content"], "doc.txt", { type: "text/plain" });
      result.current.mutate({
        graphTemplateId: "tpl-1",
        inputs: {},
        inputFiles: [file],
      });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));

      expect(mockApi.postForm).toHaveBeenCalledOnce();
      const [postFormPath, formData] = mockApi.postForm.mock.calls[0];
      expect(postFormPath).toBe("/attachments/temp");
      expect(formData).toBeInstanceOf(FormData);

      expect(mockApi.post).toHaveBeenCalledWith("/runs", {
        graphTemplateId: "tpl-1",
        inputs: {},
        name: undefined,
        inputAttachmentRefs: stagingRefs,
      });
    });

    it("does not call api.postForm when no inputFiles provided", async () => {
      const newRun = { id: "run-new" };
      mockApi.post.mockResolvedValueOnce(newRun);
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(() => useStartRun(), { wrapper });

      result.current.mutate({
        graphTemplateId: "tpl-1",
        inputs: {},
      });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(mockApi.postForm).not.toHaveBeenCalled();
    });

    it("propagates api.postForm error when file upload fails", async () => {
      mockApi.postForm.mockRejectedValueOnce(new Error("Upload failed"));
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(() => useStartRun(), { wrapper });

      const file = new File(["content"], "doc.txt", { type: "text/plain" });
      result.current.mutate({
        graphTemplateId: "tpl-1",
        inputs: {},
        inputFiles: [file],
      });

      await waitFor(() => expect(result.current.isError).toBe(true));
      expect(result.current.error?.message).toBe("Upload failed");
      expect(mockApi.post).not.toHaveBeenCalled();
    });
  });

  describe("useRenameRun", () => {
    it("calls api.patch with the new name", async () => {
      mockApi.patch.mockResolvedValueOnce(undefined);
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(() => useRenameRun("run-1"), { wrapper });

      result.current.mutate("New Name");

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(mockApi.patch).toHaveBeenCalledWith("/runs/run-1/name", { name: "New Name" });
    });
  });

  describe("usePauseRun", () => {
    it("calls api.post to pause the run", async () => {
      mockApi.post.mockResolvedValueOnce(undefined);
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(() => usePauseRun("run-1"), { wrapper });

      result.current.mutate();

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(mockApi.post).toHaveBeenCalledWith("/runs/run-1/pause");
    });
  });

  describe("useResumeRun", () => {
    it("calls api.post to resume the run", async () => {
      mockApi.post.mockResolvedValueOnce(undefined);
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(() => useResumeRun("run-1"), { wrapper });

      result.current.mutate();

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(mockApi.post).toHaveBeenCalledWith("/runs/run-1/resume");
    });
  });

  describe("useCancelRun", () => {
    it("calls api.post to cancel the run", async () => {
      mockApi.post.mockResolvedValueOnce(undefined);
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(() => useCancelRun("run-1"), { wrapper });

      result.current.mutate();

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(mockApi.post).toHaveBeenCalledWith("/runs/run-1/cancel");
    });
  });

  describe("useSignalNode", () => {
    it("calls api.post with decision and feedback", async () => {
      mockApi.post.mockResolvedValueOnce(undefined);
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(() => useSignalNode("run-1"), { wrapper });

      result.current.mutate({
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

    it("invalidates both runs and pending-gates queries on success", async () => {
      mockApi.post.mockResolvedValueOnce(undefined);
      const { wrapper, queryClient } = createTestHookWrapper();
      const invalidateSpy = vi.spyOn(queryClient, "invalidateQueries");

      const { result } = renderHook(() => useSignalNode("run-1"), { wrapper });

      result.current.mutate({
        nodeExecId: "exec-1",
        decision: "approved",
        feedback: "looks good",
      });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ["runs", "run-1"] });
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ["pending-gates"] });
    });

    it("calls api.postForm first then api.post with attachmentRefs when files provided", async () => {
      const attachmentRefs = '{"evidence.png":"org/runs/uuid/gate-attachments/exec-1/evidence.png"}';
      mockApi.postForm.mockResolvedValueOnce({ attachmentRefs });
      mockApi.post.mockResolvedValueOnce(undefined);
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(() => useSignalNode("run-1"), { wrapper });

      const file = new File(["img"], "evidence.png", { type: "image/png" });
      result.current.mutate({
        nodeExecId: "exec-1",
        decision: "approved",
        feedback: "see attachment",
        files: [file],
      });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));

      expect(mockApi.postForm).toHaveBeenCalledOnce();
      const [postFormPath, formData] = mockApi.postForm.mock.calls[0];
      expect(postFormPath).toBe("/runs/run-1/nodes/exec-1/attachments");
      expect(formData).toBeInstanceOf(FormData);

      expect(mockApi.post).toHaveBeenCalledWith("/runs/run-1/nodes/exec-1/signal", {
        decision: "approved",
        feedback: "see attachment",
        attachmentRefs,
      });
    });

    it("does not call api.postForm when no files provided", async () => {
      mockApi.post.mockResolvedValueOnce(undefined);
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(() => useSignalNode("run-1"), { wrapper });

      result.current.mutate({
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

      const { result } = renderHook(() => useSignalNode("run-1"), { wrapper });

      const file = new File(["img"], "evidence.png", { type: "image/png" });
      result.current.mutate({
        nodeExecId: "exec-1",
        decision: "approved",
        feedback: "see attachment",
        files: [file],
      });

      await waitFor(() => expect(result.current.isError).toBe(true));
      expect(result.current.error?.message).toBe("Upload failed");
      expect(mockApi.post).not.toHaveBeenCalled();
    });

    it("includes editedCandidates in the request body when provided", async () => {
      mockApi.post.mockResolvedValueOnce(undefined);
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(() => useSignalNode("run-1"), { wrapper });

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

      const { result } = renderHook(() => useSignalNode("run-1"), { wrapper });

      result.current.mutate({
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
      const body = mockApi.post.mock.calls[0][1] as Record<string, unknown>;
      expect(Object.prototype.hasOwnProperty.call(body, "editedCandidates")).toBe(false);
    });
  });

  describe("useStartRun error handling", () => {
    it("shows 429 warning toast with backend message on quota exceeded", async () => {
      const { wrapper } = createTestHookWrapper();

      // Import ApiError from the mocked module
      const { ApiError: MockApiError } = await import("@/lib/api");
      mockApi.post.mockRejectedValueOnce(
        new MockApiError(429, { message: "Quota limit reached for this organization." }),
      );

      const { result } = renderHook(() => useStartRun(), { wrapper });

      result.current.mutate({ graphTemplateId: "tpl-1", inputs: {} });

      await waitFor(() => expect(result.current.isError).toBe(true));
      expect(showMutationToast).toHaveBeenCalledWith(
        "Quota limit reached for this organization.",
        "warning",
      );
    });

    it("shows warning toast with response body on 400 plain-string error (credential invalid)", async () => {
      const { wrapper } = createTestHookWrapper();

      const { ApiError: MockApiError } = await import("@/lib/api");
      mockApi.post.mockRejectedValueOnce(
        new MockApiError(400, "GitHub credential is expired or invalid."),
      );

      const { result } = renderHook(() => useStartRun(), { wrapper });

      result.current.mutate({ graphTemplateId: "tpl-1", inputs: {} });

      await waitFor(() => expect(result.current.isError).toBe(true));
      expect(showMutationToast).toHaveBeenCalledWith(
        "GitHub credential is expired or invalid.",
        "warning",
      );
    });

    it("does not show body for 400 JSON errors — shows generic failure message", async () => {
      const { wrapper } = createTestHookWrapper();

      const { ApiError: MockApiError } = await import("@/lib/api");
      mockApi.post.mockRejectedValueOnce(
        new MockApiError(400, { message: "Invalid input", field: "graphTemplateId" }),
      );

      const { result } = renderHook(() => useStartRun(), { wrapper });

      result.current.mutate({ graphTemplateId: "tpl-1", inputs: {} });

      await waitFor(() => expect(result.current.isError).toBe(true));
      expect(showMutationToast).toHaveBeenCalledWith("Failed to start run", "error");
    });

    it("shows generic error toast for non-400/429 errors", async () => {
      const { wrapper } = createTestHookWrapper();

      mockApi.post.mockRejectedValueOnce(new Error("Network failure"));

      const { result } = renderHook(() => useStartRun(), { wrapper });

      result.current.mutate({ graphTemplateId: "tpl-1", inputs: {} });

      await waitFor(() => expect(result.current.isError).toBe(true));
      expect(showMutationToast).toHaveBeenCalledWith("Failed to start run", "error");
    });
  });
});
