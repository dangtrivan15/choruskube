import { describe, it, expect } from "vitest";
import fs from "fs";
import path from "path";

/**
 * Guards the handful of screens that used to bypass the theme's semantic
 * tokens (docs/light-theme-rose-pine-audit.md Tier 2) against regressing
 * back to a hardcoded / non-resolving color.
 */
function read(relPath: string): string {
  return fs.readFileSync(path.resolve(__dirname, "../..", relPath), "utf-8");
}

describe("analytics charts reference tokens directly, not through hsl()", () => {
  const LEGEND_CHART_PATHS = [
    "src/components/analytics/RunTrendChart.tsx",
    "src/components/analytics/BottleneckChart.tsx",
  ];
  const CHART_PATHS = [...LEGEND_CHART_PATHS, "src/components/analytics/RoadmapThroughputChart.tsx"];
  /** BottleneckChart's Y axis keeps its pre-existing 11px tick to fit long node labels; every other axis is 12px. */
  const AXIS_FONT_SIZES: Record<string, number[]> = {
    "src/components/analytics/RunTrendChart.tsx": [12, 12],
    "src/components/analytics/BottleneckChart.tsx": [12, 11],
    "src/components/analytics/RoadmapThroughputChart.tsx": [12, 12],
  };

  it.each(CHART_PATHS)("%s has no hsl(var(--...)) wrapper", (relPath) => {
    const source = read(relPath);
    expect(source).not.toContain("hsl(var(--");
  });

  it.each(CHART_PATHS)("%s reads its series styles from the chart-series registry", (relPath) => {
    expect(read(relPath)).toContain('from "@/lib/chartSeriesStyles"');
  });

  it.each(CHART_PATHS)("%s carries no inline stroke/fill series-color literal, bypassing the registry", (relPath) => {
    const source = read(relPath);
    expect(source).not.toMatch(/\b(stroke|fill)=\{?\s*["'`]var\(--/);
  });

  /** Every self-closing `<Tag …/>` element of `tag` in `source`, props included (props may hold `=>`). */
  function jsxTags(source: string, tag: string): string[] {
    return source.match(new RegExp(`<${tag}\\b[\\s\\S]*?\\/>`, "g")) ?? [];
  }

  it.each(CHART_PATHS)("%s styles every tooltip through chartTooltipProps() alone", (relPath) => {
    const tooltips = jsxTags(read(relPath), "Tooltip");
    expect(tooltips).toHaveLength(1);
    for (const tooltip of tooltips) {
      expect(tooltip).toMatch(/^<Tooltip\s+\{\.\.\.chartTooltipProps\(\)\}/);
      // A style prop after the spread would silently override the opaque surface or ink text.
      expect(tooltip).not.toMatch(/\b(?:contentStyle|itemStyle|labelStyle|wrapperStyle)=/);
    }
  });

  it.each(CHART_PATHS)("%s pins every axis tick to chartTickProps() at its expected font size", (relPath) => {
    const source = read(relPath);
    const axes = [...jsxTags(source, "XAxis"), ...jsxTags(source, "YAxis")];
    const sizes = axes.map((axis) => axis.match(/\btick=\{chartTickProps\((\d+)\)\}/)?.[1]);
    expect(sizes).toEqual(AXIS_FONT_SIZES[relPath].map(String));
  });

  it.each(LEGEND_CHART_PATHS)("%s pins its legend label text to the foreground ink", (relPath) => {
    expect(read(relPath)).toMatch(/labelStyle=\{\{\s*color:\s*"var\(--foreground\)"\s*\}\}/);
  });
});

describe("AnalyticsPage.tsx chart card containers use the opaque popover surface", () => {
  it("Run Trend, Bottlenecks, and Roadmap chart containers use bg-popover, not bg-card", () => {
    const source = read("src/pages/AnalyticsPage.tsx");
    const matches = source.match(/className="[^"]*rounded-lg border[^"]*"/g) ?? [];
    const popoverContainers = matches.filter((m) => m.includes("bg-popover"));
    expect(popoverContainers.length).toBeGreaterThanOrEqual(3);
    for (const container of popoverContainers) {
      expect(container).toContain("shadow-sm");
    }
  });
});

describe("git-repo dialog renders its notice through the shared callout primitive", () => {
  it("CreateGitRepoDialog.tsx contains no blue- class", () => {
    const source = read("src/components/git-repos/CreateGitRepoDialog.tsx");
    expect(source).not.toMatch(/\bblue-/);
    expect(source).toContain("StatusCallout");
  });
});

describe("platform credential panel renders its dot through the shared status primitive", () => {
  it("PlatformManagedCredentialPanel.tsx contains no green- class", () => {
    const source = read(
      "src/components/integrations/PlatformManagedCredentialPanel.tsx",
    );
    expect(source).not.toMatch(/\bgreen-/);
    expect(source).toContain("StatusDot");
  });
});

describe("log viewer severity styling draws only on semantic status tokens", () => {
  const RAW_COLOR_PATTERNS = [/\b(red|amber|yellow|orange|blue|green)-/, /#[0-9a-fA-F]{3,8}\b/];

  it("logLevelStyles.ts uses status- tokens", () => {
    expect(read("src/lib/logLevelStyles.ts")).toContain("status-");
  });

  it.each(["src/lib/logLevelStyles.ts", "src/components/runs/ExecutionLogs.tsx"])(
    "%s has no raw palette class or hex literal",
    (relPath) => {
      const source = read(relPath);
      for (const pattern of RAW_COLOR_PATTERNS) {
        expect(source).not.toMatch(pattern);
      }
    },
  );

  it("ExecutionLogs.tsx consumes the centralized severity resolver", () => {
    expect(read("src/components/runs/ExecutionLogs.tsx")).toContain("logLevelStyle");
  });
});
