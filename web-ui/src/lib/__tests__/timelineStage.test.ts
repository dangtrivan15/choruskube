import { describe, it, expect } from "vitest";
import { STAGE_TOKEN_MAP, stageColors, stageTone } from "../timelineStage";
import { statusColorTokens } from "../statusColors";

describe("stageColors", () => {
  it("maps every known stage in STAGE_TOKEN_MAP to its corresponding status token classes", () => {
    for (const [stage, token] of Object.entries(STAGE_TOKEN_MAP)) {
      const expected = statusColorTokens(token);
      expect(stageColors(stage)).toEqual({
        bg: expected.tint,
        border: expected.borderSoft,
        text: expected.text,
      });
    }
  });

  it("falls through to statusColorTokens's default for an unknown stage (parity with the pre-extraction behavior)", () => {
    const expected = statusColorTokens("some-unknown-stage");
    expect(stageColors("some-unknown-stage")).toEqual({
      bg: expected.tint,
      border: expected.borderSoft,
      text: expected.text,
    });
  });
});

describe("stageTone", () => {
  it("maps each board stage to the tone its status token carries, falling back to neutral", () => {
    expect(stageTone("backlog")).toBe("neutral");
    expect(stageTone("in_progress")).toBe("info");
    expect(stageTone("rolled_out")).toBe("success");
    expect(stageTone("some-unknown-stage")).toBe("neutral");
  });
});
