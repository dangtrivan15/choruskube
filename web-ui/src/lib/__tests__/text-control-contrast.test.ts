/**
 * The contrast gate: recomputes every text/control ink-background pair from
 * index.css on each test run, so a palette edit that drops a pair below WCAG
 * AA is caught here instead of at eyeball review. See
 * docs/decisions/2026-09-26---01-aa-contrast-text-and-controls.md.
 */
import { describe, it, expect } from "vitest";
import { readFileSync } from "fs";
import path from "path";
import {
  readThemeTokens,
  parseCssColor,
  contrastRatio,
  blendOver,
  TEXT_CONTRAST_MIN,
  NON_TEXT_CONTRAST_MIN,
} from "./helpers/colorMetrics";

const CSS_PATH = path.resolve(__dirname, "../../index.css");
const rawCss = readFileSync(CSS_PATH, "utf-8");

function hexOfRgb([r, g, b]: [number, number, number]): string {
  const byte = (v: number) => Math.round(v).toString(16).padStart(2, "0");
  return `#${byte(r)}${byte(g)}${byte(b)}`;
}

/**
 * The darkest (lowest relative-luminance) of a set of opaque hex colors, found
 * by contrast-to-white — monotonic for the near-white candidates used here.
 */
function darkestOf(hexes: string[]): string {
  return hexes.reduce((worst, candidate) =>
    contrastRatio(candidate, "#ffffff") > contrastRatio(worst, "#ffffff") ? candidate : worst,
  );
}

function tint(inkHex: string, alpha: number, bgHex: string): string {
  return blendOver(inkHex, alpha, bgHex);
}

// ---------------------------------------------------------------------------
// Gradient-constants guard: the canvas floor below is modelled from these
// constants, which must equal the real gradient declaration's full set of
// tints and stops, or an edit to the gradient would silently stop being
// reflected in the modelled canvas floor.
// ---------------------------------------------------------------------------
const GRADIENT_TINTS = ["rgba(144,122,169,0.08)", "rgba(86,148,159,0.06)", "rgba(234,157,52,0.06)"];
const GRADIENT_STOPS = ["#faf4ed", "#f4ede8", "#f2e9e1"];

describe("light gradient tints/stops match the modelled canvas-floor constants", () => {
  it("the html:not(.dark) body background declaration equals the modelled set", () => {
    const match = rawCss.match(
      /html:not\(\.dark\) body\s*{\s*background:\s*([\s\S]*?)\s*background-attachment/,
    );
    if (!match) {
      throw new Error("Could not find the light body gradient declaration in index.css");
    }
    const declaration = match[1];
    const normalize = (s: string) => s.replace(/\s+/g, "");
    const rgbaFound = Array.from(declaration.matchAll(/rgba\([^)]*\)/g)).map((m) => normalize(m[0]));
    const hexFound = Array.from(declaration.matchAll(/#[0-9a-fA-F]{6}/g)).map((m) => m[0]);
    expect(new Set(rgbaFound)).toEqual(new Set(GRADIENT_TINTS.map(normalize)));
    expect(new Set(hexFound)).toEqual(new Set(GRADIENT_STOPS));
  });
});

describe("focus-visible base layer applies outline-ring at full opacity", () => {
  it("index.css declares outline-ring, not an alphaed outline-ring/NN", () => {
    expect(rawCss).toContain("outline-ring;");
    expect(rawCss).not.toContain("outline-ring/");
  });
});

describe("a translucent ink token cannot be measured silently", () => {
  it("parseHex/contrastRatio throw on an rgba() ink instead of computing a wrong ratio", () => {
    // The pre-fix light --input value — proves a translucent ink/control token
    // fails loudly rather than being measured against the wrong math.
    expect(() => contrastRatio("rgba(87,82,121,0.12)", "#faf4ed")).toThrow();
  });

  it("parseCssColor throws on a non-hex, non-rgb() value", () => {
    expect(() => parseCssColor("oklab(0.6 0.03 0.02)")).toThrow();
  });
});

// ---------------------------------------------------------------------------
// Background model
// ---------------------------------------------------------------------------

interface PairCase {
  ink: string;
  bg: string;
  inkHex: string;
  bgHex: string;
  min: number;
}

function buildLightBackgrounds(tokens: Record<string, string>) {
  const base = tokens["background"];
  const surface = tokens["popover"];
  const overlay = tokens["muted"];
  const sidebar = tokens["sidebar"];

  const S = darkestOf(GRADIENT_STOPS);
  const canvasFloorIris = tint("#907aa9", 0.08, S);
  const canvasFloorFoam = tint("#56949f", 0.06, S);
  const canvasFloorGold = tint("#ea9d34", 0.06, S);
  const darkestFloor = darkestOf([canvasFloorIris, canvasFloorFoam, canvasFloorGold]);

  const cardAlpha = parseCssColor(tokens["card"]).alpha;
  const glassAlpha = parseCssColor(tokens["surface-glass"]).alpha;
  const cardOnFloor = tint("#ffffff", cardAlpha, darkestFloor);
  const glassOnFloor = tint("#ffffff", glassAlpha, darkestFloor);

  return {
    base,
    surface,
    overlay,
    sidebar,
    canvasFloorIris,
    canvasFloorFoam,
    canvasFloorGold,
    darkestFloor,
    cardOnFloor,
    glassOnFloor,
  };
}

function buildDarkBackgrounds(tokens: Record<string, string>) {
  const base = tokens["background"];
  const surface = tokens["card"];
  const overlay = tokens["muted"];
  const sidebar = tokens["sidebar"];

  const glassParsed = parseCssColor(tokens["surface-glass"]);
  const glass = tint(hexOfRgb(glassParsed.rgb), glassParsed.alpha, base);

  // The `--muted` *token* (Rose Pine overlay in dark), not the Rose Pine
  // "muted" role that `--input` takes.
  const entryFillBase = tint(overlay, 0.5, base);
  const entryFillSurface = tint(overlay, 0.5, surface);
  const entryHoverSurface = tint(overlay, 0.7, surface);

  const input = tokens["input"];
  const outlineFillBase = tint(input, 0.3, base);
  const outlineFillSurface = tint(input, 0.3, surface);
  const outlineHoverBase = tint(input, 0.5, base);
  const outlineHoverSurface = tint(input, 0.5, surface);

  return {
    base,
    surface,
    overlay,
    sidebar,
    glass,
    entryFillBase,
    entryFillSurface,
    entryHoverSurface,
    outlineFillBase,
    outlineFillSurface,
    outlineHoverBase,
    outlineHoverSurface,
  };
}

type LightBg = ReturnType<typeof buildLightBackgrounds>;
type DarkBg = ReturnType<typeof buildDarkBackgrounds>;

const LIGHT_TEXT_BG_IDS: (keyof LightBg)[] = [
  "base",
  "surface",
  "overlay",
  "sidebar",
  "canvasFloorIris",
  "canvasFloorFoam",
  "canvasFloorGold",
  "cardOnFloor",
  "glassOnFloor",
];
const LIGHT_CONTROL_BG_IDS: (keyof LightBg)[] = [
  "base",
  "surface",
  "overlay",
  "cardOnFloor",
  "glassOnFloor",
  "canvasFloorIris",
  "canvasFloorFoam",
  "canvasFloorGold",
];
const DARK_TEXT_BG_IDS: (keyof DarkBg)[] = ["base", "surface", "overlay", "glass"];
const DARK_CONTROL_BG_IDS: (keyof DarkBg)[] = ["base", "surface", "glass"];

function buildLightRegistry(tokens: Record<string, string>, bg: LightBg): PairCase[] {
  const cases: PairCase[] = [];
  const push = (ink: string, bgName: string, inkHex: string, bgHex: string, min: number) =>
    cases.push({ ink, bg: bgName, inkHex, bgHex, min });

  for (const inkName of ["foreground", "muted-foreground", "primary", "destructive"]) {
    for (const bgId of LIGHT_TEXT_BG_IDS) {
      push(inkName, bgId, tokens[inkName], bg[bgId], TEXT_CONTRAST_MIN);
    }
  }
  push("card-foreground", "cardOnFloor", tokens["card-foreground"], bg.cardOnFloor, TEXT_CONTRAST_MIN);
  for (const inkName of ["popover-foreground", "muted-foreground"]) {
    push(inkName, "surface", tokens[inkName], bg.surface, TEXT_CONTRAST_MIN);
  }
  for (const inkName of ["secondary-foreground", "accent-foreground"]) {
    push(inkName, "overlay", tokens[inkName], bg.overlay, TEXT_CONTRAST_MIN);
  }
  for (const inkName of ["sidebar-foreground", "sidebar-accent-foreground"]) {
    for (const bgId of ["sidebar", "overlay", "glassOnFloor"] as const) {
      push(inkName, bgId, tokens[inkName], bg[bgId], TEXT_CONTRAST_MIN);
    }
  }
  push("primary-foreground", "primary(fill)", tokens["primary-foreground"], tokens["primary"], TEXT_CONTRAST_MIN);
  push(
    "sidebar-primary-foreground",
    "sidebar-primary(fill)",
    tokens["sidebar-primary-foreground"],
    tokens["sidebar-primary"],
    TEXT_CONTRAST_MIN,
  );
  push(
    "destructive-foreground",
    "destructive(fill)",
    tokens["destructive-foreground"],
    tokens["destructive"],
    TEXT_CONTRAST_MIN,
  );
  push("background", "foreground(tooltip)", tokens["background"], tokens["foreground"], TEXT_CONTRAST_MIN);

  for (const inkName of ["primary", "destructive"]) {
    for (const bgId of ["base", "surface", "cardOnFloor"] as const) {
      push(inkName, `tint10(${inkName},${bgId})`, tokens[inkName], tint(tokens[inkName], 0.1, bg[bgId]), TEXT_CONTRAST_MIN);
    }
  }
  for (const bgId of ["canvasFloorIris", "canvasFloorFoam", "canvasFloorGold"] as const) {
    push(
      "destructive",
      `tint5(destructive,${bgId})`,
      tokens["destructive"],
      tint(tokens["destructive"], 0.05, bg[bgId]),
      TEXT_CONTRAST_MIN,
    );
  }
  for (const bgId of LIGHT_CONTROL_BG_IDS) {
    for (const inkName of ["ring", "input", "destructive"]) {
      push(inkName, bgId, tokens[inkName], bg[bgId], NON_TEXT_CONTRAST_MIN);
    }
  }
  return cases;
}

function buildDarkRegistry(tokens: Record<string, string>, bg: DarkBg): PairCase[] {
  const cases: PairCase[] = [];
  const push = (ink: string, bgName: string, inkHex: string, bgHex: string, min: number) =>
    cases.push({ ink, bg: bgName, inkHex, bgHex, min });

  for (const inkName of ["foreground", "muted-foreground", "primary", "destructive"]) {
    for (const bgId of DARK_TEXT_BG_IDS) {
      push(inkName, bgId, tokens[inkName], bg[bgId], TEXT_CONTRAST_MIN);
    }
  }
  push("card-foreground", "surface", tokens["card-foreground"], bg.surface, TEXT_CONTRAST_MIN);
  for (const inkName of ["popover-foreground", "muted-foreground"]) {
    push(inkName, "surface", tokens[inkName], bg.surface, TEXT_CONTRAST_MIN);
  }
  for (const inkName of ["secondary-foreground", "accent-foreground"]) {
    push(inkName, "overlay", tokens[inkName], bg.overlay, TEXT_CONTRAST_MIN);
  }
  for (const inkName of ["sidebar-foreground", "sidebar-accent-foreground"]) {
    for (const bgId of ["sidebar", "overlay", "glass"] as const) {
      push(inkName, bgId, tokens[inkName], bg[bgId], TEXT_CONTRAST_MIN);
    }
  }
  push("primary-foreground", "primary(fill)", tokens["primary-foreground"], tokens["primary"], TEXT_CONTRAST_MIN);
  push(
    "sidebar-primary-foreground",
    "sidebar-primary(fill)",
    tokens["sidebar-primary-foreground"],
    tokens["sidebar-primary"],
    TEXT_CONTRAST_MIN,
  );
  push(
    "destructive-foreground",
    "destructive(fill)",
    tokens["destructive-foreground"],
    tokens["destructive"],
    TEXT_CONTRAST_MIN,
  );
  push("background", "foreground(tooltip)", tokens["background"], tokens["foreground"], TEXT_CONTRAST_MIN);

  for (const inkName of ["primary", "destructive"]) {
    for (const bgId of ["base", "surface"] as const) {
      push(inkName, `tint10(${inkName},${bgId})`, tokens[inkName], tint(tokens[inkName], 0.1, bg[bgId]), TEXT_CONTRAST_MIN);
    }
  }

  for (const inkName of ["muted-foreground", "foreground"]) {
    for (const bgId of ["entryFillBase", "entryFillSurface", "entryHoverSurface"] as const) {
      push(inkName, bgId, tokens[inkName], bg[bgId], TEXT_CONTRAST_MIN);
    }
  }
  for (const bgId of ["outlineFillBase", "outlineFillSurface", "outlineHoverBase", "outlineHoverSurface"] as const) {
    push("foreground(outline-button)", bgId, tokens["foreground"], bg[bgId], TEXT_CONTRAST_MIN);
  }

  for (const bgId of DARK_CONTROL_BG_IDS) {
    for (const inkName of ["ring", "input", "destructive"]) {
      push(inkName, bgId, tokens[inkName], bg[bgId], NON_TEXT_CONTRAST_MIN);
    }
  }
  return cases;
}

const THEMES = [
  {
    theme: "light" as const,
    selector: ":root" as const,
    build: (tokens: Record<string, string>) => buildLightRegistry(tokens, buildLightBackgrounds(tokens)),
  },
  {
    theme: "dark" as const,
    selector: ".dark" as const,
    build: (tokens: Record<string, string>) => buildDarkRegistry(tokens, buildDarkBackgrounds(tokens)),
  },
];

describe.each(THEMES)("text/control contrast registry — $theme", ({ theme, selector, build }) => {
  const tokens = readThemeTokens(selector);
  const registry = build(tokens);

  it("the registry is non-empty", () => {
    expect(registry.length).toBeGreaterThan(0);
  });

  it.each(registry.map((c): [string, PairCase] => [`--${c.ink} on ${c.bg}`, c]))(
    "%s clears its AA floor",
    (_label, c) => {
      const ratio = contrastRatio(c.inkHex, c.bgHex);
      expect(
        ratio,
        `${theme}: --${c.ink} on ${c.bg} = ${ratio.toFixed(2)} < ${c.min}`,
      ).toBeGreaterThanOrEqual(c.min);
    },
  );
});
