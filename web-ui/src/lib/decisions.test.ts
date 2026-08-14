import { describe, expect, it } from "vitest";
import { parseEscalationCategory, parseGateTrigger } from "./decisions";

describe("parseGateTrigger", () => {
  it("returns approved for the approved decision", () => {
    expect(parseGateTrigger("approved")).toEqual({ kind: "approved" });
  });
  it("returns review_conflict for the review_conflict suffix", () => {
    expect(parseGateTrigger("need_human_decision:review_conflict")).toEqual({ kind: "review_conflict" });
  });
  it("returns uncertainty for the uncertainty suffix", () => {
    expect(parseGateTrigger("need_human_decision:uncertainty")).toEqual({ kind: "uncertainty" });
  });
  it("returns alternative_proposal for the alternative_proposal suffix", () => {
    expect(parseGateTrigger("need_human_decision:alternative_proposal")).toEqual({ kind: "alternative_proposal" });
  });
  it("falls back to uncertainty for bare need_human_decision (backwards-compat)", () => {
    expect(parseGateTrigger("need_human_decision")).toEqual({ kind: "uncertainty" });
  });
  it("treats null/empty as approved", () => {
    expect(parseGateTrigger(null)).toEqual({ kind: "approved" });
    expect(parseGateTrigger(undefined)).toEqual({ kind: "approved" });
  });
});

describe("parseEscalationCategory", () => {
  it("maps each escalation.md category to a trigger", () => {
    expect(parseEscalationCategory("review_conflict")).toEqual({ kind: "review_conflict" });
    expect(parseEscalationCategory("uncertainty")).toEqual({ kind: "uncertainty" });
    expect(parseEscalationCategory("alternative_proposal")).toEqual({ kind: "alternative_proposal" });
    expect(parseEscalationCategory("environment")).toEqual({ kind: "environment" });
    expect(parseEscalationCategory("blocked_external")).toEqual({ kind: "blocked_external" });
  });

  it("degrades an unknown or absent category to approved (no banner)", () => {
    expect(parseEscalationCategory("nonsense")).toEqual({ kind: "approved" });
    expect(parseEscalationCategory(null)).toEqual({ kind: "approved" });
  });
});

describe("parseGateTrigger (legacy v36 runs)", () => {
  it("still parses frozen need_human_decision decisions", () => {
    expect(parseGateTrigger("need_human_decision:review_conflict")).toEqual({ kind: "review_conflict" });
  });
});
