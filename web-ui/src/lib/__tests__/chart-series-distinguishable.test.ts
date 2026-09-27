import { describe, it, expect } from "vitest";
import {
  CHART_SERIES_STYLES,
  CHART_TEXT_TOKEN,
  CHART_SURFACE_TOKEN,
  type ChartSeriesStyle,
} from "../chartSeriesStyles";
import {
  readThemeTokens,
  contrastRatio,
  deltaE,
  CONTRAST_MIN,
  TEXT_CONTRAST_MIN,
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

// Charts now render on the opaque --popover surface (CHART_SURFACE_TOKEN) in both themes —
// see chartTooltipProps()/chartTickProps() in chartSeriesStyles.ts — so series/text contrast
// is measured against that surface, not against --background/--card.
const THEMES = [
  { theme: "light", selector: ":root" as const },
  { theme: "dark", selector: ".dark" as const },
];

describe.each(THEMES)("chart series distinguishability — $theme", ({ theme, selector }) => {
  const tokens = readThemeTokens(selector);
  const surface = tokens[CHART_SURFACE_TOKEN.slice(2)];
  const hexOf = (style: ChartSeriesStyle) => tokens[style.token.slice(2)];

  it("chart text clears the AA text floor against the opaque chart surface", () => {
    const text = tokens[CHART_TEXT_TOKEN.slice(2)];
    const ratio = contrastRatio(text, surface);
    expect(ratio, `${theme}: ${CHART_TEXT_TOKEN} vs ${CHART_SURFACE_TOKEN} = ${ratio.toFixed(2)}`).toBeGreaterThanOrEqual(
      TEXT_CONTRAST_MIN,
    );
  });

  describe.each(Object.entries(CHART_SERIES_STYLES))("%s", (chartName, seriesRaw) => {
    const series = seriesRaw as Record<string, ChartSeriesStyle>;
    const entries = Object.entries(series);

    it.each(entries)("%s's series token resolves to a hex color in this theme", (_name, style) => {
      const hex = hexOf(style);
      expect(hex, `${theme} ${chartName}: ${style.token} missing from ${selector}`).toBeDefined();
      expect(hex, `${theme} ${chartName}: ${style.token} is not #rrggbb`).toMatch(/^#[0-9a-fA-F]{6}$/);
    });

    it.each(entries)(`%s clears the ${CONTRAST_MIN}:1 contrast floor against the chart surface`, (name, style) => {
      const value = contrastRatio(hexOf(style), surface);
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

    it.each(pairs)(`%s and %s clear normal-vision ΔE ≥ ${NORMAL_DELTA_E_MIN}`, (nameA, nameB) => {
      const value = deltaE(hexOf(series[nameA]), hexOf(series[nameB]));
      expect(
        value,
        `${theme} ${chartName}: ${nameA}↔${nameB} normal ΔE`,
      ).toBeGreaterThanOrEqual(NORMAL_DELTA_E_MIN);
    });

    it.each(pairs)(`%s and %s clear CVD ΔE ≥ ${CVD_DELTA_E_MIN}, or are relieved by distinct line dash patterns`, (nameA, nameB) => {
      const styleA = series[nameA];
      const styleB = series[nameB];
      const a = hexOf(styleA);
      const b = hexOf(styleB);
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
