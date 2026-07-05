import { render, screen } from "@testing-library/react";
import { describe, it, expect } from "vitest";
import CredentialHealthBadge from "../CredentialHealthBadge";

describe("CredentialHealthBadge", () => {
  it("renders Valid badge with success color for VALID status", () => {
    render(<CredentialHealthBadge status="VALID" />);
    const badge = screen.getByText("Valid");
    expect(badge).toBeInTheDocument();
    expect(badge.className).toContain("status-success");
  });

  it("renders Expired badge with error color for EXPIRED status", () => {
    render(<CredentialHealthBadge status="EXPIRED" />);
    const badge = screen.getByText("Expired");
    expect(badge).toBeInTheDocument();
    expect(badge.className).toContain("status-error");
  });

  it("renders Insufficient Permissions badge with error color for INSUFFICIENT_PERMISSIONS status", () => {
    render(<CredentialHealthBadge status="INSUFFICIENT_PERMISSIONS" />);
    const badge = screen.getByText("Insufficient Permissions");
    expect(badge).toBeInTheDocument();
    expect(badge.className).toContain("status-error");
  });

  it("renders Unreachable badge with warning color for UNREACHABLE status", () => {
    render(<CredentialHealthBadge status="UNREACHABLE" />);
    const badge = screen.getByText("Unreachable");
    expect(badge).toBeInTheDocument();
    expect(badge.className).toContain("status-warning");
  });

  it("renders nothing for null status", () => {
    const { container } = render(<CredentialHealthBadge status={null} />);
    expect(container.firstChild).toBeNull();
  });

  it("renders nothing for undefined status", () => {
    const { container } = render(<CredentialHealthBadge status={undefined} />);
    expect(container.firstChild).toBeNull();
  });

  it("renders nothing for unknown status value", () => {
    // Deliberately testing runtime robustness with a server-returned unexpected value
    const { container } = render(
      // @ts-expect-error intentionally passing an unknown value to test runtime guard
      <CredentialHealthBadge status="UNKNOWN_STATUS" />,
    );
    expect(container.firstChild).toBeNull();
  });
});
