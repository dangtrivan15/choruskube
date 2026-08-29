import { describe, it, expect } from "vitest";
import { readFileSync } from "fs";
import path from "path";

// Guard against silent drift between the light theme and the deviation catalog
// (web-ui/docs/light-theme-rose-pine-audit.md). Parses the raw :root block rather
// than importing computed styles so it also catches edits made outside a browser
// context (e.g. a value changed by hand without running the app).
const CSS_PATH = path.resolve(__dirname, "../../index.css");

function readRootBlock(): string {
  const css = readFileSync(CSS_PATH, "utf-8");
  const match = css.match(/:root\s*{([^}]*)}/);
  if (!match) {
    throw new Error("Could not find :root block in index.css");
  }
  return match[1];
}

function tokenValue(block: string, token: string): string {
  const match = block.match(
    new RegExp(`--${token}:\\s*([^;]+);`),
  );
  if (!match) {
    throw new Error(`Token --${token} not found in :root block`);
  }
  return match[1].trim();
}

describe("light theme accent tokens match canonical Rose Pine Dawn", () => {
  const block = readRootBlock();

  // Canonical Dawn hues (rosepinetheme.com), pinned in
  // docs/decisions/2026-08-29---01-original-rose-pine-dawn-light-theme.md.
  // If one of these fails, either the theme drifted or the catalog is stale —
  // update web-ui/docs/light-theme-rose-pine-audit.md alongside the fix.
  it.each([
    ["primary", "#907aa9"], // iris
    ["ring", "#907aa9"], // iris
    ["sidebar-primary", "#907aa9"], // iris
    ["primary-foreground", "#faf4ed"], // base
    ["sidebar-primary-foreground", "#faf4ed"], // base
    ["destructive", "#b4637a"], // love
    ["muted-foreground", "#797593"], // subtle
    ["chart-1", "#56949f"], // foam
    ["chart-2", "#907aa9"], // iris
    ["chart-3", "#d7827e"], // rose
    ["chart-4", "#286983"], // pine
    ["chart-5", "#ea9d34"], // gold
    ["status-success", "#56949f"], // foam
    ["status-error", "#b4637a"], // love
    ["status-info", "#286983"], // pine
    ["status-warning", "#ea9d34"], // gold
    ["status-accent", "#907aa9"], // iris
    ["status-neutral", "#797593"], // subtle
  ])("--%s equals canonical Dawn %s", (token, canonical) => {
    expect(tokenValue(block, token)).toBe(canonical);
  });
});

describe("known neutral deviations from canonical Rose Pine Dawn (not yet restored)", () => {
  const block = readRootBlock();

  // These intentionally do NOT assert the canonical value — they pin the
  // *current* custom value so this test mirrors the catalog's Tier-1 table
  // truthfully instead of pretending a restore already happened. When the
  // restoration task changes one of these, update this test and the catalog
  // in the same change.
  it.each([
    ["background", "#fafaf7"],
    ["popover", "#fafaf7"],
    ["sidebar", "#fafaf7"],
    ["foreground", "#3e3859"],
    ["card-foreground", "#3e3859"],
    ["secondary", "#f1eef5"],
    ["muted", "#f1eef5"],
    ["accent", "#f1eef5"],
    ["sidebar-accent", "#e6dfee"],
  ])("--%s still holds its current custom value %s", (token, current) => {
    expect(tokenValue(block, token)).toBe(current);
  });
});
