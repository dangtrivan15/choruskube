import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";

vi.mock("@/lib/oidc", () => ({
  isAuthEnabled: vi.fn(() => true),
  logout: vi.fn().mockResolvedValue(undefined),
}));

import { OrgErrorBoundary } from "@/components/OrgErrorBoundary";

function ThrowingChild({ message }: { message: string }): never {
  throw new Error(message);
}

function GoodChild() {
  return <div data-testid="child">OK</div>;
}

describe("OrgErrorBoundary", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // Suppress console.error noise from React error boundary logging
    vi.spyOn(console, "error").mockImplementation(() => {});
  });

  it("renders children normally when no error", () => {
    render(
      <OrgErrorBoundary>
        <GoodChild />
      </OrgErrorBoundary>,
    );
    expect(screen.getByTestId("child")).toHaveTextContent("OK");
  });

  it("catches thrown error and renders error UI with message", () => {
    render(
      <OrgErrorBoundary>
        <ThrowingChild message="Organization context unavailable" />
      </OrgErrorBoundary>,
    );
    expect(screen.getByText("Organization Error")).toBeInTheDocument();
    expect(
      screen.getByText("Organization context unavailable"),
    ).toBeInTheDocument();
    expect(screen.getByText("Retry")).toBeInTheDocument();
    expect(screen.getByText("Logout")).toBeInTheDocument();
  });

  it("clicking Logout invokes the oidc logout verb", async () => {
    const { logout } = await import("@/lib/oidc");

    render(
      <OrgErrorBoundary>
        <ThrowingChild message="boom" />
      </OrgErrorBoundary>,
    );

    fireEvent.click(screen.getByText("Logout"));
    expect(logout).toHaveBeenCalled();
  });

  it("hides Logout button when auth is disabled", async () => {
    const { isAuthEnabled } = await import("@/lib/oidc");
    vi.mocked(isAuthEnabled).mockReturnValue(false);

    render(
      <OrgErrorBoundary>
        <ThrowingChild message="test error" />
      </OrgErrorBoundary>,
    );
    expect(screen.getByText("Retry")).toBeInTheDocument();
    expect(screen.queryByText("Logout")).not.toBeInTheDocument();
  });
});
