import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/__tests__/test-utils";
import GitRepoListPage from "@/pages/GitRepoListPage";

vi.mock("@/components/AuthProvider", () => ({
  useAuth: vi.fn(() => ({ ...baseAuth })),
}));

vi.mock("@/lib/oidc", () => ({
  isAuthEnabled: vi.fn(() => true),
}));

vi.mock("@/hooks/useGitRepos", () => ({
  useGitRepos: vi.fn().mockReturnValue({
    data: {
      content: [
        {
          id: "repo-1",
          url: "https://github.com/test/repo",
          defaultBranch: "main",
          testCommand: "npm test",
          enableDocker: false,
          createdAt: "2025-01-01T00:00:00Z",
        },
      ],
      totalPages: 1,
      number: 0,
    },
    isLoading: false,
  }),
  useCreateGitRepo: vi.fn().mockReturnValue({
    mutate: vi.fn(),
    isPending: false,
    isError: false,
    reset: vi.fn(),
  }),
  useUpdateGitRepo: vi.fn().mockReturnValue({
    mutate: vi.fn(),
    isPending: false,
    isError: false,
    reset: vi.fn(),
  }),
  useDeleteGitRepo: vi.fn().mockReturnValue({
    mutate: vi.fn(),
    isPending: false,
    isError: false,
    reset: vi.fn(),
  }),
}));

import { useAuth } from "@/components/AuthProvider";
import { isAuthEnabled } from "@/lib/oidc";

const mockUseAuth = useAuth as ReturnType<typeof vi.fn>;
const mockIsAuthEnabled = isAuthEnabled as ReturnType<typeof vi.fn>;

const baseAuth = {
  authenticated: true,
  token: "test-token",
  username: "testuser",
  roles: [],
  organizationId: "org-1",
  organizationSlug: "test-org",
  role: "org-admin" as string | null,
  platformAdmin: false,
  logout: vi.fn(),
};

describe("GitRepoListPage", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockIsAuthEnabled.mockReturnValue(true);
    mockUseAuth.mockReturnValue({ ...baseAuth, role: "org-admin" });
  });

  it("shows New Repo button for operators", () => {
    mockUseAuth.mockReturnValue({ ...baseAuth, role: "operator" });

    renderWithProviders(<GitRepoListPage />);

    expect(screen.getByText("New Repo")).toBeInTheDocument();
  });

  it("hides New Repo button for viewers", () => {
    mockUseAuth.mockReturnValue({ ...baseAuth, role: "viewer" });

    renderWithProviders(<GitRepoListPage />);

    expect(screen.queryByText("New Repo")).not.toBeInTheDocument();
  });

  it("shows Edit/Delete buttons for admins", () => {
    mockUseAuth.mockReturnValue({ ...baseAuth, role: "org-admin" });

    renderWithProviders(<GitRepoListPage />);

    // The repo row should render
    expect(screen.getByText("test/repo")).toBeInTheDocument();
    // Edit and delete buttons should be present for admins
    expect(screen.getAllByTestId("repo-edit-button").length).toBeGreaterThan(0);
    expect(screen.getAllByTestId("repo-delete-button").length).toBeGreaterThan(0);
  });

  it("hides Edit/Delete row buttons for operators", () => {
    mockUseAuth.mockReturnValue({ ...baseAuth, role: "operator" });

    renderWithProviders(<GitRepoListPage />);

    // The New Repo button should still be visible (canOperate)
    expect(screen.getByText("New Repo")).toBeInTheDocument();
    // The repo data should still render
    expect(screen.getByText("test/repo")).toBeInTheDocument();
    // But row-level edit/delete actions (canAdmin) should be hidden
    expect(screen.queryByTestId("repo-edit-button")).not.toBeInTheDocument();
    expect(screen.queryByTestId("repo-delete-button")).not.toBeInTheDocument();
  });
});
