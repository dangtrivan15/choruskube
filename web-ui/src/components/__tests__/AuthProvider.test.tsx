import { describe, it, expect, vi, beforeEach } from "vitest";
import { act, render, screen, waitFor } from "@testing-library/react";

vi.mock("@/lib/oidc", () => ({
  isAuthEnabled: vi.fn(() => false),
  initAuth: vi.fn().mockResolvedValue(false),
  getToken: vi.fn(() => undefined),
  getClaims: vi.fn(() => undefined),
  refreshToken: vi.fn().mockResolvedValue(undefined),
  login: vi.fn().mockResolvedValue(undefined),
  logout: vi.fn().mockResolvedValue(undefined),
  isAuthenticated: vi.fn(() => false),
}));

vi.mock("@/lib/api", () => ({
  api: { get: vi.fn() },
  ApiError: class ApiError extends Error {},
}));

vi.mock("@/lib/impersonation", () => ({ useImpersonation: vi.fn() }));

import { AuthProvider, useAuth } from "@/components/AuthProvider";
import { SYSTEM_ORG_ID } from "@/lib/constants";
import { useImpersonation } from "@/lib/impersonation";

function ContextProbe() {
  const {
    platformAdmin,
    actingAsPlatformAdmin,
    organizationId,
    organizationSlug,
    activeOrg,
    memberships,
    role,
    onboardingCompleted,
  } = useAuth();
  return (
    <div>
      <span data-testid="platform-admin">{String(platformAdmin)}</span>
      <span data-testid="acting-as-platform-admin">{String(actingAsPlatformAdmin)}</span>
      <span data-testid="org-id">{organizationId ?? "null"}</span>
      <span data-testid="org-slug">{organizationSlug ?? "null"}</span>
      <span data-testid="active-org-id">{activeOrg?.id ?? "null"}</span>
      <span data-testid="active-org-slug">{activeOrg?.slug ?? "null"}</span>
      <span data-testid="memberships-count">{memberships.length}</span>
      <span data-testid="role">{role ?? "null"}</span>
      <span data-testid="onboarding-completed">{String(onboardingCompleted)}</span>
    </div>
  );
}

describe("AuthProvider", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(useImpersonation).mockReturnValue(null);
  });

  it("dev mode exposes platformAdmin=true and system org defaults", async () => {
    render(
      <AuthProvider>
        <ContextProbe />
      </AuthProvider>,
    );

    await waitFor(() => {
      expect(screen.getByTestId("platform-admin")).toHaveTextContent("true");
    });
    // Dev mode's active org is `system`, so the derived in-effect flag is true too.
    expect(screen.getByTestId("acting-as-platform-admin")).toHaveTextContent("true");
    expect(screen.getByTestId("org-id")).toHaveTextContent(SYSTEM_ORG_ID);
    expect(screen.getByTestId("org-slug")).toHaveTextContent("system");
    expect(screen.getByTestId("active-org-id")).toHaveTextContent(SYSTEM_ORG_ID);
    expect(screen.getByTestId("active-org-slug")).toHaveTextContent("system");
    expect(screen.getByTestId("memberships-count")).toHaveTextContent("1");
    expect(screen.getByTestId("role")).toHaveTextContent("org-admin");
  });

  it("actingAsPlatformAdmin is false when platformAdmin=true but active org is not system", async () => {
    // Covers the workspace-switched platform admin: backend resolves
    // isPlatformAdmin() against the JWT's active org, so the in-effect flag must
    // drop to false to keep UI gating aligned with what the server will authorize.
    const { isAuthEnabled, initAuth, getToken, getClaims } = await import("@/lib/oidc");
    const { api } = await import("@/lib/api");

    vi.mocked(isAuthEnabled).mockReturnValue(true);
    vi.mocked(initAuth).mockResolvedValue(true);
    vi.mocked(getToken).mockReturnValue("mock-token");
    vi.mocked(getClaims).mockReturnValue({ username: "admin", roles: ["org-admin"] });
    vi.mocked(api.get).mockResolvedValue({
      userId: "u1",
      activeOrg: { id: "org-2", slug: "acme-corp", displayName: "Acme" },
      memberships: [
        { id: SYSTEM_ORG_ID, slug: "system", displayName: "System" },
        { id: "org-2", slug: "acme-corp", displayName: "Acme" },
      ],
      platformAdmin: true,
      onboardingCompleted: true,
      role: "org-admin",
    });

    render(
      <AuthProvider>
        <ContextProbe />
      </AuthProvider>,
    );

    await waitFor(() => {
      expect(screen.getByTestId("active-org-slug")).toHaveTextContent("acme-corp");
    });
    expect(screen.getByTestId("platform-admin")).toHaveTextContent("true");
    expect(screen.getByTestId("acting-as-platform-admin")).toHaveTextContent("false");
  });

  it("while impersonating, overrides activeOrg and drops actingAsPlatformAdmin to false", async () => {
    // Precondition: platform admin in system org (dev-mode defaults).
    // Then start a manage-as session and verify the context presents as a member
    // of the impersonated org, while raw `platformAdmin` identity stays true so
    // the impersonation banner's Exit button keeps working.
    vi.mocked(useImpersonation).mockReturnValue({ orgId: "org-khoa", orgSlug: "khoahuynhdev" });

    render(
      <AuthProvider>
        <ContextProbe />
      </AuthProvider>,
    );

    await waitFor(() => {
      expect(screen.getByTestId("active-org-slug")).toHaveTextContent("khoahuynhdev");
    });
    expect(screen.getByTestId("active-org-id")).toHaveTextContent("org-khoa");
    expect(screen.getByTestId("org-id")).toHaveTextContent("org-khoa");
    expect(screen.getByTestId("org-slug")).toHaveTextContent("khoahuynhdev");
    // Memberships collapse to the impersonated org so the sidebar workspace switcher
    // (gated on memberships.length > 1) stays hidden during manage-as.
    expect(screen.getByTestId("memberships-count")).toHaveTextContent("1");
    // Admin-only UI (Organizations nav, /admin/* route guards) disappears.
    expect(screen.getByTestId("acting-as-platform-admin")).toHaveTextContent("false");
    // But the underlying identity stays — needed for the Exit-impersonation button and
    // the post-exit return to platform-admin surfaces.
    expect(screen.getByTestId("platform-admin")).toHaveTextContent("true");
  });

  it("dev mode exposes onboardingCompleted=true", async () => {
    render(
      <AuthProvider>
        <ContextProbe />
      </AuthProvider>,
    );

    await waitFor(() => {
      expect(screen.getByTestId("onboarding-completed")).toHaveTextContent("true");
    });
  });

  it("renders OrgPicker when /me returns activeOrg=null and memberships has 2+", async () => {
    const { isAuthEnabled, initAuth, getToken, getClaims } = await import("@/lib/oidc");
    const { api } = await import("@/lib/api");

    vi.mocked(isAuthEnabled).mockReturnValue(true);
    vi.mocked(initAuth).mockResolvedValue(true);
    vi.mocked(getToken).mockReturnValue("mock-token");
    vi.mocked(getClaims).mockReturnValue({ username: "testuser", roles: [] });
    vi.mocked(api.get).mockResolvedValue({
      userId: "u1",
      activeOrg: null,
      memberships: [
        { id: "org-1", slug: "alpha", displayName: "Alpha" },
        { id: "org-2", slug: "beta", displayName: "Beta" },
      ],
      platformAdmin: false,
      onboardingCompleted: true,
      role: "viewer",
    });

    render(
      <AuthProvider>
        <ContextProbe />
      </AuthProvider>,
    );

    await waitFor(() => {
      expect(screen.getByText("Choose your workspace")).toBeInTheDocument();
    });
    // The children probe is replaced by the picker, so the probe is absent.
    expect(screen.queryByTestId("platform-admin")).not.toBeInTheDocument();
    expect(screen.getByTestId("org-picker-alpha")).toBeInTheDocument();
    expect(screen.getByTestId("org-picker-beta")).toBeInTheDocument();
  });

  it("renders children (not OrgPicker) when /me returns an activeOrg", async () => {
    const { isAuthEnabled, initAuth, getToken, getClaims } = await import("@/lib/oidc");
    const { api } = await import("@/lib/api");

    vi.mocked(isAuthEnabled).mockReturnValue(true);
    vi.mocked(initAuth).mockResolvedValue(true);
    vi.mocked(getToken).mockReturnValue("mock-token");
    vi.mocked(getClaims).mockReturnValue({ username: "testuser", roles: [] });
    vi.mocked(api.get).mockResolvedValue({
      userId: "u1",
      activeOrg: { id: "org-1", slug: "alpha", displayName: "Alpha" },
      memberships: [{ id: "org-1", slug: "alpha", displayName: "Alpha" }],
      platformAdmin: false,
      onboardingCompleted: true,
      role: "viewer",
    });

    render(
      <AuthProvider>
        <ContextProbe />
      </AuthProvider>,
    );

    await waitFor(() => {
      expect(screen.getByTestId("active-org-slug")).toHaveTextContent("alpha");
    });
    expect(screen.queryByText("Choose your workspace")).not.toBeInTheDocument();
    // Back-compat derived getters still populated.
    expect(screen.getByTestId("org-id")).toHaveTextContent("org-1");
    expect(screen.getByTestId("org-slug")).toHaveTextContent("alpha");
    expect(screen.getByTestId("memberships-count")).toHaveTextContent("1");
  });

  it("shows error UI when /me fetch fails with auth enabled", async () => {
    const { isAuthEnabled, initAuth, getToken, getClaims } = await import("@/lib/oidc");
    const { api } = await import("@/lib/api");

    vi.mocked(isAuthEnabled).mockReturnValue(true);
    vi.mocked(initAuth).mockResolvedValue(true);
    vi.mocked(getToken).mockReturnValue("mock-token");
    vi.mocked(getClaims).mockReturnValue({ username: "testuser", roles: ["org-admin"] });
    vi.mocked(api.get).mockRejectedValue(new Error("Network error"));

    render(
      <AuthProvider>
        <ContextProbe />
      </AuthProvider>,
    );

    await waitFor(() => {
      expect(screen.getByText("Authentication Error")).toBeInTheDocument();
    });
    expect(screen.getByText("Retry")).toBeInTheDocument();
    expect(screen.getByText("Logout")).toBeInTheDocument();
  });

  it("refetches /me when the window gains focus (debounced to once per 30s)", async () => {
    const { isAuthEnabled, initAuth, getToken, getClaims } = await import("@/lib/oidc");
    const { api } = await import("@/lib/api");

    vi.mocked(isAuthEnabled).mockReturnValue(true);
    vi.mocked(initAuth).mockResolvedValue(true);
    vi.mocked(getToken).mockReturnValue("mock-token");
    vi.mocked(getClaims).mockReturnValue({ username: "testuser", roles: [] });

    const me = {
      userId: "u1",
      activeOrg: { id: "org-1", slug: "alpha", displayName: "Alpha" },
      memberships: [{ id: "org-1", slug: "alpha", displayName: "Alpha" }],
      platformAdmin: false,
      onboardingCompleted: true,
      role: "viewer",
    };
    vi.mocked(api.get).mockResolvedValue(me);

    render(
      <AuthProvider>
        <ContextProbe />
      </AuthProvider>,
    );

    await waitFor(() => {
      expect(screen.getByTestId("active-org-slug")).toHaveTextContent("alpha");
    });

    expect(vi.mocked(api.get)).toHaveBeenCalledTimes(1);

    // A focus within 30s of the initial fetch is debounced.
    await act(async () => {
      window.dispatchEvent(new FocusEvent("focus"));
    });

    expect(vi.mocked(api.get)).toHaveBeenCalledTimes(1);
  });
});
