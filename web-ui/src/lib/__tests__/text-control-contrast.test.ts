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
  compositeOver,
  DAWN,
  TEXT_CONTRAST_MIN,
  NON_TEXT_CONTRAST_MIN,
} from "./helpers/colorMetrics";

const CSS_PATH = path.resolve(__dirname, "../../index.css");
const rawCss = readFileSync(CSS_PATH, "utf-8");

/** The darkest (lowest relative-luminance, so highest contrast against white) of opaque hexes. */
function darkestOf(hexes: string[]): string {
  return hexes.reduce((worst, candidate) =>
    contrastRatio(candidate, "#ffffff") > contrastRatio(worst, "#ffffff") ? candidate : worst,
  );
}

// ---------------------------------------------------------------------------
// Gradient-constants guard. The canvas floor below is computed from these
// constants and nowhere else, so the guard that pins them to the real
// gradient declaration is what keeps the modelled floor honest.
// ---------------------------------------------------------------------------
const GRADIENT_TINTS = {
  iris: "rgba(144,122,169,0.08)",
  foam: "rgba(86,148,159,0.06)",
  gold: "rgba(234,157,52,0.06)",
} as const;
const GRADIENT_STOPS = ["#faf4ed", "#f4ede8", "#f2e9e1"];

const normalizeCss = (s: string) => s.replace(/\s+/g, "");

/** Every rgba() tint and #rrggbb stop in the light body gradient declaration of `css`. */
function lightGradientColors(css: string): { tints: Set<string>; stops: Set<string> } {
  const match = css.match(
    /html:not\(\.dark\) body\s*{\s*background:\s*([\s\S]*?)\s*background-attachment/,
  );
  if (!match) {
    throw new Error("Could not find the light body gradient declaration in index.css");
  }
  const declaration = match[1];
  return {
    tints: new Set(Array.from(declaration.matchAll(/rgba\([^)]*\)/g)).map((m) => normalizeCss(m[0]))),
    stops: new Set(Array.from(declaration.matchAll(/#[0-9a-fA-F]{6}/g)).map((m) => m[0])),
  };
}

const MODELLED_TINTS = new Set(Object.values(GRADIENT_TINTS).map(normalizeCss));
const MODELLED_STOPS = new Set(GRADIENT_STOPS);

describe("light gradient tints/stops match the modelled canvas-floor constants", () => {
  it("the html:not(.dark) body background declaration equals the modelled set", () => {
    const { tints, stops } = lightGradientColors(rawCss);
    expect(tints).toEqual(MODELLED_TINTS);
    expect(stops).toEqual(MODELLED_STOPS);
  });

  it("an extra radial tint in the declaration no longer equals the modelled set", () => {
    const withExtraTint = rawCss.replace(
      /(html:not\(\.dark\) body\s*{\s*background:)/,
      "$1\n    radial-gradient(circle, rgba(87,82,121,0.2), transparent 70%),",
    );
    expect(lightGradientColors(withExtraTint).tints).not.toEqual(MODELLED_TINTS);
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

  // Each tint at full strength over the darkest stop: a pessimistic floor.
  const S = darkestOf(GRADIENT_STOPS);
  const canvasFloorIris = compositeOver(GRADIENT_TINTS.iris, S);
  const canvasFloorFoam = compositeOver(GRADIENT_TINTS.foam, S);
  const canvasFloorGold = compositeOver(GRADIENT_TINTS.gold, S);
  const darkestFloor = darkestOf([canvasFloorIris, canvasFloorFoam, canvasFloorGold]);

  const cardOnFloor = compositeOver(tokens["card"], darkestFloor);
  const glassOnFloor = compositeOver(tokens["surface-glass"], darkestFloor);

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

  const glass = compositeOver(tokens["surface-glass"], base);

  // The `--muted` *token* (Rose Pine overlay in dark), not the Rose Pine
  // "muted" role that `--input` takes.
  const entryFillBase = blendOver(overlay, 0.5, base);
  const entryFillSurface = blendOver(overlay, 0.5, surface);
  const entryHoverSurface = blendOver(overlay, 0.7, surface);

  const input = tokens["input"];
  const outlineFillBase = blendOver(input, 0.3, base);
  const outlineFillSurface = blendOver(input, 0.3, surface);
  const outlineHoverBase = blendOver(input, 0.5, base);
  const outlineHoverSurface = blendOver(input, 0.5, surface);

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
      push(inkName, `tint10(${inkName},${bgId})`, tokens[inkName], blendOver(tokens[inkName], 0.1, bg[bgId]), TEXT_CONTRAST_MIN);
    }
  }
  for (const bgId of ["canvasFloorIris", "canvasFloorFoam", "canvasFloorGold"] as const) {
    push(
      "destructive",
      `tint5(destructive,${bgId})`,
      tokens["destructive"],
      blendOver(tokens["destructive"], 0.05, bg[bgId]),
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
      push(inkName, `tint10(${inkName},${bgId})`, tokens[inkName], blendOver(tokens[inkName], 0.1, bg[bgId]), TEXT_CONTRAST_MIN);
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

describe("the registry catches a regressed ink", () => {
  it("reverting light --muted-foreground to canonical subtle fails its pair on base", () => {
    const reverted = { ...readThemeTokens(":root"), "muted-foreground": DAWN.subtle };
    const failing = buildLightRegistry(reverted, buildLightBackgrounds(reverted))
      .filter((c) => contrastRatio(c.inkHex, c.bgHex) < c.min)
      .map((c) => `--${c.ink} on ${c.bg}`);
    expect(failing).toContain("--muted-foreground on base");
  });
});
