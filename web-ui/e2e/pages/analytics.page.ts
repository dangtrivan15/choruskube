import { type Page, type Locator, expect } from "@playwright/test";
import { toggleTheme } from "../helpers/colors";

/**
 * Page object for the Analytics page (/analytics), scoped to the chart
 * series-color contract: resolved series/legend colors and dash patterns,
 * legend label ink, paint order, and theme responsiveness.
 */
export class AnalyticsPage {
  readonly page: Page;

  readonly heading: Locator;
  readonly runTrendChart: Locator;
  readonly bottleneckChart: Locator;

  constructor(page: Page) {
    this.page = page;
    this.heading = page.getByRole("heading", { name: "Analytics" });
    this.runTrendChart = page.getByTestId("run-trend-chart");
    this.bottleneckChart = page.getByTestId("bottleneck-chart");
  }

  async goto() {
    await this.page.goto("/analytics");
    await expect(this.heading).toBeVisible();
  }

  private areaCurve(key: string): Locator {
    return this.runTrendChart.locator(`.recharts-area.series-${key} .recharts-area-curve`).first();
  }

  private legendIcon(chart: Locator, label: string): Locator {
    return chart.locator(`[aria-label="${label} legend icon"] .recharts-legend-icon`);
  }

  /** Computed `stroke` of a Run Trend area's curve path for the given series key (e.g. `"total"`). */
  async areaStroke(key: string): Promise<string> {
    return this.areaCurve(key).evaluate((el) => getComputedStyle(el).stroke);
  }

  /** A Run Trend area curve's `stroke-dasharray` attribute; `null` when the line is solid. */
  async areaDash(key: string): Promise<string | null> {
    return this.areaCurve(key).getAttribute("stroke-dasharray");
  }

  /** Computed `fill` of a Bottlenecks bar rectangle for the given series key (e.g. `"avg"`). */
  async barFill(key: string): Promise<string> {
    const rect = this.bottleneckChart.locator(`.recharts-bar.series-${key} .recharts-bar-rectangle path`).first();
    return rect.evaluate((el) => getComputedStyle(el).fill);
  }

  /**
   * Computed `stroke` or `fill` of a chart's legend icon for the given series
   * label. Line series (Run Trend) carry their color in `stroke`; bar series
   * (Bottlenecks) carry it in `fill`.
   */
  async legendIconColor(chart: Locator, label: string, prop: "stroke" | "fill"): Promise<string> {
    return this.legendIcon(chart, label).evaluate((el, p) => getComputedStyle(el)[p], prop);
  }

  /** A legend icon's `stroke-dasharray` attribute; `null` when the icon is solid. */
  async legendIconDash(chart: Locator, label: string): Promise<string | null> {
    return this.legendIcon(chart, label).getAttribute("stroke-dasharray");
  }

  /** Computed text color of a chart's legend label for the given series. */
  async legendLabelColor(chart: Locator, label: string): Promise<string> {
    const item = chart
      .locator(".recharts-legend-item", { has: this.page.locator(`[aria-label="${label} legend icon"]`) })
      .locator(".recharts-legend-item-text");
    return item.evaluate((el) => getComputedStyle(el).color);
  }

  /** The page's resolved ink color — the `<body>` text color for the active theme. */
  async inkColor(): Promise<string> {
    return this.page.evaluate(() => getComputedStyle(document.body).color);
  }

  /** Each Run Trend area layer's `series-*` class, in DOM (paint) order. */
  async areaPaintOrder(): Promise<string[]> {
    return this.runTrendChart.locator(".recharts-area").evaluateAll((els) =>
      els.map((el) => Array.from(el.classList).find((c) => c.startsWith("series-")) ?? ""),
    );
  }

  async isDark(): Promise<boolean> {
    const className = await this.page.locator("html").getAttribute("class");
    return (className ?? "").split(/\s+/).includes("dark");
  }

  async toggleTheme(): Promise<void> {
    await toggleTheme(this.page);
  }
}
