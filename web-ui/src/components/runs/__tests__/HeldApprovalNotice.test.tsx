import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import HeldApprovalNotice from "../HeldApprovalNotice";
import type { GateStampResponse } from "@/lib/types";

function makeStamp(overrides: Partial<GateStampResponse> = {}): GateStampResponse {
  return {
    id: "stamp-1",
    gateTemplateNodeId: "node-gate",
    gateLabel: "final_approval",
    status: "pending",
    recheckRounds: 1,
    maxRecheckRounds: 2,
    voidReason: null,
    supersededByReviewer: false,
    createdAt: "2026-10-11T00:00:00Z",
    updatedAt: "2026-10-11T00:00:00Z",
    ...overrides,
  };
}

describe("HeldApprovalNotice", () => {
  it("renders nothing without a stamp", () => {
    const { container } = render(<HeldApprovalNotice stamp={null} />);
    expect(container.firstChild).toBeNull();
  });

  it("shows a pending approval with its re-check round", () => {
    render(<HeldApprovalNotice stamp={makeStamp({ recheckRounds: 1, maxRecheckRounds: 2 })} />);
    const notice = screen.getByTestId("gate-held-notice");
    expect(notice).toHaveTextContent("Your approval is held");
    expect(notice).toHaveTextContent("round 1 of 2");
  });

  it("shows why a voided approval was not applied", () => {
    render(
      <HeldApprovalNotice
        stamp={makeStamp({ status: "voided", voidReason: "The re-check ran out of rounds." })}
      />,
    );
    const notice = screen.getByTestId("gate-held-voided-notice");
    expect(notice).toHaveTextContent("A held approval was not applied: The re-check ran out of rounds.");
    expect(notice).toHaveTextContent("Decide again.");
    expect(notice).toHaveAttribute("data-tone", "warning");
  });

  it("renders nothing when a reviewer's own decision replaced the held approval", () => {
    const { container } = render(
      <HeldApprovalNotice
        stamp={makeStamp({ status: "voided", supersededByReviewer: true, voidReason: "A reviewer decided." })}
      />,
    );
    expect(container.firstChild).toBeNull();
  });

  it("renders nothing once the held approval was applied", () => {
    const { container } = render(<HeldApprovalNotice stamp={makeStamp({ status: "consumed" })} />);
    expect(container.firstChild).toBeNull();
  });
});
