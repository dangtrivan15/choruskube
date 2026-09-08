import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import EditGitRepoDialog from "@/components/git-repos/EditGitRepoDialog";
import type { GitRepoResponse } from "@/lib/types";

const mockMutate = vi.fn();
vi.mock("@/hooks/useGitRepos", () => ({
  useUpdateGitRepo: vi.fn(() => ({
    mutate: mockMutate,
    isPending: false,
    isError: false,
    reset: vi.fn(),
  })),
}));

beforeEach(() => {
  vi.clearAllMocks();
  mockMutate.mockReset();
});

function makeGitRepo(overrides: Partial<GitRepoResponse> = {}): GitRepoResponse {
  return {
    id: "r1",
    url: "https://github.com/org/repo",
    defaultBranch: "main",
    testCommand: null,
    agentImage: null,
    secrets: [],
    enableDocker: false,
    dindImage: null,
    createdAt: "2026-01-01T00:00:00Z",
    updatedAt: "2026-01-01T00:00:00Z",
    ...overrides,
  };
}

describe("EditGitRepoDialog", () => {
  it("hides the custom dind image field when Enable Docker is unchecked", async () => {
    renderWithProviders(
      <EditGitRepoDialog gitRepo={makeGitRepo()} open={true} onOpenChange={vi.fn()} />
    );

    await waitFor(() => {
      expect(screen.getByLabelText(/Enable Docker-in-Docker/i)).toBeInTheDocument();
    });
    expect(screen.queryByLabelText(/Custom dind image/i)).not.toBeInTheDocument();
  });

  it("shows the custom dind image field, seeded from the repo, when Enable Docker is checked", async () => {
    renderWithProviders(
      <EditGitRepoDialog
        gitRepo={makeGitRepo({ enableDocker: true, dindImage: "registry.example/dind:seeded" })}
        open={true}
        onOpenChange={vi.fn()}
      />
    );

    await waitFor(() => {
      expect(screen.getByLabelText(/Custom dind image/i)).toHaveValue(
        "registry.example/dind:seeded"
      );
    });
  });

  it("includes the custom dind image in the submit payload", async () => {
    renderWithProviders(
      <EditGitRepoDialog
        gitRepo={makeGitRepo({ enableDocker: true })}
        open={true}
        onOpenChange={vi.fn()}
      />
    );

    const user = userEvent.setup({ pointerEventsCheck: 0 });

    await waitFor(() => {
      expect(screen.getByLabelText(/Custom dind image/i)).toBeInTheDocument();
    });

    await user.type(screen.getByLabelText(/Custom dind image/i), "registry.example/dind:custom");
    await user.click(screen.getByRole("button", { name: /Save/i }));

    expect(mockMutate).toHaveBeenCalledWith(
      expect.objectContaining({
        id: "r1",
        body: expect.objectContaining({
          enableDocker: true,
          dindImage: "registry.example/dind:custom",
        }),
      }),
      expect.anything()
    );
  });
});
