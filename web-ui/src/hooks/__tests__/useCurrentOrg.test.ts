import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook } from "@testing-library/react";
import { SYSTEM_ORG_ID } from "@/lib/constants";
const TEST_ORG_ID = "22222222-2222-2222-2222-222222222222";

// Create mocks before importing the module
const mockUseAuth = vi.fn();
const mockIsAuthEnabled = vi.fn();

vi.mock("@/components/AuthProvider", () => ({
  useAuth: () => mockUseAuth(),
}));

vi.mock("@/lib/oidc", () => ({
  isAuthEnabled: () => mockIsAuthEnabled(),
}));

import { useCurrentOrg } from "@/hooks/useCurrentOrg";

describe("useCurrentOrg", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("returns org UUID from auth context when auth is enabled", () => {
    mockIsAuthEnabled.mockReturnValue(true);
    mockUseAuth.mockReturnValue({ organizationId: TEST_ORG_ID });

    const { result } = renderHook(() => useCurrentOrg());

    expect(result.current).toBe(TEST_ORG_ID);
  });

  it("returns system org UUID when auth is disabled", () => {
    mockIsAuthEnabled.mockReturnValue(false);
    mockUseAuth.mockReturnValue({ organizationId: TEST_ORG_ID });

    const { result } = renderHook(() => useCurrentOrg());

    expect(result.current).toBe(SYSTEM_ORG_ID);
  });

  it("throws when auth context has no organizationId and auth is enabled", () => {
    mockIsAuthEnabled.mockReturnValue(true);
    mockUseAuth.mockReturnValue({ organizationId: undefined });

    expect(() => renderHook(() => useCurrentOrg())).toThrow(
      "Organization context unavailable",
    );
  });
});
