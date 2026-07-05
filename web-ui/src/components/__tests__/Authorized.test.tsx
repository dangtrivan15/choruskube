import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/__tests__/test-utils";
import Authorized from "@/components/Authorized";

vi.mock("@/components/AuthProvider", () => ({
  useAuth: vi.fn(() => ({ ...baseAuth })),
}));

vi.mock("@/lib/oidc", () => ({
  isAuthEnabled: vi.fn(() => true),
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
  role: null as string | null,
  platformAdmin: false,
  logout: vi.fn(),
};

describe("Authorized", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockIsAuthEnabled.mockReturnValue(true);
    mockUseAuth.mockReturnValue({ ...baseAuth });
  });

  it("renders children when user has required permission (canOperate with operator)", () => {
    mockUseAuth.mockReturnValue({ ...baseAuth, role: "operator" });

    renderWithProviders(
      <Authorized require="canOperate">
        <button>Start Run</button>
      </Authorized>,
    );

    expect(screen.getByText("Start Run")).toBeInTheDocument();
  });

  it("does not render children when user lacks required permission (canAdmin with operator)", () => {
    mockUseAuth.mockReturnValue({ ...baseAuth, role: "operator" });

    renderWithProviders(
      <Authorized require="canAdmin">
        <button>Delete</button>
      </Authorized>,
    );

    expect(screen.queryByText("Delete")).not.toBeInTheDocument();
  });

  it("renders fallback when provided and user lacks permission", () => {
    mockUseAuth.mockReturnValue({ ...baseAuth, role: "viewer" });

    renderWithProviders(
      <Authorized require="canOperate" fallback={<span>Read only</span>}>
        <button>Edit</button>
      </Authorized>,
    );

    expect(screen.queryByText("Edit")).not.toBeInTheDocument();
    expect(screen.getByText("Read only")).toBeInTheDocument();
  });

  it("renders children for all permission levels when auth is disabled", () => {
    mockIsAuthEnabled.mockReturnValue(false);
    mockUseAuth.mockReturnValue({ ...baseAuth, role: null });

    renderWithProviders(
      <Authorized require="canAdmin">
        <button>Admin Action</button>
      </Authorized>,
    );

    expect(screen.getByText("Admin Action")).toBeInTheDocument();
  });

  it("renders children with canRead requirement for viewer role", () => {
    mockUseAuth.mockReturnValue({ ...baseAuth, role: "viewer" });

    renderWithProviders(
      <Authorized require="canRead">
        <span>Readable Content</span>
      </Authorized>,
    );

    expect(screen.getByText("Readable Content")).toBeInTheDocument();
  });

  it("hides children when user has no role (unauthenticated)", () => {
    mockUseAuth.mockReturnValue({ ...baseAuth, role: null });

    renderWithProviders(
      <Authorized require="canRead">
        <span>Protected</span>
      </Authorized>,
    );

    expect(screen.queryByText("Protected")).not.toBeInTheDocument();
  });

  it("renders nothing as default fallback when unauthorized", () => {
    mockUseAuth.mockReturnValue({ ...baseAuth, role: "viewer" });

    const { container } = renderWithProviders(
      <Authorized require="canAdmin">
        <button>Delete</button>
      </Authorized>,
    );

    expect(container.innerHTML).toBe("");
  });
});
