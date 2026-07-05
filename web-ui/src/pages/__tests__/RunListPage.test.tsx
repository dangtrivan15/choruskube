import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/__tests__/test-utils";
import RunListPage from "@/pages/RunListPage";

vi.mock("@/components/AuthProvider", () => ({
  useAuth: vi.fn(() => ({ ...baseAuth })),
}));

vi.mock("@/lib/oidc", () => ({
  isAuthEnabled: vi.fn(() => true),
}));

vi.mock("@/hooks/useRuns", () => ({
  useRuns: vi.fn().mockReturnValue({
    data: { content: [], totalPages: 0, number: 0 },
    isLoading: false,
  }),
  useStartRun: vi.fn().mockReturnValue({
    mutate: vi.fn(),
    isPending: false,
  }),
}));

vi.mock("@/hooks/useTemplates", () => ({
  useTemplates: vi.fn().mockReturnValue({
    data: [],
    isLoading: false,
  }),
}));

vi.mock("@/hooks/useGitRepos", () => ({
  useGitRepos: vi.fn().mockReturnValue({
    data: { content: [] },
    isLoading: false,
  }),
}));

vi.mock("@/hooks/useRunSubscription", () => ({
  useRunListSubscription: vi.fn(),
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
  role: "operator" as string | null,
  platformAdmin: false,
  logout: vi.fn(),
};

describe("RunListPage", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockIsAuthEnabled.mockReturnValue(true);
    mockUseAuth.mockReturnValue({ ...baseAuth, role: "operator" });
  });

  it("shows Start Run button for operators", () => {
    mockUseAuth.mockReturnValue({ ...baseAuth, role: "operator" });

    renderWithProviders(<RunListPage />);

    expect(screen.getByTestId("start-run-button")).toBeInTheDocument();
  });

  it("hides Start Run button for viewers", () => {
    mockUseAuth.mockReturnValue({ ...baseAuth, role: "viewer" });

    renderWithProviders(<RunListPage />);

    expect(screen.queryByTestId("start-run-button")).not.toBeInTheDocument();
  });

  it("shows Start Run button when auth is disabled (dev mode)", () => {
    mockIsAuthEnabled.mockReturnValue(false);
    mockUseAuth.mockReturnValue({ ...baseAuth, role: null });

    renderWithProviders(<RunListPage />);

    expect(screen.getByTestId("start-run-button")).toBeInTheDocument();
  });
});
