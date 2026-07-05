import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { createTestHookWrapper } from "@/__tests__/test-utils";

vi.mock("@/lib/api", () => {
  class MockApiError extends Error {
    status: number;
    body: unknown;
    constructor(status: number, body: unknown) {
      super(`API error ${status}`);
      this.status = status;
      this.body = body;
    }
  }
  return {
    api: {
      get: vi.fn(),
      put: vi.fn(),
      post: vi.fn(),
      delete: vi.fn(),
    },
    ApiError: MockApiError,
  };
});

import { api, ApiError } from "@/lib/api";
import {
  useGitHubCredential,
  useSaveGitHubCredential,
  useDeleteGitHubCredential,
  useVerifyGitHubCredential,
  useCheckRepoReachability,
  GITHUB_CREDENTIAL_QUERY_KEY,
} from "@/hooks/useGitHubCredential";

const mockApi = api as unknown as {
  get: ReturnType<typeof vi.fn>;
  put: ReturnType<typeof vi.fn>;
  post: ReturnType<typeof vi.fn>;
  delete: ReturnType<typeof vi.fn>;
};

const ORG_ID = "00000000-0000-0000-0000-000000000001";

const sampleCredential = {
  id: "cred-1",
  organizationId: ORG_ID,
  credentialType: "pat",
  tokenHint: "abcd",
  createdAt: "2026-04-01T00:00:00Z",
  updatedAt: "2026-04-01T00:00:00Z",
};

beforeEach(() => {
  vi.clearAllMocks();
});

describe("GITHUB_CREDENTIAL_QUERY_KEY", () => {
  it("returns the correct query key tuple", () => {
    expect(GITHUB_CREDENTIAL_QUERY_KEY(ORG_ID)).toEqual(["org-github-credential", ORG_ID]);
  });
});

describe("useGitHubCredential", () => {
  it("fetches credential and returns data", async () => {
    mockApi.get.mockResolvedValue(sampleCredential);
    const { wrapper } = createTestHookWrapper();

    const { result } = renderHook(() => useGitHubCredential(ORG_ID), { wrapper });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toEqual(sampleCredential);
    expect(mockApi.get).toHaveBeenCalledWith(`/organizations/${ORG_ID}/github-credential`);
  });

  it("returns null on 404", async () => {
    mockApi.get.mockRejectedValue(new ApiError(404, "Not found"));
    const { wrapper } = createTestHookWrapper();

    const { result } = renderHook(() => useGitHubCredential(ORG_ID), { wrapper });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeNull();
  });

  it("does not fetch when orgId is empty", () => {
    const { wrapper } = createTestHookWrapper();

    const { result } = renderHook(() => useGitHubCredential(""), { wrapper });

    expect(result.current.fetchStatus).toBe("idle");
    expect(mockApi.get).not.toHaveBeenCalled();
  });
});

describe("useSaveGitHubCredential", () => {
  it("calls PUT with correct URL and body", async () => {
    mockApi.put.mockResolvedValue(sampleCredential);
    mockApi.get.mockResolvedValue(sampleCredential);
    const { wrapper } = createTestHookWrapper();

    const { result } = renderHook(() => useSaveGitHubCredential(), { wrapper });

    result.current.mutate({ orgId: ORG_ID, token: "ghp_test" });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(mockApi.put).toHaveBeenCalledWith(
      `/organizations/${ORG_ID}/github-credential`,
      { token: "ghp_test" }
    );
  });

  it("appends ?force=true when force is true", async () => {
    mockApi.put.mockResolvedValue(sampleCredential);
    mockApi.get.mockResolvedValue(sampleCredential);
    const { wrapper } = createTestHookWrapper();

    const { result } = renderHook(() => useSaveGitHubCredential(), { wrapper });

    result.current.mutate({ orgId: ORG_ID, token: "ghp_test", force: true });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(mockApi.put).toHaveBeenCalledWith(
      `/organizations/${ORG_ID}/github-credential?force=true`,
      { token: "ghp_test" }
    );
  });
});

describe("useDeleteGitHubCredential", () => {
  it("calls DELETE with correct URL", async () => {
    mockApi.delete.mockResolvedValue(undefined);
    mockApi.get.mockRejectedValue(new ApiError(404, "Not found"));
    const { wrapper } = createTestHookWrapper();

    const { result } = renderHook(() => useDeleteGitHubCredential(), { wrapper });

    result.current.mutate({ orgId: ORG_ID });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(mockApi.delete).toHaveBeenCalledWith(
      `/organizations/${ORG_ID}/github-credential`
    );
  });

  it("appends ?force=true when force is true", async () => {
    mockApi.delete.mockResolvedValue(undefined);
    mockApi.get.mockRejectedValue(new ApiError(404, "Not found"));
    const { wrapper } = createTestHookWrapper();

    const { result } = renderHook(() => useDeleteGitHubCredential(), { wrapper });

    result.current.mutate({ orgId: ORG_ID, force: true });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(mockApi.delete).toHaveBeenCalledWith(
      `/organizations/${ORG_ID}/github-credential?force=true`
    );
  });
});

describe("useVerifyGitHubCredential", () => {
  it("POSTs to /verify endpoint", async () => {
    const healthResponse = {
      status: "VALID",
      lastCheckedAt: "2026-04-01T00:00:00Z",
      detail: "Token is valid",
      requiredScopes: null,
      remediationUrl: null,
    };
    mockApi.post.mockResolvedValue(healthResponse);
    mockApi.get.mockResolvedValue(sampleCredential);
    const { wrapper } = createTestHookWrapper();

    const { result } = renderHook(() => useVerifyGitHubCredential(), { wrapper });

    result.current.mutate({ orgId: ORG_ID });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(mockApi.post).toHaveBeenCalledWith(
      `/organizations/${ORG_ID}/github-credential/verify`
    );
  });
});

describe("useCheckRepoReachability", () => {
  it("POSTs to /check-repo endpoint with url", async () => {
    const reachabilityResponse = { reachable: true, detail: "Repository is accessible" };
    mockApi.post.mockResolvedValue(reachabilityResponse);
    const { wrapper } = createTestHookWrapper();

    const { result } = renderHook(() => useCheckRepoReachability(), { wrapper });

    result.current.mutate({ orgId: ORG_ID, url: "https://github.com/owner/repo" });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(mockApi.post).toHaveBeenCalledWith(
      `/organizations/${ORG_ID}/github-credential/check-repo`,
      { url: "https://github.com/owner/repo" }
    );
    expect(result.current.data).toEqual(reachabilityResponse);
  });
});
