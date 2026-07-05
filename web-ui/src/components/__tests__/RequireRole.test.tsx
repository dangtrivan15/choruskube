import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/__tests__/test-utils";
import RequireRole from "@/components/RequireRole";

vi.mock("@/lib/oidc", () => ({
  isAuthEnabled: vi.fn(() => false),
}));

vi.mock("@/components/AuthProvider", () => ({
  useAuth: vi.fn(() => ({
    authenticated: false,
    roles: [],
    token: undefined,
    username: undefined,
    organizationId: undefined,
    organizationSlug: undefined,
    role: null,
    platformAdmin: false,
    actingAsPlatformAdmin: false,
    logout: vi.fn(),
  })),
}));

import { isAuthEnabled } from "@/lib/oidc";
import { useAuth } from "@/components/AuthProvider";

const mockIsAuthEnabled = isAuthEnabled as ReturnType<typeof vi.fn>;
const mockUseAuth = useAuth as ReturnType<typeof vi.fn>;

const baseAuth = {
  authenticated: false,
  roles: [] as string[],
  token: undefined,
  username: undefined,
  organizationId: undefined,
  organizationSlug: undefined,
  role: null,
  platformAdmin: false,
  actingAsPlatformAdmin: false,
  logout: vi.fn(),
};

describe("RequireRole", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockIsAuthEnabled.mockReturnValue(false);
    mockUseAuth.mockReturnValue(baseAuth);
  });

  it("renders children when auth is disabled (dev mode)", () => {
    renderWithProviders(
      <RequireRole role="org-admin">
        <div>Admin Content</div>
      </RequireRole>,
    );
    expect(screen.getByText("Admin Content")).toBeInTheDocument();
  });

  it("renders children when auth enabled and user has role", () => {
    mockIsAuthEnabled.mockReturnValue(true);
    mockUseAuth.mockReturnValue({
      authenticated: true,
      roles: ["org-admin"],
      token: "test-token",
      username: "admin-user",
      organizationId: "test-org-id",
      logout: vi.fn(),
    });
    renderWithProviders(
      <RequireRole role="org-admin">
        <div>Admin Content</div>
      </RequireRole>,
    );
    expect(screen.getByText("Admin Content")).toBeInTheDocument();
  });

  it("redirects when auth enabled and user lacks role", () => {
    mockIsAuthEnabled.mockReturnValue(true);
    mockUseAuth.mockReturnValue({
      authenticated: true,
      roles: ["operator"],
      token: "test-token",
      username: "op-user",
      organizationId: "test-org-id",
      logout: vi.fn(),
    });
    renderWithProviders(
      <RequireRole role="org-admin">
        <div>Admin Content</div>
      </RequireRole>,
    );
    expect(screen.queryByText("Admin Content")).not.toBeInTheDocument();
  });

  it("redirects when auth enabled and user is not authenticated", () => {
    mockIsAuthEnabled.mockReturnValue(true);
    renderWithProviders(
      <RequireRole role="org-admin">
        <div>Admin Content</div>
      </RequireRole>,
    );
    expect(screen.queryByText("Admin Content")).not.toBeInTheDocument();
  });

  // --- platformAdmin mode ---

  it("renders children when role=platformAdmin and user is acting as platform admin", () => {
    mockIsAuthEnabled.mockReturnValue(true);
    mockUseAuth.mockReturnValue({
      ...baseAuth,
      authenticated: true,
      platformAdmin: true,
      actingAsPlatformAdmin: true,
    });
    renderWithProviders(
      <RequireRole role="platformAdmin">
        <div>Platform Content</div>
      </RequireRole>,
    );
    expect(screen.getByText("Platform Content")).toBeInTheDocument();
  });

  it("redirects when role=platformAdmin and user lacks platform-admin identity (even with org-admin OIDC role)", () => {
    mockIsAuthEnabled.mockReturnValue(true);
    mockUseAuth.mockReturnValue({
      ...baseAuth,
      authenticated: true,
      roles: ["org-admin"], // has org-admin role but not in system org
      platformAdmin: false,
      actingAsPlatformAdmin: false,
    });
    renderWithProviders(
      <RequireRole role="platformAdmin">
        <div>Platform Content</div>
      </RequireRole>,
    );
    expect(screen.queryByText("Platform Content")).not.toBeInTheDocument();
  });

  it("redirects when role=platformAdmin for a platform admin whose working org is not system", () => {
    // The identity is platform-admin, but they've switched workspace to a non-system org,
    // so the backend would 403 any platform-admin call. The guard must redirect instead of
    // letting the page render and trip an in-view 403.
    mockIsAuthEnabled.mockReturnValue(true);
    mockUseAuth.mockReturnValue({
      ...baseAuth,
      authenticated: true,
      platformAdmin: true,
      actingAsPlatformAdmin: false,
      organizationSlug: "acme-corp",
    });
    renderWithProviders(
      <RequireRole role="platformAdmin">
        <div>Platform Content</div>
      </RequireRole>,
    );
    expect(screen.queryByText("Platform Content")).not.toBeInTheDocument();
  });

  it("renders children when role=platformAdmin in dev mode (auth disabled) and user is acting as platform admin", () => {
    mockIsAuthEnabled.mockReturnValue(false);
    mockUseAuth.mockReturnValue({
      ...baseAuth,
      platformAdmin: true,
      actingAsPlatformAdmin: true,
    });
    renderWithProviders(
      <RequireRole role="platformAdmin">
        <div>Platform Content</div>
      </RequireRole>,
    );
    expect(screen.getByText("Platform Content")).toBeInTheDocument();
  });
});
