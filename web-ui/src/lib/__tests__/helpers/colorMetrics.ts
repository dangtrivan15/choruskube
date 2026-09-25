/** Test-only color math for chart-series-distinguishable.test.ts. */
import { readFileSync } from "fs";
import path from "path";

const CSS_PATH = path.resolve(__dirname, "../../../index.css");

/** A theme block's (`:root` or `.dark`) custom-property values from index.css, keyed by name without the leading `--`. */
export function readThemeTokens(selector: ":root" | ".dark"): Record<string, string> {
  const css = readFileSync(CSS_PATH, "utf-8");
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
