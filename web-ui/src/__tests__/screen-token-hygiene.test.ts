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

describe("git-repo dialog uses the status-info token, not a raw blue", () => {
  it("CreateGitRepoDialog.tsx contains no blue- class", () => {
    const source = read("src/components/git-repos/CreateGitRepoDialog.tsx");
    expect(source).not.toMatch(/\bblue-/);
    expect(source).toContain("status-info");
  });
});

describe("platform credential panel uses the status-success token, not a raw green", () => {
  it("PlatformManagedCredentialPanel.tsx contains no green- class", () => {
    const source = read(
      "src/components/integrations/PlatformManagedCredentialPanel.tsx",
    );
    expect(source).not.toMatch(/\bgreen-/);
    expect(source).toContain("status-success");
  });
});
