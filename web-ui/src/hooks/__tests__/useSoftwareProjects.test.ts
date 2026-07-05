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

import { api } from "@/lib/api";
import { useSoftwareProjects } from "@/hooks/useSoftwareProjects";
import type { SoftwareProject } from "@/lib/types";

const mockApi = api as unknown as {
  get: ReturnType<typeof vi.fn>;
};

describe("useSoftwareProjects", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("fetches the flat software-project list", async () => {
    const data: SoftwareProject[] = [
      {
        id: "r1",
        name: "repo-one",
        type: "git_repo",
        agentImage: null,
        description: null,
        runtimeRequirements: { agentImage: null, enableDocker: false },
        createdAt: "2026-01-01T00:00:00Z",
        updatedAt: "2026-01-01T00:00:00Z",
      },
      {
        id: "g1",
        name: "group-one",
        type: "repo_group",
        agentImage: null,
        description: null,
        runtimeRequirements: { agentImage: null, enableDocker: false },
        createdAt: "2026-01-01T00:00:00Z",
        updatedAt: "2026-01-01T00:00:00Z",
      },
    ];
    mockApi.get.mockResolvedValueOnce(data);
    const { wrapper } = createTestHookWrapper();

    const { result } = renderHook(() => useSoftwareProjects(), { wrapper });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toEqual(data);
    expect(mockApi.get).toHaveBeenCalledWith("/software-projects");
  });
});
