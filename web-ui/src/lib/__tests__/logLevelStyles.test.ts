import { describe, it, expect } from "vitest";
import { Info, TriangleAlert, OctagonAlert, Minus } from "lucide-react";
import { logLevelStyle } from "../logLevelStyles";

describe("logLevelStyle", () => {
  it("returns the info treatment", () => {
    const style = logLevelStyle("info");
    expect(style.Icon).toBe(Info);
    expect(style.text).toBe("text-status-info");
    expect(style.weight).toBe("");
    expect(style.border).toBe("border-status-info");
    expect(style.row).toBe("");
  });

  it("returns the warn treatment", () => {
    const style = logLevelStyle("warn");
    expect(style.Icon).toBe(TriangleAlert);
    expect(style.text).toBe("text-status-warning");
    expect(style.weight).toBe("font-medium");
    expect(style.border).toBe("border-status-warning");
    expect(style.row).toBe("bg-status-warning/10");
  });

  it("returns the error treatment", () => {
    const style = logLevelStyle("error");
    expect(style.Icon).toBe(OctagonAlert);
    expect(style.text).toBe("text-status-error");
    expect(style.weight).toBe("font-semibold");
    expect(style.border).toBe("border-status-error");
    expect(style.row).toBe("bg-status-error/10");
  });

  it("is case-insensitive", () => {
    expect(logLevelStyle("WARN")).toEqual(logLevelStyle("warn"));
    expect(logLevelStyle("Error")).toEqual(logLevelStyle("error"));
    expect(logLevelStyle("Info")).toEqual(logLevelStyle("info"));
  });

  it("falls back to the neutral treatment for an unknown level", () => {
    const style = logLevelStyle("debug");
    expect(style.Icon).toBe(Minus);
    expect(style.text).toBe("text-status-neutral");
    expect(style.weight).toBe("");
    expect(style.border).toBe("border-status-neutral");
    expect(style.row).toBe("");
  });

  it("falls back to the neutral treatment for an empty level", () => {
    expect(logLevelStyle("").text).toBe("text-status-neutral");
  });

  it("resolves pairwise-distinct colors for info/warn/error", () => {
    const colors = ["info", "warn", "error"].map((level) => logLevelStyle(level).text);
    expect(new Set(colors).size).toBe(3);
  });

  it("resolves pairwise-distinct icons for info/warn/error", () => {
    const icons = ["info", "warn", "error"].map((level) => logLevelStyle(level).Icon);
    expect(new Set(icons).size).toBe(3);
  });
});
