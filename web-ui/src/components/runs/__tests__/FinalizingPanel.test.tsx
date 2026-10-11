import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import FinalizingPanel from "../FinalizingPanel";
import type { GateStampResponse } from "@/lib/types";

function makeStamp(overrides: Partial<GateStampResponse> = {}): GateStampResponse {
  return {
    id: "stamp-1",
    gateTemplateNodeId: "node-gate",
    gateLabel: "final_approval",
    status: "finalizing",
    recheckRounds: 0,
    maxRecheckRounds: 2,
    voidReason: null,
    supersededByReviewer: false,
    parkedNodeExecutionId: null,
    createdAt: "2026-10-11T00:00:00Z",
    updatedAt: "2026-10-11T00:00:00Z",
    ...overrides,
  };
}

describe("FinalizingPanel", () => {
  it("describes the finalizing steps without a stamp", () => {
    render(<FinalizingPanel stamp={null} />);
    const panel = screen.getByTestId("gate-finalizing");
    expect(panel).toHaveTextContent("Finalizing the approval");
    expect(panel).toHaveTextContent("checking against current main and merging the pull requests");
    expect(panel).not.toHaveTextContent("Re-check round");
    expect(panel).toHaveAttribute("data-tone", "info");
  });

  it("omits the round before any re-check has run", () => {
    render(<FinalizingPanel stamp={makeStamp({ recheckRounds: 0 })} />);
    expect(screen.getByTestId("gate-finalizing")).not.toHaveTextContent("Re-check round");
  });

  it("shows the re-check round once one is known", () => {
    render(<FinalizingPanel stamp={makeStamp({ recheckRounds: 1, maxRecheckRounds: 2 })} />);
    expect(screen.getByTestId("gate-finalizing")).toHaveTextContent("Re-check round 1 of 2.");
  });
});
