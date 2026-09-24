import { describe, it, expect } from "vitest";
import { ROADMAP_EDGE_STYLES } from "../roadmapEdgeStyles";

describe("ROADMAP_EDGE_STYLES", () => {
  it("has exactly the four dependency kinds", () => {
    expect(Object.keys(ROADMAP_EDGE_STYLES).sort()).toEqual(
      ["crossEpic", "dependency", "epicDependency", "hierarchy"].sort(),
    );
  });

  it.each([
    ["hierarchy", "--muted-foreground"],
    ["dependency", "--status-warning"],
    ["epicDependency", "--status-info"],
    ["crossEpic", "--status-accent"],
  ] as const)(
    "pins %s's token to %s — the single value every stroke, marker call site, and legend swatch reads",
    (kind, token) => {
      expect(ROADMAP_EDGE_STYLES[kind].token).toBe(token);
    },
  );

  it("every entry has a non-empty token and label, and a valid dashArray", () => {
    for (const style of Object.values(ROADMAP_EDGE_STYLES)) {
      expect(style.token.length).toBeGreaterThan(0);
      expect(style.token.startsWith("--")).toBe(true);
      expect(style.label.length).toBeGreaterThan(0);
      // A dash array is either "" (solid) or a whitespace-separated list of numbers.
      expect(style.dashArray === "" || /^\d+(\s\d+)*$/.test(style.dashArray)).toBe(true);
    }
  });

  it("hierarchy is the only solid (no dash pattern) kind", () => {
    expect(ROADMAP_EDGE_STYLES.hierarchy.dashArray).toBe("");
    expect(ROADMAP_EDGE_STYLES.dependency.dashArray).not.toBe("");
    expect(ROADMAP_EDGE_STYLES.epicDependency.dashArray).not.toBe("");
    expect(ROADMAP_EDGE_STYLES.crossEpic.dashArray).not.toBe("");
  });

  it("each of the three dependency kinds has a distinct dash array, so their lines never look identical", () => {
    const dashArrays = [
      ROADMAP_EDGE_STYLES.dependency.dashArray,
      ROADMAP_EDGE_STYLES.epicDependency.dashArray,
      ROADMAP_EDGE_STYLES.crossEpic.dashArray,
    ];
    expect(new Set(dashArrays).size).toBe(dashArrays.length);
  });

  it("each kind has a distinct token, so no two edge kinds share a color", () => {
    const tokens = Object.values(ROADMAP_EDGE_STYLES).map((s) => s.token);
    expect(new Set(tokens).size).toBe(tokens.length);
  });
});
