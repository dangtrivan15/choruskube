import type { Page } from "@playwright/test";

/**
 * Resolves a CSS color expression (e.g. `"var(--status-success)"`) to the
 * browser's computed `rgb(...)`/`oklab(...)` serialization, by setting it on
 * a throwaway element and reading `getComputedStyle`. Always compare the
 * result against another computed value, never against a literal — the
 * serialization form depends on the browser and the color function used.
 */
export async function resolveColor(page: Page, cssColor: string): Promise<string> {
  return page.evaluate((color) => {
    const span = document.createElement("span");
    span.style.color = color;
    document.body.appendChild(span);
    const resolved = getComputedStyle(span).color;
    span.remove();
    return resolved;
  }, cssColor);
}

/** Toggles the app's light/dark theme via its header control. */
export async function toggleTheme(page: Page): Promise<void> {
  await page.getByRole("button", { name: "Toggle theme" }).click();
}

/**
 * Extracts the alpha channel from a computed CSS color string. Chromium
 * serializes `color-mix()` results and Tailwind's `/n` opacity modifiers as
 * `oklab(...)`, not `rgba(...)`, so a regex on `rgba(` alone misses most of
 * the values this module compares.
 */
export function alphaOf(color: string): number {
  if (color === "transparent") return 0;

  const rgbaMatch = color.match(/^rgba\(\s*[\d.]+\s*,\s*[\d.]+\s*,\s*[\d.]+\s*,\s*([\d.]+)\s*\)$/);
  if (rgbaMatch) return Number(rgbaMatch[1]);

  const slashMatch = color.match(/\/\s*([\d.]+%?)\s*\)$/);
  if (slashMatch) {
    const raw = slashMatch[1];
    return raw.endsWith("%") ? Number(raw.slice(0, -1)) / 100 : Number(raw);
  }

  return 1;
}

function parseOpaqueRgbTriplet(color: string): [number, number, number] {
  const match = /^rgba?\(\s*([\d.]+)\s*,\s*([\d.]+)\s*,\s*([\d.]+)\s*(?:,\s*([\d.]+)\s*)?\)$/.exec(
    color.trim(),
  );
  if (!match) {
    throw new Error(`Expected an rgb()/rgba() color, got "${color}"`);
  }
  const alpha = match[4] === undefined ? 1 : Number(match[4]);
  if (alpha < 1) {
    throw new Error(
      `Expected an opaque color (alpha 1) — resolve translucency to an opaque background first, got "${color}" (alpha ${alpha})`,
    );
  }
  return [Number(match[1]), Number(match[2]), Number(match[3])];
}

function srgbToLinear(c: number): number {
  const v = c / 255;
  return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
}

function relativeLuminance([r, g, b]: [number, number, number]): number {
  return 0.2126 * srgbToLinear(r) + 0.7152 * srgbToLinear(g) + 0.0722 * srgbToLinear(b);
}

/**
 * WCAG contrast ratio between two computed `rgb()`/`rgba()` strings. Throws on
 * `oklab(...)` or on any alpha < 1 — callers must measure opaque colors (an
 * element sitting on a solid background), not a translucent layer in isolation.
 */
export function contrastRatioRgb(a: string, b: string): number {
  const la = relativeLuminance(parseOpaqueRgbTriplet(a));
  const lb = relativeLuminance(parseOpaqueRgbTriplet(b));
  const [lighter, darker] = la >= lb ? [la, lb] : [lb, la];
  return (lighter + 0.05) / (darker + 0.05);
}

/** Splits `value` on `separator` only outside of any `(...)` nesting. */
function splitTopLevel(value: string, separator: string): string[] {
  const parts: string[] = [];
  let depth = 0;
  let current = "";
  for (const ch of value) {
    if (ch === "(") depth += 1;
    if (ch === ")") depth -= 1;
    if (ch === separator && depth === 0) {
      parts.push(current);
      current = "";
    } else {
      current += ch;
    }
  }
  parts.push(current);
  return parts;
}

/**
 * Returns the color of the `box-shadow` layer whose spread (the fourth length)
 * is non-zero — the ring/border-halo layer a focus style actually draws, as
 * opposed to Tailwind's other layers, which are `rgba(0, 0, 0, 0) 0px 0px 0px 0px`
 * placeholders. Throws if no layer has a non-zero spread, so a caller can't pass
 * vacuously on an unfocused element. A comma inside the layer's own color
 * function (`rgb(...)`, `oklab(...)`) is not a layer split point.
 */
export function ringColorOf(boxShadow: string): string {
  const layers = splitTopLevel(boxShadow, ",")
    .map((layer) => layer.trim())
    .filter(Boolean);

  for (const layer of layers) {
    const match = /^((?:rgba?|oklab|oklch|hsla?|color)\([^)]*\))\s*(.*)$/.exec(layer);
    if (!match) continue;
    const [, color, rest] = match;
    const lengths = rest.trim().split(/\s+/).filter(Boolean);
    if (lengths.length < 4) continue;
    const spread = parseFloat(lengths[3]);
    if (spread !== 0) return color;
  }

  throw new Error(`No box-shadow layer with a non-zero spread found in "${boxShadow}"`);
}
