import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ReactNode } from "react";
import { Building2 } from "lucide-react";
import { renderWithProviders } from "@/__tests__/test-utils";
import Sidebar from "@/components/layout/Sidebar";
import { ExtensionsProvider } from "@/ExtensionsContext";
import type { NavItem } from "@/extensions";

const cloudNavItems: NavItem[] = [
  { to: "/admin/organizations", label: "Organizations", icon: Building2, platformAdminOnly: true, shortcutHint: "g O" },
  {
    to: (ctx) => (ctx.organizationId ? `/organizations/${ctx.organizationId}` : undefined),
    label: "My Organization",
    icon: Building2,
    shortcutHint: "g o",
    testId: "nav-my-organization",
  },
];

function withNav(children: ReactNode) {
  return <ExtensionsProvider value={{ navItems: cloudNavItems }}>{children}</ExtensionsProvider>;
}

vi.mock("@/hooks/usePendingGates", () => ({
  usePendingGatesCount: vi.fn().mockReturnValue({ data: { count: 0 } }),
}));

const mockUsePendingGatesSubscription = vi.fn();
vi.mock("@/hooks/usePendingGatesSubscription", () => ({
  usePendingGatesSubscription: (...args: unknown[]) => mockUsePendingGatesSubscription(...args),
}));

vi.mock("@/lib/oidc", () => ({
  isAuthEnabled: vi.fn(() => false),
  login: vi.fn().mockResolvedValue(undefined),
}));

vi.mock("@/components/AuthProvider", () => ({
  useAuth: vi.fn(() => ({
    authenticated: false,
    token: undefined,
    username: undefined,
    roles: [],
    activeOrg: null,
    memberships: [],
    organizationId: undefined,
    organizationSlug: undefined,
    role: null,
    platformAdmin: false,
    actingAsPlatformAdmin: false,
    logout: vi.fn(),
  })),
}));

import { usePendingGatesCount } from "@/hooks/usePendingGates";
import { useAuth } from "@/components/AuthProvider";
import { isAuthEnabled, login } from "@/lib/oidc";

const mockUsePendingGatesCount = usePendingGatesCount as ReturnType<typeof vi.fn>;
const mockUseAuth = useAuth as ReturnType<typeof vi.fn>;
const mockIsAuthEnabled = isAuthEnabled as ReturnType<typeof vi.fn>;
const mockLogin = vi.mocked(login);

const baseAuth = {
  authenticated: false,
  token: undefined,
  username: undefined,
  roles: [],
  activeOrg: null,
  memberships: [],
  organizationId: undefined,
  organizationSlug: undefined,
  role: null,
  platformAdmin: false,
  actingAsPlatformAdmin: false,
  logout: vi.fn(),
};

describe("Sidebar", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockUsePendingGatesCount.mockReturnValue({ data: { count: 0 } });
    mockUseAuth.mockReturnValue(baseAuth);
  });

  it("renders navigation items", () => {
    renderWithProviders(<Sidebar />);

    expect(screen.getByText("Runs")).toBeInTheDocument();
    expect(screen.getByText("Approvals")).toBeInTheDocument();
    expect(screen.getByText("Software Projects")).toBeInTheDocument();
  });

  it("renders the brand SVG mark next to the ChorusKube wordmark", () => {
    renderWithProviders(<Sidebar />);

    const wordmark = screen.getByRole("heading", { name: "ChorusKube" });
    // Logo and wordmark sit in the same flex row inside the brand block.
    const brandRow = wordmark.parentElement;
    expect(brandRow).not.toBeNull();
    const mark = brandRow?.querySelector('[data-testid="logo-mark"]');
    expect(mark).not.toBeNull();
    expect(mark?.tagName.toLowerCase()).toBe("svg");
  });

  it("calls usePendingGatesSubscription for app-wide WebSocket subscription", () => {
    renderWithProviders(<Sidebar />);

    expect(mockUsePendingGatesSubscription).toHaveBeenCalled();
  });

  it("shows badge when there are pending gates", () => {
    mockUsePendingGatesCount.mockReturnValue({ data: { count: 3 } });

    renderWithProviders(<Sidebar />);

    expect(screen.getByText("3")).toBeInTheDocument();
  });

  it("does not show badge when pending count is zero", () => {
    mockUsePendingGatesCount.mockReturnValue({ data: { count: 0 } });

    renderWithProviders(<Sidebar />);

    expect(screen.queryByText("0")).not.toBeInTheDocument();
  });

  it("calls onNavigate when a nav link is clicked", async () => {
    const user = userEvent.setup();
    const onNavigate = vi.fn();

    renderWithProviders(<Sidebar onNavigate={onNavigate} />);

    await user.click(screen.getByText("Runs"));
    expect(onNavigate).toHaveBeenCalledTimes(1);
  });

  it("does not error when onNavigate is undefined", async () => {
    const user = userEvent.setup();

    renderWithProviders(<Sidebar />);

    // Should not throw when clicking without onNavigate
    await user.click(screen.getByText("Runs"));
    expect(screen.getByText("Runs")).toBeInTheDocument();
  });

  it("hides injected platformAdminOnly nav item when not platform admin", () => {
    mockUseAuth.mockReturnValue({
      ...baseAuth,
      platformAdmin: false,
      actingAsPlatformAdmin: false,
      organizationId: "test-org-id-123",
    });

    renderWithProviders(withNav(<Sidebar />));

    expect(screen.queryByText("Organizations")).not.toBeInTheDocument();
  });

  it("shows injected Organizations item when acting as platform admin", () => {
    mockUseAuth.mockReturnValue({
      ...baseAuth,
      platformAdmin: true,
      actingAsPlatformAdmin: true,
      organizationSlug: "system",
    });

    renderWithProviders(withNav(<Sidebar />));

    expect(screen.getByText("Organizations")).toBeInTheDocument();
  });

  it("hides Organizations when platform admin has switched working org away from system", () => {
    // actingAsPlatformAdmin gates the platformAdminOnly item; My Organization (a plain
    // injected item with a per-org `to`) still resolves for the current org.
    mockUseAuth.mockReturnValue({
      ...baseAuth,
      platformAdmin: true,
      actingAsPlatformAdmin: false,
      organizationId: "new-org-id",
      organizationSlug: "acme-corp",
    });

    renderWithProviders(withNav(<Sidebar />));

    expect(screen.queryByText("Organizations")).not.toBeInTheDocument();
    expect(screen.getByText("My Organization")).toBeInTheDocument();
  });

  it("does not render an ADMIN section header", () => {
    mockUseAuth.mockReturnValue({ ...baseAuth, platformAdmin: true, actingAsPlatformAdmin: true });

    renderWithProviders(withNav(<Sidebar />));

    expect(screen.queryByText("Admin")).not.toBeInTheDocument();
    expect(screen.queryByText("ADMIN")).not.toBeInTheDocument();
  });

  // --- injected My Organization link (dynamic per-org `to`) ---

  it("resolves the injected My Organization link to the per-org path", () => {
    mockUseAuth.mockReturnValue({
      ...baseAuth,
      organizationId: "test-org-id-123",
    });

    renderWithProviders(withNav(<Sidebar />));

    // Uses the explicit testId (not the generic `nav-my organization`) so e2e selectors stay stable.
    const link = screen.getByTestId("nav-my-organization");
    expect(link).toHaveAttribute("href", "/organizations/test-org-id-123");
  });

  it("drops the injected My Organization link when its `to` resolves to undefined (no org)", () => {
    mockUseAuth.mockReturnValue({ ...baseAuth, organizationId: undefined });

    renderWithProviders(withNav(<Sidebar />));

    expect(screen.queryByText("My Organization")).not.toBeInTheDocument();
  });

  it("renders no org nav items in OSS (nothing injected)", () => {
    mockUseAuth.mockReturnValue({
      ...baseAuth,
      platformAdmin: true,
      actingAsPlatformAdmin: true,
      organizationId: "test-org-id-123",
    });

    renderWithProviders(<Sidebar />);

    expect(screen.queryByText("Organizations")).not.toBeInTheDocument();
    expect(screen.queryByText("My Organization")).not.toBeInTheDocument();
  });

  // --- Role badge ---

  it("shows role badge with correct text when auth is enabled", () => {
    mockIsAuthEnabled.mockReturnValue(true);
    mockUseAuth.mockReturnValue({
      ...baseAuth,
      authenticated: true,
      username: "jdoe",
      role: "operator",
    });

    renderWithProviders(<Sidebar />);

    const badge = screen.getByTestId("sidebar-role-badge");
    expect(badge).toBeInTheDocument();
    expect(badge).toHaveTextContent("Operator");
  });

  it("does not show role badge when auth is disabled", () => {
    mockIsAuthEnabled.mockReturnValue(false);
    mockUseAuth.mockReturnValue({
      ...baseAuth,
      authenticated: true,
      username: "jdoe",
      role: "org-admin",
    });

    renderWithProviders(<Sidebar />);

    expect(screen.queryByTestId("sidebar-role-badge")).not.toBeInTheDocument();
  });

  it("shows admin badge with default variant", () => {
    mockIsAuthEnabled.mockReturnValue(true);
    mockUseAuth.mockReturnValue({
      ...baseAuth,
      authenticated: true,
      username: "admin-user",
      role: "org-admin",
    });

    renderWithProviders(<Sidebar />);

    const badge = screen.getByTestId("sidebar-role-badge");
    expect(badge).toHaveTextContent("Admin");
  });

  it("shows viewer badge with outline variant", () => {
    mockIsAuthEnabled.mockReturnValue(true);
    mockUseAuth.mockReturnValue({
      ...baseAuth,
      authenticated: true,
      username: "viewer-user",
      role: "viewer",
    });

    renderWithProviders(<Sidebar />);

    const badge = screen.getByTestId("sidebar-role-badge");
    expect(badge).toHaveTextContent("Viewer");
  });

  // --- Active-org subtitle ---

  it("shows the active organization slug under the ChorusKube brand", () => {
    mockUseAuth.mockReturnValue({
      ...baseAuth,
      authenticated: true,
      organizationId: "org-1",
      organizationSlug: "acme-corp",
    });

    renderWithProviders(<Sidebar />);

    expect(screen.getByTestId("sidebar-active-org")).toHaveTextContent("acme-corp");
  });

  it("hides the active org subtitle when no organizationSlug is available", () => {
    mockUseAuth.mockReturnValue({ ...baseAuth, organizationSlug: undefined });

    renderWithProviders(<Sidebar />);

    expect(screen.queryByTestId("sidebar-active-org")).not.toBeInTheDocument();
  });

  // --- Workspace switcher (memberships > 1) ---

  it("renders a static slug (no dropdown trigger) when memberships has a single entry", () => {
    mockUseAuth.mockReturnValue({
      ...baseAuth,
      organizationId: "org-1",
      organizationSlug: "acme-corp",
      memberships: [{ id: "org-1", slug: "acme-corp", displayName: "Acme Corp" }],
    });

    renderWithProviders(<Sidebar />);

    expect(screen.getByTestId("sidebar-active-org")).toHaveTextContent("acme-corp");
    expect(screen.queryByTestId("sidebar-org-switcher-trigger")).not.toBeInTheDocument();
  });

  it("renders the workspace switcher trigger when memberships has more than one entry", () => {
    mockUseAuth.mockReturnValue({
      ...baseAuth,
      organizationId: "org-1",
      organizationSlug: "acme-corp",
      memberships: [
        { id: "org-1", slug: "acme-corp", displayName: "Acme Corp" },
        { id: "org-2", slug: "beta-co", displayName: "Beta Co" },
      ],
    });

    renderWithProviders(<Sidebar />);

    const trigger = screen.getByTestId("sidebar-org-switcher-trigger");
    expect(trigger).toBeInTheDocument();
    // The active slug is still present inside the trigger.
    expect(screen.getByTestId("sidebar-active-org")).toHaveTextContent("acme-corp");
  });

  it("opens a menu listing memberships when the switcher trigger is clicked", async () => {
    const user = userEvent.setup();
    mockUseAuth.mockReturnValue({
      ...baseAuth,
      organizationId: "org-1",
      organizationSlug: "acme-corp",
      memberships: [
        { id: "org-1", slug: "acme-corp", displayName: "Acme Corp" },
        { id: "org-2", slug: "beta-co", displayName: "Beta Co" },
      ],
    });

    renderWithProviders(<Sidebar />);

    expect(screen.queryByTestId("sidebar-org-switcher-menu")).not.toBeInTheDocument();
    await user.click(screen.getByTestId("sidebar-org-switcher-trigger"));

    expect(screen.getByTestId("sidebar-org-switcher-menu")).toBeInTheDocument();
    expect(screen.getByTestId("sidebar-org-switcher-item-acme-corp")).toBeInTheDocument();
    expect(screen.getByTestId("sidebar-org-switcher-item-beta-co")).toBeInTheDocument();
  });

  it("clicking a non-active membership triggers silent re-auth with the right scope", async () => {
    const user = userEvent.setup();
    mockLogin.mockReset();
    mockLogin.mockResolvedValue(undefined);
    mockUseAuth.mockReturnValue({
      ...baseAuth,
      organizationId: "org-1",
      organizationSlug: "acme-corp",
      memberships: [
        { id: "org-1", slug: "acme-corp", displayName: "Acme Corp" },
        { id: "org-2", slug: "beta-co", displayName: "Beta Co" },
      ],
    });

    renderWithProviders(<Sidebar />);

    await user.click(screen.getByTestId("sidebar-org-switcher-trigger"));
    await user.click(screen.getByTestId("sidebar-org-switcher-item-beta-co"));

    // switchActiveOrg delegates to the login() verb with silent:true; the verb owns
    // the prompt=none → interactive fallback (covered in lib/__tests__/oidc.test.ts).
    expect(mockLogin).toHaveBeenCalledTimes(1);
    expect(mockLogin).toHaveBeenCalledWith(
      expect.objectContaining({
        scope: "openid organization:beta-co",
        silent: true,
      }),
    );
  });

  // --- SaaS nudge footer ---

  it("renders the SaaS nudge link to ChorusKube Cloud opening in a new tab", () => {
    renderWithProviders(<Sidebar />);

    const nudge = screen.getByTestId("sidebar-saas-nudge");
    expect(nudge).toBeInTheDocument();
    expect(nudge).toHaveAttribute(
      "href",
      "https://choruskube.com/?utm_source=oss&utm_medium=webui&utm_campaign=sidebar-nudge",
    );
    expect(nudge).toHaveAttribute("target", "_blank");
    expect(nudge).toHaveAttribute("rel", expect.stringContaining("noopener"));
    expect(nudge).toHaveTextContent("Try ChorusKube Cloud");
  });

  it("hides the SaaS nudge when an extension sets showSaaSNudge=false", () => {
    renderWithProviders(
      <ExtensionsProvider value={{ showSaaSNudge: false }}>
        <Sidebar />
      </ExtensionsProvider>,
    );

    expect(screen.queryByTestId("sidebar-saas-nudge")).not.toBeInTheDocument();
  });

  it("clicking the active membership does NOT trigger re-auth", async () => {
    const user = userEvent.setup();
    mockLogin.mockReset();
    mockUseAuth.mockReturnValue({
      ...baseAuth,
      organizationId: "org-1",
      organizationSlug: "acme-corp",
      memberships: [
        { id: "org-1", slug: "acme-corp", displayName: "Acme Corp" },
        { id: "org-2", slug: "beta-co", displayName: "Beta Co" },
      ],
    });

    renderWithProviders(<Sidebar />);

    await user.click(screen.getByTestId("sidebar-org-switcher-trigger"));
    await user.click(screen.getByTestId("sidebar-org-switcher-item-acme-corp"));

    expect(mockLogin).not.toHaveBeenCalled();
  });
});
