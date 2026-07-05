import { describe, it, expect, beforeEach } from "vitest";
import { getEdgeColor } from "../dagLayout";

describe("getEdgeColor", () => {
  beforeEach(() => {
    // Set CSS custom properties so getComputedStyle can resolve them in happy-dom
    const root = document.documentElement;
    root.classList.remove("dark");
    root.style.setProperty("--status-success", "#9ccfd8");
    root.style.setProperty("--status-info", "#31748f");
    root.style.setProperty("--status-error", "#eb6f92");
    root.style.setProperty("--status-neutral", "#908caa");
  });

  it("returns the correct color for known statuses", () => {
    expect(getEdgeColor("completed")).toBe("#9ccfd8");
    expect(getEdgeColor("running")).toBe("#31748f");
    expect(getEdgeColor("failed")).toBe("#eb6f92");
  });

  it("returns neutral color for unknown statuses", () => {
    expect(getEdgeColor("unknown")).toBe("#908caa");
  });

  it("invalidates cache when dark class toggles", () => {
    // Resolve once in light mode
    expect(getEdgeColor("completed")).toBe("#9ccfd8");

    // Switch to dark and update the CSS variable
    document.documentElement.classList.add("dark");
    document.documentElement.style.setProperty("--status-success", "#56949f");

    // Should re-resolve from getComputedStyle (cache invalidated by dark flag)
    expect(getEdgeColor("completed")).toBe("#56949f");
  });
});
