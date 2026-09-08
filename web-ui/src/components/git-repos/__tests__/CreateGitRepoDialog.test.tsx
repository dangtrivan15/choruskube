import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import CreateGitRepoDialog from "@/components/git-repos/CreateGitRepoDialog";

const mockMutate = vi.fn();
vi.mock("@/hooks/useGitRepos", () => ({
  useCreateGitRepo: vi.fn(() => ({
    mutate: mockMutate,
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
  mockMutate.mockReset();
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

  it("hides the custom dind image field until Enable Docker is checked", async () => {
    mockUseGitHubCredential.mockReturnValue({ data: { id: "cred-1", credentialType: "pat" } });

    renderWithProviders(<CreateGitRepoDialog open={true} onOpenChange={vi.fn()} />);

    await waitFor(() => {
      expect(screen.getByLabelText(/Enable Docker-in-Docker/i)).toBeInTheDocument();
    });
    expect(screen.queryByLabelText(/Custom dind image/i)).not.toBeInTheDocument();

    const user = userEvent.setup({ pointerEventsCheck: 0 });
    await user.click(screen.getByLabelText(/Enable Docker-in-Docker/i));

    expect(screen.getByLabelText(/Custom dind image/i)).toBeInTheDocument();
  });

  it("includes the custom dind image in the submit payload", async () => {
    mockUseGitHubCredential.mockReturnValue({ data: { id: "cred-1", credentialType: "pat" } });

    renderWithProviders(<CreateGitRepoDialog open={true} onOpenChange={vi.fn()} />);

    const user = userEvent.setup({ pointerEventsCheck: 0 });

    await waitFor(() => {
      expect(screen.getByLabelText(/Repository URL/i)).toBeInTheDocument();
    });

    await user.type(screen.getByLabelText(/Repository URL/i), "https://github.com/org/repo");
    await user.click(screen.getByLabelText(/Enable Docker-in-Docker/i));
    await user.type(screen.getByLabelText(/Custom dind image/i), "registry.example/dind:custom");
    await user.click(screen.getByRole("button", { name: /Create/i }));

    expect(mockMutate).toHaveBeenCalledWith(
      expect.objectContaining({
        enableDocker: true,
        dindImage: "registry.example/dind:custom",
      }),
      expect.anything()
    );
  });
});
