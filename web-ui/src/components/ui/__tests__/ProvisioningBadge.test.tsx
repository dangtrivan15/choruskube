import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import ProvisioningBadge from "../ProvisioningBadge";
import type { ProvisioningStatus } from "@/lib/types";

describe("ProvisioningBadge", () => {
  const CASES: [ProvisioningStatus, string, string][] = [
    ["pending", "Pending", "status-neutral"],
    ["provisioning", "Provisioning", "status-info"],
    ["ready", "Ready", "status-success"],
    ["failed", "Failed", "status-error"],
  ];

  it.each(CASES)("%s renders %s in its token", (status, label, token) => {
    render(<ProvisioningBadge status={status} />);
    const el = screen.getByText(label);
    expect(el.className).toContain(token);
  });

  it("an unknown status renders Pending in neutral", () => {
    render(<ProvisioningBadge status={"unknown" as ProvisioningStatus} />);
    const el = screen.getByText("Pending");
    expect(el.className).toContain("status-neutral");
  });
});
