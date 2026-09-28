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
  readonly roadmapThroughputChart: Locator;
  /** Every axis tick label across the whole page (scope to a chart's own container to narrow). */
  readonly axisTickLabels: Locator;
  /** Usage-quota "Warning"/"Critical" chips (UsageDashboard.tsx). */
  readonly quotaChips: Locator;

  constructor(page: Page) {
    this.page = page;
    this.heading = page.getByRole("heading", { name: "Analytics" });
    this.runTrendChart = page.getByTestId("run-trend-chart");
    this.bottleneckChart = page.getByTestId("bottleneck-chart");
    this.roadmapThroughputChart = page.getByTestId("roadmap-throughput-chart");
    this.axisTickLabels = page.locator(".recharts-cartesian-axis-tick-value");
    this.quotaChips = page.getByTestId("usage-quota-chip");
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

  /**
   * Hovers the middle of a chart's plotting surface to trigger Recharts' tooltip, and returns
   * that chart's tooltip wrapper. Every chart on the page (Run Trend, Bottlenecks, Roadmap
   * Throughput) renders its own `.recharts-tooltip-wrapper` at all times — hidden via inline
   * style until hovered, not absent from the DOM — so a page-wide `.recharts-tooltip-wrapper`
   * locator is a strict-mode violation whenever more than one chart is mounted; the lookup must
   * be scoped to the chart just hovered.
   *
   * The plotting surface locator is scoped to `.recharts-wrapper`'s direct child: each Legend
   * item icon is *also* an `.recharts-surface` (Recharts renders legend icons with the same
   * `<Surface>` primitive as the main plot) and those icons mount, as siblings of the real
   * plotting surface, before it — so an unscoped `.recharts-surface` `.first()` silently
   * resolves to a 24×24 legend swatch instead of the chart, and hovering its center never lands
   * inside the plot area, leaving the tooltip permanently hidden. Legend icons sit several
   * levels deeper (`.recharts-wrapper > .recharts-legend-wrapper > … > svg`), so the direct-child
   * combinator excludes them unambiguously.
   *
   * `scrollIntoViewIfNeeded` runs before the bounding box is read: `page.mouse.move` dispatches
   * at raw viewport coordinates and does not auto-scroll like `locator.hover()` does, so a chart
   * that starts below the fold (Run Trend sits below the Resource Usage/overview cards, which
   * pushes it past the default 720px viewport height) would otherwise get a center point beyond
   * the visible viewport — the move lands nowhere and the tooltip never opens.
   */
  async hoverDataPoint(chart: Locator): Promise<Locator> {
    const surface = chart.locator(".recharts-wrapper > .recharts-surface").first();
    await surface.scrollIntoViewIfNeeded();
    const box = await surface.boundingBox();
    if (!box) throw new Error("Chart surface has no bounding box — is it visible?");
    await this.page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
    const tooltip = chart.locator(".recharts-tooltip-wrapper");
    await expect(tooltip).toBeVisible();
    return tooltip;
  }

  async isDark(): Promise<boolean> {
    const className = await this.page.locator("html").getAttribute("class");
    return (className ?? "").split(/\s+/).includes("dark");
  }

  async toggleTheme(): Promise<void> {
    await toggleTheme(this.page);
  }
}
