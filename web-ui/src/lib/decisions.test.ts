import { describe, expect, it } from "vitest";
import { parseGateTrigger } from "./decisions";

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
