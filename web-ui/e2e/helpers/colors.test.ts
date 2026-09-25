import { describe, it, expect } from "vitest";
import { alphaOf } from "./colors";

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
