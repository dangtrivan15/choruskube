import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders } from "@/__tests__/test-utils";
import CreateGitRepoDialog from "@/components/git-repos/CreateGitRepoDialog";

vi.mock("@/hooks/useGitRepos", () => ({
  useCreateGitRepo: vi.fn(() => ({
    mutate: vi.fn(),
    isPending: false,
    isError: false,
    reset: vi.fn(),
  })),
}));

vi.mock("@/components/AuthProvider", () => ({
  useAuth: vi.fn(() => ({
    organizationId: "org-1",
    authenticated: true,
  })),
}));

vi.mock("@/hooks/useGitHubCredential", () => ({
  useGitHubCredential: vi.fn(() => ({ data: undefined, isLoading: true })),
}));

import { useGitHubCredential } from "@/hooks/useGitHubCredential";
const mockUseGitHubCredential = useGitHubCredential as ReturnType<typeof vi.fn>;

beforeEach(() => {
  vi.clearAllMocks();
});

describe("CreateGitRepoDialog", () => {
  it("does not show credential banner when credential is present", async () => {
    mockUseGitHubCredential.mockReturnValue({ data: { id: "cred-1", credentialType: "pat" } });

    renderWithProviders(<CreateGitRepoDialog open={true} onOpenChange={vi.fn()} />);

    await waitFor(() => {
      expect(screen.getByText("New Git Repo")).toBeInTheDocument();
    });

    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("shows credential banner when no credential is configured", async () => {
    mockUseGitHubCredential.mockReturnValue({ data: null });

    renderWithProviders(<CreateGitRepoDialog open={true} onOpenChange={vi.fn()} />);

    await waitFor(() => {
      expect(screen.getByRole("alert")).toBeInTheDocument();
    });

    expect(screen.getByText(/No GitHub credential is configured/i)).toBeInTheDocument();
  });

  it("form remains usable when no credential is configured", async () => {
    mockUseGitHubCredential.mockReturnValue({ data: null });

    renderWithProviders(<CreateGitRepoDialog open={true} onOpenChange={vi.fn()} />);

    await waitFor(() => {
      expect(screen.getByLabelText(/Repository URL/i)).toBeInTheDocument();
    });

    expect(screen.getByRole("button", { name: /Create/i })).toBeInTheDocument();
  });
});
