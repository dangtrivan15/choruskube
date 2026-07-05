import { describe, it, expect } from "vitest";
import { buildOrthogonalPath } from "../DagEdge";

describe("buildOrthogonalPath", () => {
  it("returns an empty string for fewer than 2 points", () => {
    expect(buildOrthogonalPath([], 12)).toBe("");
    expect(buildOrthogonalPath([{ x: 0, y: 0 }], 12)).toBe("");
  });

  it("returns a straight line for exactly 2 points", () => {
    expect(buildOrthogonalPath([{ x: 0, y: 0 }, { x: 100, y: 0 }], 12)).toBe(
      "M 0,0 L 100,0",
    );
  });

  it("rounds an L-shaped corner with a quadratic curve", () => {
    const path = buildOrthogonalPath(
      [
        { x: 0,   y: 0  },
        { x: 100, y: 0  },
        { x: 100, y: 80 },
      ],
      12,
    );
    expect(path).toContain("L 88,0");
    expect(path).toContain("Q 100,0 100,12");
    expect(path).toContain("L 100,80");
  });

  it("clamps the corner radius to half the shorter adjacent segment", () => {
    const path = buildOrthogonalPath(
      [
        { x: 0,  y: 0  },
        { x: 10, y: 0  },
        { x: 10, y: 50 },
      ],
      12, // shorter incoming segment is 10, so r = 5
    );
    expect(path).toContain("L 5,0");
    expect(path).toContain("Q 10,0 10,5");
  });
});
