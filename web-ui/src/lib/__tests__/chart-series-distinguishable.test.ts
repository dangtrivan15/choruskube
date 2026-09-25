import { describe, it, expect } from "vitest";
import { CHART_SERIES_STYLES, type ChartSeriesStyle } from "../chartSeriesStyles";
import {
  readThemeTokens,
  contrastRatio,
  deltaE,
  CONTRAST_MIN,
  NORMAL_DELTA_E_MIN,
  CVD_DELTA_E_MIN,
} from "./helpers/colorMetrics";

describe("color metrics sanity", () => {
  it("black on white is ~21:1", () => {
    expect(contrastRatio("#000000", "#ffffff")).toBeCloseTo(21, 0);
  });

  it("a color against itself has zero ΔE", () => {
    expect(deltaE("#907aa9", "#907aa9")).toBe(0);
  });

  it("the pre-change dark Total/Completed pair (foreground vs. foam) measures ~10.4 ΔE — below the floor, proving the gate would have caught the original defect", () => {
    expect(deltaE("#e0def4", "#9ccfd8")).toBeCloseTo(10.4, 0);
    expect(deltaE("#e0def4", "#9ccfd8")).toBeLessThan(NORMAL_DELTA_E_MIN);
  });

  it("the light Completed/Failed pair is only ~5.6 ΔE apart for deuteranopes — passes only via dash relief", () => {
    expect(deltaE("#b4637a", "#56949f", "deutan")).toBeCloseTo(5.6, 0);
  });
});

const THEMES = [
  { theme: "light", selector: ":root" as const, surfaceToken: "background" },
  { theme: "dark", selector: ".dark" as const, surfaceToken: "card" },
];

describe.each(THEMES)("chart series distinguishability — $theme", ({ theme, selector, surfaceToken }) => {
  const tokens = readThemeTokens(selector);
  const surface = tokens[surfaceToken];

  describe.each(Object.entries(CHART_SERIES_STYLES))("%s", (chartName, seriesRaw) => {
    const series = seriesRaw as Record<string, ChartSeriesStyle>;
    const entries = Object.entries(series);

    it.each(entries)("%s's series token resolves to a hex color in this theme", (_name, style) => {
      const hex = tokens[style.token.slice(2)];
      expect(hex, `${theme} ${chartName}: ${style.token} missing from ${selector}`).toBeDefined();
      expect(() => hex.match(/^#[0-9a-fA-F]{6}$/)).toBeTruthy();
    });

    it.each(entries)("%s clears the %d:1 contrast floor against the chart surface", (name, style) => {
      const hex = tokens[style.token.slice(2)];
      const value = contrastRatio(hex, surface);
      expect(value, `${theme} ${chartName}: ${name} vs surface contrast`).toBeGreaterThanOrEqual(
        CONTRAST_MIN,
      );
    });

    const pairs: [string, string][] = [];
    for (let i = 0; i < entries.length; i++) {
      for (let j = i + 1; j < entries.length; j++) {
        pairs.push([entries[i][0], entries[j][0]]);
      }
    }

    it.each(pairs)("%s and %s clear normal-vision ΔE ≥ %d", (nameA, nameB) => {
      const a = tokens[series[nameA].token.slice(2)];
      const b = tokens[series[nameB].token.slice(2)];
      const value = deltaE(a, b);
      expect(
        value,
        `${theme} ${chartName}: ${nameA}↔${nameB} normal ΔE`,
      ).toBeGreaterThanOrEqual(NORMAL_DELTA_E_MIN);
    });

    it.each(pairs)("%s and %s clear CVD ΔE ≥ %d, or are relieved by distinct line dash patterns", (nameA, nameB) => {
      const styleA = series[nameA];
      const styleB = series[nameB];
      const a = tokens[styleA.token.slice(2)];
      const b = tokens[styleB.token.slice(2)];
      const value = Math.min(deltaE(a, b, "protan"), deltaE(a, b, "deutan"));
      const dashRelieved =
        styleA.mark === "line" && styleB.mark === "line" && styleA.dashArray !== styleB.dashArray;
      expect(
        value >= CVD_DELTA_E_MIN || dashRelieved,
        `${theme} ${chartName}: ${nameA}↔${nameB} CVD ΔE ${value.toFixed(1)}, dash-relieved=${dashRelieved}`,
      ).toBe(true);
    });
  });
});
