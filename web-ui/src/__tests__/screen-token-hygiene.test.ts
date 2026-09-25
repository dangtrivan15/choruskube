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
  it.each([
    "src/components/analytics/RunTrendChart.tsx",
    "src/components/analytics/BottleneckChart.tsx",
    "src/components/analytics/RoadmapThroughputChart.tsx",
  ])("%s has no hsl(var(--...)) wrapper and still uses var(--card)/var(--border)", (relPath) => {
    const source = read(relPath);
    expect(source).not.toContain("hsl(var(--");
    expect(source).toContain("var(--card)");
    expect(source).toContain("var(--border)");
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
