import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import RepoGroupsTab from "@/components/repo-groups/RepoGroupsTab";
import type { RepoGroup } from "@/lib/types";

// Mock auth so role-gated controls render predictably.
vi.mock("@/components/AuthProvider", () => ({
  useAuth: vi.fn(() => ({ ...baseAuth })),
}));

vi.mock("@/lib/oidc", () => ({
  isAuthEnabled: vi.fn(() => true),
}));

// Stub data hooks — keeps tests fast and presentational.
vi.mock("@/hooks/useGitRepos", () => ({
  useGitRepos: vi.fn().mockReturnValue({
    data: { content: [], totalPages: 0, number: 0 },
    isLoading: false,
  }),
}));

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
import { useRepoGroups } from "@/hooks/useRepoGroups";

const mockUseAuth = useAuth as ReturnType<typeof vi.fn>;
const mockUseRepoGroups = useRepoGroups as ReturnType<typeof vi.fn>;

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

function makeGroup(overrides: Partial<RepoGroup> = {}): RepoGroup {
  return {
    id: "g1",
    name: "my-group",
    agentImage: "ghcr.io/example/agent:latest",
    description: "Test group",
    runtimeRequirements: { agentImage: "ghcr.io/example/agent:latest", enableDocker: false },
    members: [
      { gitRepoId: "r1", name: "repo-one", position: 0 },
      { gitRepoId: "r2", name: "repo-two", position: 1 },
    ],
    createdAt: "2026-01-15T10:00:00Z",
    updatedAt: "2026-01-15T10:00:00Z",
    ...overrides,
  };
}

describe("RepoGroupsTab", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockUseAuth.mockReturnValue({ ...baseAuth });
    mockUseRepoGroups.mockReturnValue({ data: [], isLoading: false });
  });

  describe("column headers", () => {
    it("renders all four column headers when empty", () => {
      renderWithProviders(<RepoGroupsTab />);

      // Regression guard: "Agent Image" header must exist as its own node,
      // not merged/overflowing into "Created".
      expect(screen.getByRole("columnheader", { name: "Name" })).toBeInTheDocument();
      expect(screen.getByRole("columnheader", { name: "Members" })).toBeInTheDocument();
      expect(screen.getByRole("columnheader", { name: "Agent Image" })).toBeInTheDocument();
      expect(screen.getByRole("columnheader", { name: "Created" })).toBeInTheDocument();
    });

    it("Agent Image header has the w-48 width class (table-fixed regression)", () => {
      renderWithProviders(<RepoGroupsTab />);

      const agentImageHeader = screen.getByRole("columnheader", { name: "Agent Image" });
      expect(agentImageHeader.className).toContain("w-48");
    });
  });

  describe("empty state", () => {
    it("renders the empty-state message when there are no groups", () => {
      renderWithProviders(<RepoGroupsTab />);

      expect(
        screen.getByText(/no repo groups yet/i),
      ).toBeInTheDocument();
    });

    it("renders the New Group button for org-admins", () => {
      renderWithProviders(<RepoGroupsTab />);

      expect(screen.getByRole("button", { name: /new group/i })).toBeInTheDocument();
    });

    it("does not render the New Group button for viewers", () => {
      mockUseAuth.mockReturnValue({ ...baseAuth, role: "viewer" });

      renderWithProviders(<RepoGroupsTab />);

      expect(screen.queryByRole("button", { name: /new group/i })).not.toBeInTheDocument();
    });
  });

  describe("loading state", () => {
    it("renders skeleton rows while loading", () => {
      mockUseRepoGroups.mockReturnValue({ data: undefined, isLoading: true });

      renderWithProviders(<RepoGroupsTab />);

      // The skeleton renders 3 rows; each row contains skeleton elements.
      const skeletons = document.querySelectorAll("[class*='skeleton'], [data-slot='skeleton']");
      // There should be multiple skeleton cells rendered.
      expect(skeletons.length).toBeGreaterThan(0);
    });

    it("does not show the empty-state message while loading", () => {
      mockUseRepoGroups.mockReturnValue({ data: undefined, isLoading: true });

      renderWithProviders(<RepoGroupsTab />);

      expect(screen.queryByText(/no repo groups yet/i)).not.toBeInTheDocument();
    });
  });

  describe("data rows", () => {
    it("renders a row for each group", () => {
      const groups = [
        makeGroup({ id: "g1", name: "group-alpha" }),
        makeGroup({ id: "g2", name: "group-beta" }),
      ];
      mockUseRepoGroups.mockReturnValue({ data: groups, isLoading: false });

      renderWithProviders(<RepoGroupsTab />);

      expect(screen.getByText("group-alpha")).toBeInTheDocument();
      expect(screen.getByText("group-beta")).toBeInTheDocument();
    });

    it("shows the agent image in the row", () => {
      mockUseRepoGroups.mockReturnValue({
        data: [makeGroup()],
        isLoading: false,
      });

      renderWithProviders(<RepoGroupsTab />);

      expect(screen.getByText("ghcr.io/example/agent:latest")).toBeInTheDocument();
    });

    it("shows member count in a collapsible button", () => {
      mockUseRepoGroups.mockReturnValue({
        data: [makeGroup()],
        isLoading: false,
      });

      renderWithProviders(<RepoGroupsTab />);

      // The member toggle shows the count.
      expect(screen.getByText(/2 repos/i)).toBeInTheDocument();
    });

    it("expands member list when toggle is clicked", async () => {
      const user = userEvent.setup();
      mockUseRepoGroups.mockReturnValue({
        data: [makeGroup()],
        isLoading: false,
      });

      renderWithProviders(<RepoGroupsTab />);

      const toggle = screen.getByRole("button", { name: /expand my-group members/i });
      await user.click(toggle);

      // After expanding, the member names should be visible.
      expect(screen.getByText("repo-one")).toBeInTheDocument();
      expect(screen.getByText("repo-two")).toBeInTheDocument();
    });

    it("shows edit and delete buttons for org-admins", () => {
      mockUseRepoGroups.mockReturnValue({
        data: [makeGroup()],
        isLoading: false,
      });

      renderWithProviders(<RepoGroupsTab />);

      expect(
        screen.getByTestId("repo-group-edit-button"),
      ).toBeInTheDocument();
      expect(
        screen.getByTestId("repo-group-delete-button"),
      ).toBeInTheDocument();
    });
  });

  describe("New Group dialog", () => {
    it("opens the create dialog when New Group is clicked", async () => {
      const user = userEvent.setup();
      renderWithProviders(<RepoGroupsTab />);

      await user.click(screen.getByRole("button", { name: /new group/i }));

      expect(screen.getByRole("dialog")).toBeInTheDocument();
      expect(screen.getByText("New Repo Group")).toBeInTheDocument();
    });
  });
});
