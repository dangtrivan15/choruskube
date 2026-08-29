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

describe("light theme neutral tokens match canonical Rose Pine Dawn", () => {
  const block = readRootBlock();

  // These neutrals were restored from a house-drifted set of cool-lilac
  // values to canonical Dawn — see docs/light-theme-rose-pine-audit.md's
  // Tier-1 table (kept in sync with this file) and
  // docs/decisions/2026-08-29---01-original-rose-pine-dawn-light-theme.md.
  it.each([
    ["background", "#faf4ed"], // base
    ["popover", "#fffaf3"], // surface
    ["sidebar", "#fffaf3"], // surface
    ["foreground", "#575279"], // text
    ["card-foreground", "#575279"], // text
    ["secondary", "#f2e9e1"], // overlay
    ["muted", "#f2e9e1"], // overlay
    ["accent", "#f2e9e1"], // overlay
    ["sidebar-accent", "#f2e9e1"], // overlay
  ])("--%s equals canonical Dawn %s", (token, canonical) => {
    expect(tokenValue(block, token)).toBe(canonical);
  });
});
