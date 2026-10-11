import { describe, it, expect } from "vitest";
import { decisionToastMessage, latestStampFor, pendingStamp } from "../gateStamps";
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
    parkedNodeExecutionId: null,
    createdAt: "2026-10-11T00:00:00Z",
    updatedAt: "2026-10-11T00:00:00Z",
    ...overrides,
  };
}

describe("decisionToastMessage", () => {
  it("says the decision was submitted when there is no response body", () => {
    expect(decisionToastMessage(undefined)).toBe("Decision submitted");
  });

  it("says the decision was submitted for a signaled outcome", () => {
    expect(decisionToastMessage({ outcome: "signaled", staleRepos: [] })).toBe("Decision submitted");
  });

  it("says the approval is being finalized when the server accepted it for finalizing", () => {
    expect(decisionToastMessage({ outcome: "finalizing", staleRepos: [] })).toBe("Finalizing your approval…");
  });

  it("names every stale repo when the approval is held", () => {
    const message = decisionToastMessage({
      outcome: "held_for_recheck",
      staleRepos: [
        { repo: "acme/app", detail: "2 commits behind main" },
        { repo: "acme/web", detail: "1 commit behind main" },
      ],
    });
    expect(message).toContain("Approval held");
    expect(message).toContain("acme/app, acme/web");
    expect(message).toContain("proceeds automatically");
  });

  it("still explains the hold when the server names no repo", () => {
    const message = decisionToastMessage({ outcome: "held_for_recheck", staleRepos: [] });
    expect(message).toContain("Approval held");
    expect(message).toContain("this run's repos");
  });
});

describe("latestStampFor", () => {
  it("returns null when there are no stamps", () => {
    expect(latestStampFor(undefined, "final_approval")).toBeNull();
    expect(latestStampFor([], "final_approval")).toBeNull();
  });

  it("returns the newest stamp for the gate and ignores other gates", () => {
    const older = makeStamp({ id: "a", status: "voided" });
    const otherGate = makeStamp({ id: "b", gateLabel: "approve_spec_and_plan" });
    const newer = makeStamp({ id: "c", status: "pending" });
    expect(latestStampFor([older, otherGate, newer], "final_approval")?.id).toBe("c");
  });

  it("returns null when only other gates have stamps", () => {
    expect(latestStampFor([makeStamp({ gateLabel: "approve_spec_and_plan" })], "final_approval")).toBeNull();
  });
});

describe("pendingStamp", () => {
  it("returns null when there are no stamps", () => {
    expect(pendingStamp(undefined)).toBeNull();
  });

  it("returns null when no stamp is pending", () => {
    expect(pendingStamp([makeStamp({ status: "consumed" }), makeStamp({ status: "voided" })])).toBeNull();
  });

  it("returns null once the run has ended", () => {
    const pending = makeStamp({ status: "pending" });
    expect(pendingStamp([pending], "cancelled")).toBeNull();
    expect(pendingStamp([pending], "failed")).toBeNull();
    expect(pendingStamp([pending], "completed")).toBeNull();
    expect(pendingStamp([pending], "running")).toBe(pending);
  });

  it("returns the pending stamp from any gate", () => {
    const consumed = makeStamp({ id: "a", status: "consumed" });
    const pending = makeStamp({ id: "b", gateLabel: "approve_spec_and_plan", status: "pending" });
    expect(pendingStamp([consumed, pending])?.id).toBe("b");
  });
});
