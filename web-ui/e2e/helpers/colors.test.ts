import { describe, it, expect } from "vitest";
import { alphaOf, contrastRatioRgb, ringColorOf } from "./colors";

describe("alphaOf", () => {
  it.each([
    ["rgb(1, 2, 3)", 1],
    ["rgba(1, 2, 3, 0.5)", 0.5],
    ["oklab(0.9 0.01 0.02)", 1],
    ["oklab(0.9 0.01 0.02 / 0.1)", 0.1],
    ["color(srgb 1 0 0 / 50%)", 0.5],
    ["transparent", 0],
  ])("%s -> %s", (color, expected) => {
    expect(alphaOf(color)).toBe(expected);
  });
});

describe("contrastRatioRgb", () => {
  it("black on white is 21:1", () => {
    expect(contrastRatioRgb("rgb(0, 0, 0)", "rgb(255, 255, 255)")).toBeCloseTo(21, 5);
  });

  it("throws on oklab(...)", () => {
    expect(() => contrastRatioRgb("oklab(0.9 0.01 0.02)", "rgb(255, 255, 255)")).toThrow();
  });

  it("throws on a translucent rgba() (alpha < 1)", () => {
    expect(() => contrastRatioRgb("rgba(1, 2, 3, 0.5)", "rgb(255, 255, 255)")).toThrow();
  });
});

describe("ringColorOf", () => {
  // Tailwind's computed box-shadow: several inert zero-spread placeholder
  // layers plus one real ring layer (here the 3px spread).
  const fiveLayerBoxShadow = (ringColor: string) =>
    [
      "rgba(0, 0, 0, 0) 0px 0px 0px 0px",
      "rgba(0, 0, 0, 0) 0px 0px 0px 0px",
      "rgba(0, 0, 0, 0) 0px 0px 0px 0px",
      `${ringColor} 0px 0px 0px 3px`,
      "rgba(0, 0, 0, 0) 0px 0px 0px 0px",
    ].join(", ");

  it("returns the non-zero-spread layer's color", () => {
    expect(ringColorOf(fiveLayerBoxShadow("rgb(104, 94, 135)"))).toBe("rgb(104, 94, 135)");
  });

  it("returns an oklab(... / alpha) ring color as-is, so a regression reports the alpha", () => {
    expect(ringColorOf(fiveLayerBoxShadow("oklab(64.876% 0.16469 -0.10037 / 0.5)"))).toBe(
      "oklab(64.876% 0.16469 -0.10037 / 0.5)",
    );
  });

  it("throws when every layer's spread is 0px", () => {
    const allZero = [
      "rgba(0, 0, 0, 0) 0px 0px 0px 0px",
      "rgba(0, 0, 0, 0) 0px 0px 0px 0px",
    ].join(", ");
    expect(() => ringColorOf(allZero)).toThrow();
  });

  it("does not split a comma inside the layer's own color function", () => {
    // If the comma inside rgba(...) were treated as a layer separator, this
    // would parse as garbage layers and either throw or misidentify the color.
    expect(ringColorOf(fiveLayerBoxShadow("rgba(104, 94, 135, 1)"))).toBe("rgba(104, 94, 135, 1)");
  });
});
