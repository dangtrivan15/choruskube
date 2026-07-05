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
import { useTemplates } from "@/hooks/useTemplates";

const mockApi = api as unknown as {
  get: ReturnType<typeof vi.fn>;
  getPage: ReturnType<typeof vi.fn>;
};

describe("useTemplates hooks", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  describe("useTemplates", () => {
    it("fetches all templates", async () => {
      const page = { content: [{ id: "t1", name: "Template 1", version: 1 }], totalElements: 1, totalPages: 1, number: 0 };
      mockApi.getPage.mockResolvedValueOnce(page);
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(() => useTemplates(), { wrapper });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(result.current.data).toEqual(page);
      expect(mockApi.getPage).toHaveBeenCalledWith("/graph-templates", undefined);
    });

    it("fetches with latestOnly filter", async () => {
      const page = { content: [{ id: "t1", name: "Template 1", version: 2 }], totalElements: 1, totalPages: 1, number: 0 };
      mockApi.getPage.mockResolvedValueOnce(page);
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(() => useTemplates(true), { wrapper });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));
      expect(mockApi.getPage).toHaveBeenCalledWith("/graph-templates?latestOnly=true", undefined);
    });

    it("handles fetch error", async () => {
      mockApi.getPage.mockRejectedValueOnce(new Error("server error"));
      const { wrapper } = createTestHookWrapper();

      const { result } = renderHook(() => useTemplates(), { wrapper });

      await waitFor(() => expect(result.current.isError).toBe(true));
    });
  });
});
