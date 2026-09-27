/**
 * Test-only color math shared by chart-series-distinguishable.test.ts, the
 * text/control contrast gate (text-control-contrast.test.ts), the status/badge/
 * chart contrast gate (status-contrast.test.ts), and the Playwright helper
 * e2e/helpers/contrast.ts.
 */
import { readFileSync } from "fs";
import { fileURLToPath } from "url";

// Playwright imports this as ESM, where a module-scope `__dirname` throws. Keep
// `import.meta.url` in a local: Vite rewrites a literal `new URL(x, import.meta.url)`
// to a dev-server URL, which breaks the filesystem read under Vitest.
function cssPath(): string {
  const base = import.meta.url;
  return fileURLToPath(new URL("../../../index.css", base));
}

/** A theme block's (`:root` or `.dark`) custom-property values from index.css, keyed by name without the leading `--`. */
export function readThemeTokens(selector: ":root" | ".dark"): Record<string, string> {
  const css = readFileSync(cssPath(), "utf-8");
  const pattern = selector === ":root" ? /:root\s*{([^}]*)}/ : /\.dark\s*{([^}]*)}/;
  const match = css.match(pattern);
  if (!match) {
    throw new Error(`Could not find ${selector} block in index.css`);
  }
  const tokens: Record<string, string> = {};
  for (const m of match[1].matchAll(/--([a-z0-9-]+):\s*([^;]+);/g)) {
    tokens[m[1]] = m[2].trim();
  }
  return tokens;
}

/**
 * Parses `#rrggbb` into `[r, g, b]` (0–255). Throws on any other form, so a series
 * pointed at a translucent `rgba()` token fails the gate instead of being measured wrongly.
 */
export function parseHex(hex: string): [number, number, number] {
  const match = /^#([0-9a-fA-F]{6})$/.exec(hex);
  if (!match) {
    throw new Error(`Expected a #rrggbb hex color, got "${hex}"`);
  }
  const int = parseInt(match[1], 16);
  return [(int >> 16) & 0xff, (int >> 8) & 0xff, int & 0xff];
}

// WCAG 2.x's 0.03928 breakpoint and sRGB's 0.04045 pick the same branch for every
// 8-bit channel, so one conversion serves both the contrast ratio and OKLab.
function srgbToLinear(c: number): number {
  const v = c / 255;
  return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
}

function relativeLuminance([r, g, b]: [number, number, number]): number {
  return 0.2126 * srgbToLinear(r) + 0.7152 * srgbToLinear(g) + 0.0722 * srgbToLinear(b);
}

/** WCAG 2.x contrast ratio between two `#rrggbb` colors, in [1, 21]. */
export function contrastRatio(a: string, b: string): number {
  const la = relativeLuminance(parseHex(a));
  const lb = relativeLuminance(parseHex(b));
  const [lighter, darker] = la >= lb ? [la, lb] : [lb, la];
  return (lighter + 0.05) / (darker + 0.05);
}

type Vec3 = [number, number, number];

function matMul(m: readonly [Vec3, Vec3, Vec3], v: Vec3): Vec3 {
  return [
    m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
    m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
    m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2],
  ];
}

// Machado, Oliveira & Fernandes (2009) severity-1.0 simulation matrices, applied
// to linear-light sRGB.
const CVD_MATRICES: Record<"protan" | "deutan", readonly [Vec3, Vec3, Vec3]> = {
  protan: [
    [0.152286, 1.052583, -0.204868],
    [0.114503, 0.786281, 0.099216],
    [-0.003882, -0.048116, 1.051998],
  ],
  deutan: [
    [0.367322, 0.860646, -0.227968],
    [0.280085, 0.672501, 0.047413],
    [-0.01182, 0.04294, 0.968881],
  ],
};

function clamp01(v: number): number {
  return Math.min(1, Math.max(0, v));
}

function linearToOKLab([r, g, b]: Vec3): Vec3 {
  const l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
  const m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
  const s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
  return [
    0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
    1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
    0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
  ];
}

/**
 * Euclidean OKLab distance x100 between two `#rrggbb` colors. When `kind` is
 * given, both colors are first passed through the Machado 2009 severity-1.0
 * color-vision-deficiency simulation before the OKLab conversion.
 */
export function deltaE(a: string, b: string, kind?: "protan" | "deutan"): number {
  const toOKLab = (hex: string): Vec3 => {
    const [r, g, bChan] = parseHex(hex);
    let linear: Vec3 = [srgbToLinear(r), srgbToLinear(g), srgbToLinear(bChan)];
    if (kind) {
      linear = matMul(CVD_MATRICES[kind], linear).map(clamp01) as Vec3;
    }
    return linearToOKLab(linear);
  };
  const [L1, a1, b1] = toOKLab(a);
  const [L2, a2, b2] = toOKLab(b);
  return 100 * Math.sqrt((L1 - L2) ** 2 + (a1 - a2) ** 2 + (b1 - b2) ** 2);
}

export const CONTRAST_MIN = 3;
export const NORMAL_DELTA_E_MIN = 15;
export const CVD_DELTA_E_MIN = 8;

export const TEXT_CONTRAST_MIN = 4.5;
export const NON_TEXT_CONTRAST_MIN = 3;

function formatHex([r, g, b]: Vec3): string {
  const toByte = (v: number) => Math.round(v).toString(16).padStart(2, "0");
  return `#${toByte(r)}${toByte(g)}${toByte(b)}`;
}

/**
 * Parses `#rrggbb` or a comma-separated `rgb(...)`/`rgba(...)` string into its
 * channels and alpha (1 when absent). Throws on any other form (e.g. `oklab(...)`),
 * so a token the gate cannot resolve fails loudly instead of being measured wrongly.
 */
export function parseCssColor(value: string): { rgb: [number, number, number]; alpha: number } {
  const hexMatch = /^#([0-9a-fA-F]{6})$/.exec(value.trim());
  if (hexMatch) {
    return { rgb: parseHex(value.trim()), alpha: 1 };
  }
  const rgbMatch = /^rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*(?:,\s*([\d.]+)\s*)?\)$/.exec(
    value.trim(),
  );
  if (rgbMatch) {
    const [, r, g, b, a] = rgbMatch;
    return {
      rgb: [Number(r), Number(g), Number(b)],
      alpha: a === undefined ? 1 : Number(a),
    };
  }
  throw new Error(`Expected a #rrggbb or rgb()/rgba() color, got "${value}"`);
}

/**
 * Per-channel `round(alpha*fg + (1-alpha)*bg)` in gamma-encoded sRGB, returned as
 * `#rrggbb`. Models a translucent layer (`fgHex` at `alpha`) painted over an opaque
 * background.
 */
export function blendOver(fgHex: string, alpha: number, bgHex: string): string {
  const [fr, fg, fb] = parseHex(fgHex);
  const [br, bg, bb] = parseHex(bgHex);
  return formatHex([
    alpha * fr + (1 - alpha) * br,
    alpha * fg + (1 - alpha) * bg,
    alpha * fb + (1 - alpha) * bb,
  ]);
}

/**
 * Same arithmetic as `blendOver`, named for the "deepen toward a role" use rather
 * than the "translucent layer over a background" use — both are a weighted sRGB mix.
 */
export function mixSrgb(aHex: string, bHex: string, weightA: number): string {
  return blendOver(aHex, weightA, bHex);
}

/**
 * Composites a `#rrggbb`/`rgb()`/`rgba()` layer, at its own alpha, over an
 * opaque background — so a translucent token is modelled from its declared
 * value rather than from a copy of its channels or alpha.
 */
export function compositeOver(cssColor: string, bgHex: string): string {
  const { rgb, alpha } = parseCssColor(cssColor);
  return blendOver(formatHex(rgb), alpha, bgHex);
}

/** `[r, g, b]` (0–255, rounded per channel) as `#rrggbb`. */
export function toHex(rgb: [number, number, number]): string {
  return formatHex(rgb);
}

type RoseRole =
  | "base"
  | "surface"
  | "overlay"
  | "muted"
  | "subtle"
  | "text"
  | "love"
  | "gold"
  | "rose"
  | "pine"
  | "foam"
  | "iris"
  | "highlightLow"
  | "highlightMed"
  | "highlightHigh";

// Published Rose Pine Dawn palette (rosepinetheme.com), also catalogued in
// web-ui/docs/light-theme-rose-pine-audit.md.
export const DAWN: Record<RoseRole, string> = {
  base: "#faf4ed",
  surface: "#fffaf3",
  overlay: "#f2e9e1",
  muted: "#9893a5",
  subtle: "#797593",
  text: "#575279",
  love: "#b4637a",
  gold: "#ea9d34",
  rose: "#d7827e",
  pine: "#286983",
  foam: "#56949f",
  iris: "#907aa9",
  highlightLow: "#f4ede8",
  highlightMed: "#dfdad9",
  highlightHigh: "#cecacd",
};

// Published Rose Pine "main" (dark) palette (rosepinetheme.com). Every role but
// the highlight trio is also pinned in palette-canonical.test.ts's comments;
// highlightLow/Med/High have no in-repo mapping today (two of the three hexes
// appear under unrelated token names --border/--input, not under a highlight
// label), so they are sourced from upstream here instead.
export const MAIN: Record<RoseRole, string> = {
  base: "#191724",
  surface: "#1f1d2e",
  overlay: "#26233a",
  muted: "#6e6a86",
  subtle: "#908caa",
  text: "#e0def4",
  love: "#eb6f92",
  gold: "#f6c177",
  rose: "#ebbcba",
  pine: "#31748f",
  foam: "#9ccfd8",
  iris: "#c4a7e7",
  highlightLow: "#21202e",
  highlightMed: "#403d52",
  highlightHigh: "#524f67",
};

interface TintUtilityRule {
  strength: number;
  base: string;
}

/** Parses one `@utility bg-tint(-strong)?-*` rule's mix percentage and base var name out of `css`. */
function parseTintUtilityRule(css: string, literalName: string): TintUtilityRule {
  const pattern = new RegExp(
    `@utility ${literalName}\\s*\\{\\s*background-color:\\s*color-mix\\(in srgb,\\s*--value\\(--color-\\*\\)\\s*(\\d+(?:\\.\\d+)?)%,\\s*var\\((--[a-z0-9-]+)\\)\\);?\\s*\\}`,
  );
  const match = css.match(pattern);
  if (!match) {
    throw new Error(`Could not find an "@utility ${literalName}" rule in index.css`);
  }
  return { strength: Number(match[1]), base: match[2] };
}

/**
 * Reads the `bg-tint-*` / `bg-tint-strong-*` `@utility` rules from index.css and
 * returns their mix percentages and shared base surface token. Throws if either
 * rule is missing, so a renamed/removed utility fails the gate instead of being
 * silently skipped.
 */
export function readTintUtilities(): { tint: number; tintStrong: number; base: string } {
  const css = readFileSync(cssPath(), "utf-8");
  const tint = parseTintUtilityRule(css, "bg-tint-\\*");
  const tintStrong = parseTintUtilityRule(css, "bg-tint-strong-\\*");
  if (tint.base !== tintStrong.base) {
    throw new Error(
      `bg-tint-* mixes over ${tint.base} but bg-tint-strong-* mixes over ${tintStrong.base} — they must share a base surface`,
    );
  }
  return { tint: tint.strength, tintStrong: tintStrong.strength, base: tint.base };
}

/**
 * Composites `token`'s raw color (e.g. `status-success`, `chart-1`, `primary`,
 * `destructive`) over `theme`'s `--popover` at the `bg-tint-*`/`bg-tint-strong-*`
 * percentage read from index.css — the same math `bg-tint-<token>` renders in
 * the browser, so a gate comparing against this stays honest if the utility's
 * percentage or base ever changes.
 */
export function resolveTint(
  theme: ":root" | ".dark",
  token: string,
  strength: "tint" | "tintStrong",
): string {
  const tokens = readThemeTokens(theme);
  const utilities = readTintUtilities();
  const percent = strength === "tint" ? utilities.tint : utilities.tintStrong;
  const baseName = utilities.base.replace(/^--/, "");
  const baseColor = tokens[baseName];
  const rawTokenValue = tokens[token];
  if (rawTokenValue === undefined) {
    throw new Error(`Unknown token --${token} in ${theme}`);
  }
  if (baseColor === undefined) {
    throw new Error(`Unknown base surface token ${utilities.base} in ${theme}`);
  }
  return blendOver(rawTokenValue, percent / 100, baseColor);
}

/**
 * Every `#hex` gradient stop in the light-mode body background, plus each
 * stop composited with each `rgba()` radial overlay at its declared alpha.
 * Overlays are never stacked onto each other: their non-transparent regions
 * don't overlap at their real positions/sizes, so stacking would model a
 * color the page never actually renders.
 */
export function lightCanvasSamples(): string[] {
  const css = readFileSync(cssPath(), "utf-8");
  const match = css.match(
    /html:not\(\.dark\) body\s*\{\s*background:\s*([\s\S]*?)\s*background-attachment/,
  );
  if (!match) {
    throw new Error("Could not find the light body gradient declaration in index.css");
  }
  const declaration = match[1];
  const stops = Array.from(declaration.matchAll(/#[0-9a-fA-F]{6}/g)).map((m) => m[0]);
  const overlays = Array.from(declaration.matchAll(/rgba\([^)]*\)/g)).map((m) => m[0]);
  const samples = new Set<string>(stops);
  for (const stop of stops) {
    for (const overlay of overlays) {
      samples.add(compositeOver(overlay, stop));
    }
  }
  return Array.from(samples);
}

/** Every `lightCanvasSamples()` result, plus `--background`, with `--card` composited on top. */
export function lightCardSamples(): string[] {
  const tokens = readThemeTokens(":root");
  const canvases = [...lightCanvasSamples(), tokens["background"]];
  return canvases.map((canvas) => compositeOver(tokens["card"], canvas));
}
