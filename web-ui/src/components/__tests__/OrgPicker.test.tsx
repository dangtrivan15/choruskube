import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

vi.mock("@/lib/oidc", () => ({
  login: vi.fn().mockResolvedValue(undefined),
}));

import { OrgPicker } from "@/components/OrgPicker";
import { login } from "@/lib/oidc";
import type { OrgRef } from "@/lib/types";

const mockLogin = vi.mocked(login);

const TWO_ORGS: OrgRef[] = [
  { id: "org-1", slug: "alpha", displayName: "Alpha" },
  { id: "org-2", slug: "beta", displayName: "Beta Co" },
];

describe("OrgPicker", () => {
  beforeEach(() => {
    mockLogin.mockReset();
    mockLogin.mockResolvedValue(undefined);
  });

  it("renders one button per membership with a matching data-testid", () => {
    render(<OrgPicker memberships={TWO_ORGS} />);

    expect(screen.getByText("Choose your workspace")).toBeInTheDocument();
    expect(screen.getByTestId("org-picker-alpha")).toBeInTheDocument();
    expect(screen.getByTestId("org-picker-beta")).toBeInTheDocument();
    expect(screen.getByText("Alpha")).toBeInTheDocument();
    expect(screen.getByText("Beta Co")).toBeInTheDocument();
  });

  it("clicking a membership triggers a silent re-auth scoped to that org", async () => {
    const user = userEvent.setup();
    render(<OrgPicker memberships={TWO_ORGS} />);

    await user.click(screen.getByTestId("org-picker-alpha"));

    // The component delegates to the login() verb with silent:true; the verb owns
    // the prompt=none → interactive fallback (covered in lib/__tests__/oidc.test.ts).
    expect(mockLogin).toHaveBeenCalledTimes(1);
    expect(mockLogin).toHaveBeenCalledWith(
      expect.objectContaining({
        scope: "openid organization:alpha",
        redirectUri: window.location.href,
        silent: true,
      }),
    );
  });

  it("passes the picked org's slug through to the silent login scope", async () => {
    const user = userEvent.setup();
    render(<OrgPicker memberships={TWO_ORGS} />);

    await user.click(screen.getByTestId("org-picker-beta"));

    expect(mockLogin).toHaveBeenCalledTimes(1);
    expect(mockLogin).toHaveBeenCalledWith(
      expect.objectContaining({
        scope: "openid organization:beta",
        silent: true,
      }),
    );
  });
});
