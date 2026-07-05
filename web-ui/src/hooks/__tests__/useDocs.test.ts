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

import { api } from "@/lib/api";
import { useDocsList, useDocsPage } from "@/hooks/useDocs";

const mockApi = api as unknown as {
  get: ReturnType<typeof vi.fn>;
};

describe("useDocs hooks", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  describe("useDocsList", () => {
    it("fetches the docs list", async () => {
      const docs = [
        { slug: "getting-started", title: "Getting Started", order: 1, description: "Step-by-step guide." },
        { slug: "features", title: "Features", order: 2, description: "Feature overview." },
      ];
      mockApi.get.mockResolvedValueOnce(docs);
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(() => useDocsList(), { wrapper });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(result.current.data).toEqual(docs);
      expect(mockApi.get).toHaveBeenCalledWith("/docs");
    });

    it("handles fetch error", async () => {
      mockApi.get.mockRejectedValueOnce(new Error("Network error"));
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(() => useDocsList(), { wrapper });

      await waitFor(() => expect(result.current.isError).toBe(true));
    });
  });

  describe("useDocsPage", () => {
    it("fetches a specific docs page", async () => {
      const page = { slug: "getting-started", title: "Getting Started", content: "## Overview\n\nWelcome." };
      mockApi.get.mockResolvedValueOnce(page);
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(() => useDocsPage("getting-started"), { wrapper });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(result.current.data).toEqual(page);
      expect(mockApi.get).toHaveBeenCalledWith("/docs/getting-started");
    });

    it("is disabled when slug is empty", async () => {
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(() => useDocsPage(""), { wrapper });

      // Query should not run when slug is empty
      expect(result.current.fetchStatus).toBe("idle");
      expect(mockApi.get).not.toHaveBeenCalled();
    });

    it("handles fetch error", async () => {
      mockApi.get.mockRejectedValueOnce(new Error("Network error"));
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(() => useDocsPage("getting-started"), { wrapper });

      await waitFor(() => expect(result.current.isError).toBe(true));
    });
  });
});
