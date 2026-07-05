import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { api, ApiError, artifactUrl, encodeArtifactPath } from "@/lib/api";
import { getImpersonation } from "@/lib/impersonation";

vi.mock("@/lib/impersonation", () => ({ getImpersonation: vi.fn() }));

describe("api", () => {
  const originalFetch = globalThis.fetch;

  beforeEach(() => {
    globalThis.fetch = vi.fn();
    vi.mocked(getImpersonation).mockReturnValue(null);
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
    vi.clearAllMocks();
  });

  function mockFetch(status: number, body: unknown, ok?: boolean) {
    (globalThis.fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
      ok: ok ?? (status >= 200 && status < 300),
      status,
      json: () => Promise.resolve(body),
      text: () => Promise.resolve(typeof body === "string" ? body : JSON.stringify(body)),
    });
  }

  describe("impersonation header", () => {
    it("does not send X-Impersonate-Org-Id when no impersonation is active", async () => {
      vi.mocked(getImpersonation).mockReturnValue(null);
      mockFetch(200, {});
      await api.get("/runs");
      const call = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
      expect(call[1].headers).not.toHaveProperty("X-Impersonate-Org-Id");
    });

    it("sends X-Impersonate-Org-Id on every request while impersonating", async () => {
      vi.mocked(getImpersonation).mockReturnValue({ orgId: "target-org-123", orgSlug: "acme" });
      mockFetch(200, {});
      mockFetch(200, {});

      await api.get("/runs");
      await api.post("/runs", { graphTemplateId: "x" });

      const calls = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls;
      expect(calls[0][1].headers["X-Impersonate-Org-Id"]).toBe("target-org-123");
      expect(calls[1][1].headers["X-Impersonate-Org-Id"]).toBe("target-org-123");
    });
  });

  describe("ApiError", () => {
    it("stores status and body", () => {
      const err = new ApiError(404, { message: "not found" });
      expect(err).toBeInstanceOf(Error);
      expect(err.status).toBe(404);
      expect(err.body).toEqual({ message: "not found" });
      expect(err.message).toBe("API error 404");
    });
  });

  describe("api.get", () => {
    it("sends GET request and returns parsed JSON", async () => {
      const data = [{ id: "1", name: "test" }];
      mockFetch(200, data);

      const result = await api.get<typeof data>("/runs");

      expect(globalThis.fetch).toHaveBeenCalledWith("/api/v1/runs", {
        method: "GET",
        headers: {},
        body: undefined,
      });
      expect(result).toEqual(data);
    });

    it("throws ApiError on non-ok response", async () => {
      const errorBody = { error: "forbidden" };
      mockFetch(403, errorBody, false);

      await expect(api.get("/runs")).rejects.toThrow(ApiError);
      await expect(api.get("/runs")).rejects.toBeDefined();
    });

    it("surfaces plain-text error bodies as a string (e.g. Spring's Conflict/BadRequest)", async () => {
      // GlobalExceptionHandler on the backend returns text/plain bodies for Conflict,
      // BadRequest, NotFound, and Forbidden. The client must expose that string so dialogs
      // can render the actionable reason, not swallow it to null.
      (globalThis.fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
        ok: false,
        status: 409,
        text: () => Promise.resolve("Organization has running agent jobs"),
      });

      try {
        await api.get("/runs");
        expect.fail("should have thrown");
      } catch (e) {
        expect(e).toBeInstanceOf(ApiError);
        expect((e as ApiError).status).toBe(409);
        expect((e as ApiError).body).toBe("Organization has running agent jobs");
      }
    });

    it("parses JSON error bodies into objects (e.g. QuotaExceededResponse)", async () => {
      (globalThis.fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
        ok: false,
        status: 429,
        text: () =>
          Promise.resolve(JSON.stringify({ error: "quota_exceeded", message: "limit reached" })),
      });

      try {
        await api.get("/runs");
        expect.fail("should have thrown");
      } catch (e) {
        expect((e as ApiError).body).toEqual({ error: "quota_exceeded", message: "limit reached" });
      }
    });

    it("throws ApiError with null body when the error response has no body", async () => {
      (globalThis.fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
        ok: false,
        status: 500,
        text: () => Promise.resolve(""),
      });

      try {
        await api.get("/runs");
        expect.fail("should have thrown");
      } catch (e) {
        expect((e as ApiError).body).toBeNull();
      }
    });
  });

  describe("api.getText", () => {
    it("sends GET request and returns text", async () => {
      mockFetch(200, "plain text content");

      const result = await api.getText("/artifacts/file.txt");

      expect(globalThis.fetch).toHaveBeenCalledWith("/api/v1/artifacts/file.txt", {
        method: "GET",
        headers: {},
      });
      expect(result).toBe("plain text content");
    });

    it("throws ApiError on failure", async () => {
      (globalThis.fetch as ReturnType<typeof vi.fn>).mockResolvedValueOnce({
        ok: false,
        status: 404,
        json: () => Promise.resolve({ error: "not found" }),
      });

      try {
        await api.getText("/artifacts/missing.txt");
        expect.fail("should have thrown");
      } catch (e) {
        expect(e).toBeInstanceOf(ApiError);
        expect((e as ApiError).status).toBe(404);
      }
    });
  });

  describe("api.post", () => {
    it("sends POST request with JSON body", async () => {
      const responseData = { id: "run-1" };
      mockFetch(200, responseData);

      const result = await api.post("/runs", { graphTemplateId: "tpl-1" });

      expect(globalThis.fetch).toHaveBeenCalledWith("/api/v1/runs", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ graphTemplateId: "tpl-1" }),
      });
      expect(result).toEqual(responseData);
    });

    it("sends POST request without body", async () => {
      mockFetch(204, undefined);

      const result = await api.post("/runs/123/pause");

      expect(globalThis.fetch).toHaveBeenCalledWith("/api/v1/runs/123/pause", {
        method: "POST",
        headers: {},
        body: undefined,
      });
      expect(result).toBeUndefined();
    });
  });

  describe("api.put", () => {
    it("sends PUT request with JSON body", async () => {
      const updated = { id: "tpl-1", name: "Updated" };
      mockFetch(200, updated);

      const result = await api.put("/graph-templates/tpl-1", { name: "Updated" });

      expect(globalThis.fetch).toHaveBeenCalledWith("/api/v1/graph-templates/tpl-1", {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ name: "Updated" }),
      });
      expect(result).toEqual(updated);
    });
  });

  describe("api.delete", () => {
    it("sends DELETE request", async () => {
      mockFetch(204, undefined);

      const result = await api.delete("/runs/123");

      expect(globalThis.fetch).toHaveBeenCalledWith("/api/v1/runs/123", {
        method: "DELETE",
        headers: {},
        body: undefined,
      });
      expect(result).toBeUndefined();
    });
  });

  describe("204 No Content handling", () => {
    it("returns undefined for 204 responses", async () => {
      mockFetch(204, undefined);

      const result = await api.post("/runs/abc/cancel");
      expect(result).toBeUndefined();
    });
  });

  describe("encodeArtifactPath", () => {
    it("preserves '/' as a path separator", () => {
      expect(encodeArtifactPath("playwright-report/index.html"))
        .toBe("playwright-report/index.html");
    });

    it("percent-encodes special characters within each segment", () => {
      expect(encodeArtifactPath("test results/run #1/file with space.txt"))
        .toBe("test%20results/run%20%231/file%20with%20space.txt");
    });

    it("handles a flat filename", () => {
      expect(encodeArtifactPath("notes.md")).toBe("notes.md");
    });
  });

  describe("artifactUrl", () => {
    it("encodes nested paths with '/' preserved", () => {
      const url = artifactUrl("run-1", "exec-1", "playwright-report/trace/index.html");
      expect(url).toContain("/artifacts/playwright-report/trace/index.html");
    });
  });
});
