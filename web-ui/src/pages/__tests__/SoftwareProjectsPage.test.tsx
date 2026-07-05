import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import SoftwareProjectsPage from "@/pages/SoftwareProjectsPage";

// Mock auth so role-gated controls render predictably.
vi.mock("@/components/AuthProvider", () => ({
  useAuth: vi.fn(() => ({ ...baseAuth })),
}));

vi.mock("@/lib/oidc", () => ({
  isAuthEnabled: vi.fn(() => true),
}));

// Stub out the data hooks so we don't need a real API or QueryClient round-trip.
vi.mock("@/hooks/useGitRepos", () => ({
  useGitRepos: vi.fn().mockReturnValue({
    data: { content: [], totalPages: 0, number: 0 },
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

// RepoGroupsTab now hits useRepoGroups + the create/update/delete mutations —
// stub them so the page test stays presentational and doesn't try to touch the
// real API.
vi.mock("@/hooks/useRepoGroups", () => ({
  useRepoGroups: vi.fn().mockReturnValue({ data: [], isLoading: false }),
  useCreateRepoGroup: vi.fn().mockReturnValue({
    mutate: vi.fn(),
    isPending: false,
    isError: false,
    reset: vi.fn(),
  }),
  useUpdateRepoGroup: vi.fn().mockReturnValue({
    mutate: vi.fn(),
    isPending: false,
    isError: false,
    reset: vi.fn(),
  }),
  useDeleteRepoGroup: vi.fn().mockReturnValue({
    mutate: vi.fn(),
    isPending: false,
    isError: false,
    reset: vi.fn(),
  }),
}));

import { useAuth } from "@/components/AuthProvider";

const mockUseAuth = useAuth as ReturnType<typeof vi.fn>;

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

describe("SoftwareProjectsPage", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockUseAuth.mockReturnValue({ ...baseAuth });
  });

  it("renders the page header titled 'Software Projects'", () => {
    renderWithProviders(<SoftwareProjectsPage />);

    expect(
      screen.getByRole("heading", { name: "Software Projects", level: 1 }),
    ).toBeInTheDocument();
  });

  it("renders both tab buttons in order: Repo Groups, Repositories", () => {
    renderWithProviders(<SoftwareProjectsPage />);

    const tabButtons = screen
      .getAllByRole("button")
      .filter((b) => b.textContent === "Repo Groups" || b.textContent === "Repositories");
    expect(tabButtons.map((b) => b.textContent)).toEqual([
      "Repo Groups",
      "Repositories",
    ]);
  });

  it("defaults to the Repo Groups tab on initial render", () => {
    renderWithProviders(<SoftwareProjectsPage />);

    // The Repo Groups tab body is mounted — its "New Group" CTA proves it.
    expect(screen.getByText("New Group")).toBeInTheDocument();
    // Repositories tab content (its "New Repo" CTA) is NOT visible yet.
    expect(screen.queryByText("New Repo")).not.toBeInTheDocument();
  });

  it("switches to the Repositories tab when its button is clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(<SoftwareProjectsPage />);

    await user.click(screen.getByRole("button", { name: "Repositories" }));

    // Repositories tab body is now mounted — the "New Repo" CTA proves it.
    expect(screen.getByText("New Repo")).toBeInTheDocument();
    // And the Repo Groups CTA is gone (the tab body unmounted).
    expect(screen.queryByText("New Group")).not.toBeInTheDocument();
  });
});
