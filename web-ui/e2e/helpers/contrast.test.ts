import { describe, it, expect } from "vitest";
import { compositeStack, contrastRatio } from "./contrast";

describe("compositeStack", () => {
  it("returns the base unchanged for an empty layer stack", () => {
    expect(compositeStack([], "#faf4ed")).toBe("#faf4ed");
  });

  it("matches straight alpha compositing for a single translucent layer", () => {
    // 50% red over white -> #ff8080
    expect(compositeStack([{ rgb: [255, 0, 0], alpha: 0.5 }], "#ffffff")).toBe("#ff8080");
  });

  it("stacks three translucent layers in paint order (first layer painted first)", () => {
    // Painting order matters: layer A (bottom) then B then C (top, nearest the
    // element) — reversing the array must change the result whenever the
    // layers' colors differ, proving order isn't silently ignored.
    const layers: { rgb: [number, number, number]; alpha: number }[] = [
      { rgb: [255, 0, 0], alpha: 0.4 }, // painted first (furthest ancestor)
      { rgb: [0, 255, 0], alpha: 0.4 },
      { rgb: [0, 0, 255], alpha: 0.4 }, // painted last (nearest the element)
    ];
    const forward = compositeStack(layers, "#000000");
    const reversed = compositeStack([...layers].reverse(), "#000000");
    expect(forward).not.toBe(reversed);
  });

  it("a fully opaque top layer makes earlier layers and the base irrelevant", () => {
    const withRedBase = compositeStack(
      [
        { rgb: [0, 255, 0], alpha: 0.5 },
        { rgb: [10, 20, 30], alpha: 1 },
      ],
      "#ff0000",
    );
    const withBlueBase = compositeStack(
      [
        { rgb: [0, 255, 0], alpha: 0.5 },
        { rgb: [10, 20, 30], alpha: 1 },
      ],
      "#0000ff",
    );
    expect(withRedBase).toBe(withBlueBase);
    expect(withRedBase).toBe("#0a141e");
  });

  it("is associative with manual two-step compositing for a two-layer stack", () => {
    const stacked = compositeStack(
      [
        { rgb: [255, 0, 0], alpha: 0.3 },
        { rgb: [0, 0, 255], alpha: 0.6 },
      ],
      "#00ff00",
    );
    // Manually: first red-over-green, then blue-over-that.
    const step1 = compositeStack([{ rgb: [255, 0, 0], alpha: 0.3 }], "#00ff00");
    const step2 = compositeStack([{ rgb: [0, 0, 255], alpha: 0.6 }], step1);
    expect(stacked).toBe(step2);
  });
});

describe("contrastRatio re-export sanity", () => {
  it("black on white is ~21:1", () => {
    expect(contrastRatio("#000000", "#ffffff")).toBeCloseTo(21, 0);
  });
});
