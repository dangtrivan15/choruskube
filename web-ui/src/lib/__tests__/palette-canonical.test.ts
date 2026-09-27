import { describe, it, expect } from "vitest";
import { readFileSync } from "fs";
import path from "path";
import { DAWN, mixSrgb } from "./helpers/colorMetrics";

// Guard against silent drift between the light theme and the deviation catalog
// (web-ui/docs/light-theme-rose-pine-audit.md). Parses the raw :root block rather
// than importing computed styles so it also catches edits made outside a browser
// context (e.g. a value changed by hand without running the app). AA-deepened
// tokens' mix recipes are pinned per
// docs/decisions/2026-09-26---01-aa-contrast-text-and-controls.md.
const CSS_PATH = path.resolve(__dirname, "../../index.css");

function readRootBlock(): string {
  const css = readFileSync(CSS_PATH, "utf-8");
  const match = css.match(/:root\s*{([^}]*)}/);
  if (!match) {
    throw new Error("Could not find :root block in index.css");
  }
  return match[1];
}

function readDarkBlock(): string {
  const css = readFileSync(CSS_PATH, "utf-8");
  const match = css.match(/\.dark\s*{([^}]*)}/);
  if (!match) {
    throw new Error("Could not find .dark block in index.css");
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
  // --primary/--ring/--sidebar-primary/--sidebar-ring, --destructive and
  // --muted-foreground are pinned as AA-deepened mixes below instead, per
  // docs/decisions/2026-09-26---01-aa-contrast-text-and-controls.md.
  it.each([
    ["primary-foreground", "#faf4ed"], // base
    ["sidebar-primary-foreground", "#faf4ed"], // base
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

describe("light theme AA-deepened tokens are on-palette mixes toward Dawn text", () => {
  const block = readRootBlock();

  // Decision: no light-mode accent but text itself clears 4.5:1 as a canonical
  // Dawn hex, so each failing one is deepened toward text in 10% steps instead
  // — see docs/decisions/2026-09-26---01-aa-contrast-text-and-controls.md.
  it.each([
    ["muted-foreground", "subtle", 0.6],
    ["primary", "iris", 0.7],
    ["ring", "iris", 0.7],
    ["sidebar-primary", "iris", 0.7],
    ["sidebar-ring", "iris", 0.7],
    ["destructive", "love", 0.7],
  ] as const)("--%s equals Dawn %s deepened toward Dawn text at text weight %s", (token, role, weightOfText) => {
    // The ladder only takes whole 10% steps.
    expect(weightOfText * 10).toBeCloseTo(Math.round(weightOfText * 10), 9);
    expect(tokenValue(block, token)).toBe(mixSrgb(DAWN.text, DAWN[role], weightOfText));
  });
});

describe("light theme re-pointed and new tokens match their canonical or recipe value", () => {
  const block = readRootBlock();

  it.each([
    ["input", "#797593"], // canonical subtle
    ["destructive-foreground", "#faf4ed"], // canonical base
  ])("--%s equals %s", (token, expected) => {
    expect(tokenValue(block, token)).toBe(expected);
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
    ["chart-reference", "#575279"], // text
  ])("--%s equals canonical Dawn %s", (token, canonical) => {
    expect(tokenValue(block, token)).toBe(canonical);
  });
});

// The :root pins above exist because a hand-edited hex can drift from canonical
// Rose Pine with nothing catching it. The .dark block had no equivalent guard;
// these two blocks close that gap the same way, against Rose Pine's "main" variant.
describe("dark theme accent tokens match canonical Rose Pine main", () => {
  const block = readDarkBlock();

  it.each([
    ["primary", "#c4a7e7"], // iris
    ["ring", "#c4a7e7"], // iris
    ["sidebar-primary", "#c4a7e7"], // iris
    ["primary-foreground", "#191724"], // base
    ["sidebar-primary-foreground", "#191724"], // base
    ["destructive", "#eb6f92"], // love
    ["destructive-foreground", "#191724"], // base
    ["muted-foreground", "#908caa"], // subtle
    ["chart-1", "#9ccfd8"], // foam
    ["chart-2", "#c4a7e7"], // iris
    ["chart-3", "#ebbcba"], // rose
    ["chart-4", "#31748f"], // pine
    ["chart-5", "#f6c177"], // gold
    ["status-success", "#9ccfd8"], // foam
    ["status-error", "#eb6f92"], // love
    ["status-info", "#31748f"], // pine
    ["status-warning", "#f6c177"], // gold
    ["status-accent", "#c4a7e7"], // iris
    ["status-neutral", "#908caa"], // subtle
  ])("--%s equals canonical main %s", (token, canonical) => {
    expect(tokenValue(block, token)).toBe(canonical);
  });
});

describe("dark theme neutral tokens match canonical Rose Pine main", () => {
  const block = readDarkBlock();

  it.each([
    ["background", "#191724"], // base
    ["card", "#1f1d2e"], // surface
    ["popover", "#1f1d2e"], // surface
    ["sidebar", "#1f1d2e"], // surface
    ["foreground", "#e0def4"], // text
    ["secondary", "#26233a"], // overlay
    ["muted", "#26233a"], // overlay
    ["accent", "#26233a"], // overlay
    ["sidebar-accent", "#26233a"], // overlay
    ["chart-reference", "#6e6a86"], // muted
    ["input", "#6e6a86"], // muted
  ])("--%s equals canonical main %s", (token, canonical) => {
    expect(tokenValue(block, token)).toBe(canonical);
  });
});
