import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook } from "@testing-library/react";
import type { ReactNode } from "react";
import { useVisibleCommands } from "@/hooks/useVisibleCommands";
import { useAuth } from "@/components/AuthProvider";
import { ExtensionsProvider } from "@/ExtensionsContext";
import type { Command } from "@/lib/commands";

vi.mock("@/components/AuthProvider", () => ({
  useAuth: vi.fn(),
}));

const mockUseAuth = useAuth as ReturnType<typeof vi.fn>;

const baseAuth = {
  authenticated: true,
  token: "t",
  username: "u",
  roles: [],
  userId: "u-1",
  activeOrg: null,
  memberships: [],
  organizationId: undefined as string | undefined,
  organizationSlug: undefined,
  role: null,
  platformAdmin: false,
  actingAsPlatformAdmin: false,
  onboardingCompleted: true,
  logout: () => {},
};

// Mirrors what an extension entrypoint injects: an org-gated link and a platform-admin link.
const injectedCommands: Command[] = [
  {
    id: "nav:my-organization",
    label: "Go to My Organization",
    category: "navigation",
    shortcut: "g o",
    visibleWhen: (a) => !!a.organizationId,
    resolvePath: ({ organizationId }) => (organizationId ? `/organizations/${organizationId}` : undefined),
  },
  {
    id: "nav:admin-organizations",
    label: "Go to Organizations",
    category: "navigation",
    shortcut: "g O",
    visibleWhen: (a) => a.actingAsPlatformAdmin,
    resolvePath: () => "/admin/organizations",
  },
];

function withExtensions(commands?: Command[]) {
  return ({ children }: { children: ReactNode }) => (
    <ExtensionsProvider value={commands ? { commands } : {}}>{children}</ExtensionsProvider>
  );
}

describe("useVisibleCommands", () => {
  beforeEach(() => {
    mockUseAuth.mockReset();
  });

  it("never exposes extension commands when none are injected (OSS)", () => {
    mockUseAuth.mockReturnValue({ ...baseAuth, organizationId: "o-1", actingAsPlatformAdmin: true });
    const { result } = renderHook(() => useVisibleCommands(), { wrapper: withExtensions() });
    expect(result.current.find((c) => c.id === "nav:my-organization")).toBeUndefined();
    expect(result.current.find((c) => c.id === "nav:admin-organizations")).toBeUndefined();
  });

  it("filters injected commands by visibleWhen — org-only user", () => {
    mockUseAuth.mockReturnValue({ ...baseAuth, organizationId: "o-1" });
    const { result } = renderHook(() => useVisibleCommands(), {
      wrapper: withExtensions(injectedCommands),
    });
    expect(result.current.find((c) => c.id === "nav:my-organization")).toBeDefined();
    expect(result.current.find((c) => c.id === "nav:admin-organizations")).toBeUndefined();
  });

  it("shows both injected commands for an acting platform admin", () => {
    mockUseAuth.mockReturnValue({
      ...baseAuth,
      organizationId: "o-1",
      platformAdmin: true,
      actingAsPlatformAdmin: true,
    });
    const { result } = renderHook(() => useVisibleCommands(), {
      wrapper: withExtensions(injectedCommands),
    });
    expect(result.current.find((c) => c.id === "nav:my-organization")).toBeDefined();
    expect(result.current.find((c) => c.id === "nav:admin-organizations")).toBeDefined();
  });

  it("always exposes core commands without a visibleWhen predicate", () => {
    mockUseAuth.mockReturnValue({ ...baseAuth });
    const { result } = renderHook(() => useVisibleCommands(), { wrapper: withExtensions() });
    expect(result.current.find((c) => c.id === "nav:runs")).toBeDefined();
    expect(result.current.find((c) => c.id === "action:toggle-theme")).toBeDefined();
  });
});
