import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook } from "@testing-library/react";
import { createTestHookWrapper } from "@/__tests__/test-utils";

vi.mock("@/components/AuthProvider", () => ({
  useAuth: vi.fn(() => ({ ...baseAuth })),
}));

vi.mock("@/lib/oidc", () => ({
  isAuthEnabled: vi.fn(() => true),
}));

import { useAuth } from "@/components/AuthProvider";
import { isAuthEnabled } from "@/lib/oidc";
import { usePermission } from "@/hooks/usePermission";

const mockUseAuth = useAuth as ReturnType<typeof vi.fn>;
const mockIsAuthEnabled = isAuthEnabled as ReturnType<typeof vi.fn>;

const baseAuth = {
  authenticated: true,
  token: "test-token",
  username: "testuser",
  roles: [],
  organizationId: "org-1",
  organizationSlug: "test-org",
  role: null as string | null,
  platformAdmin: false,
  logout: vi.fn(),
};

describe("usePermission", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockIsAuthEnabled.mockReturnValue(true);
    mockUseAuth.mockReturnValue({ ...baseAuth });
  });

  it("returns all-true when auth is disabled (dev mode)", () => {
    mockIsAuthEnabled.mockReturnValue(false);
    const { wrapper } = createTestHookWrapper();
    const { result } = renderHook(() => usePermission(), { wrapper });

    expect(result.current).toEqual({
      canRead: true,
      canOperate: true,
      canAdmin: true,
      platformAdmin: true,
    });
  });

  it("returns all-true when role is admin", () => {
    mockUseAuth.mockReturnValue({ ...baseAuth, role: "org-admin" });
    const { wrapper } = createTestHookWrapper();
    const { result } = renderHook(() => usePermission(), { wrapper });

    expect(result.current).toEqual({
      canRead: true,
      canOperate: true,
      canAdmin: true,
      platformAdmin: false,
    });
  });

  it("returns canRead and canOperate for operator role", () => {
    mockUseAuth.mockReturnValue({ ...baseAuth, role: "operator" });
    const { wrapper } = createTestHookWrapper();
    const { result } = renderHook(() => usePermission(), { wrapper });

    expect(result.current).toEqual({
      canRead: true,
      canOperate: true,
      canAdmin: false,
      platformAdmin: false,
    });
  });

  it("returns only canRead for viewer role", () => {
    mockUseAuth.mockReturnValue({ ...baseAuth, role: "viewer" });
    const { wrapper } = createTestHookWrapper();
    const { result } = renderHook(() => usePermission(), { wrapper });

    expect(result.current).toEqual({
      canRead: true,
      canOperate: false,
      canAdmin: false,
      platformAdmin: false,
    });
  });

  it("returns all-false when role is null (unauthenticated)", () => {
    mockUseAuth.mockReturnValue({ ...baseAuth, role: null });
    const { wrapper } = createTestHookWrapper();
    const { result } = renderHook(() => usePermission(), { wrapper });

    expect(result.current).toEqual({
      canRead: false,
      canOperate: false,
      canAdmin: false,
      platformAdmin: false,
    });
  });

  it("returns all-false when role is an unknown value", () => {
    mockUseAuth.mockReturnValue({ ...baseAuth, role: "unknown_role" });
    const { wrapper } = createTestHookWrapper();
    const { result } = renderHook(() => usePermission(), { wrapper });

    expect(result.current).toEqual({
      canRead: false,
      canOperate: false,
      canAdmin: false,
      platformAdmin: false,
    });
  });

  it("propagates platformAdmin from useAuth when admin role is set", () => {
    mockUseAuth.mockReturnValue({ ...baseAuth, role: "org-admin", platformAdmin: true });
    const { wrapper } = createTestHookWrapper();
    const { result } = renderHook(() => usePermission(), { wrapper });

    expect(result.current.platformAdmin).toBe(true);
    expect(result.current.canAdmin).toBe(true);
  });
});
