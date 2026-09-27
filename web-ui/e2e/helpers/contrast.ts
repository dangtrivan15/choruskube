import type { Locator, Page } from "@playwright/test";
import { contrastRatio, composite, toHex, parseColor } from "../../src/lib/__tests__/helpers/colorMetrics";

export { resolveTint } from "../../src/lib/__tests__/helpers/colorMetrics";

export const TEXT_CONTRAST_MIN = 4.5;
export const MARK_CONTRAST_MIN = 3;

/**
 * Straight sRGB alpha compositing of a stack of translucent layers (in
 * paint order, bottom layer first — i.e. `layers[0]` is painted first, each
 * subsequent layer painted over the composite so far) onto an opaque
 * `baseHex`. Factored out as a pure function so the stacking order is
 * testable without a browser (see contrast.test.ts).
 */
export function compositeStack(layers: { rgb: [number, number, number]; alpha: number }[], baseHex: string): string {
  let result = baseHex;
  for (const layer of layers) {
    result = composite(toHex(layer.rgb), layer.alpha, result);
  }
  return result;
}

/**
 * Normalizes any computed color serialization (`rgb()`, `rgba()`, `oklab()`,
 * `color(srgb ...)`, `transparent`, …) to `[r, g, b, a]` by painting it onto a
 * 1x1 canvas and reading the pixel back — canvas `getImageData` always
 * returns premultiplied-free straight sRGB bytes regardless of how the browser
 * serialized the color, so this sidesteps parsing every CSS color-function
 * syntax by hand.
 */
export async function toRgba(page: Page, cssColor: string): Promise<[number, number, number, number]> {
  return page.evaluate((color) => {
    const canvas = document.createElement("canvas");
    canvas.width = 1;
    canvas.height = 1;
    const ctx = canvas.getContext("2d", { willReadFrequently: true })!;
    ctx.clearRect(0, 0, 1, 1);
    ctx.fillStyle = color;
    ctx.fillRect(0, 0, 1, 1);
    const [r, g, b, a] = ctx.getImageData(0, 0, 1, 1).data;
    return [r, g, b, a / 255] as [number, number, number, number];
  }, cssColor);
}

/**
 * Walks from `locator`'s element up through its ancestors, collecting each
 * `background-color` (as `{ rgb, alpha }` via `toRgba`), until it finds the
 * first opaque layer (or runs out of ancestors, in which case `var(--background)`
 * stands in for the page's own base). Composites the whole stack top-down
 * (furthest ancestor painted first) onto that opaque floor, so a `bg-tint-*`
 * badge sitting inside a translucent glass card resolves to what the pixel
 * actually renders, not just its own declared background.
 */
export async function effectiveBackground(locator: Locator): Promise<string> {
  const page = locator.page();
  const layers: { rgb: [number, number, number]; alpha: number }[] = [];
  let opaqueFloor: string | null = null;

  const handles = await locator.evaluateHandle((el) => {
    const chain: Element[] = [];
    let node: Element | null = el as Element;
    while (node) {
      chain.push(node);
      node = node.parentElement;
    }
    return chain;
  });
  const count = await handles.evaluate((chain) => chain.length);

  for (let i = 0; i < count; i++) {
    const bg = await handles.evaluate((chain, idx) => getComputedStyle(chain[idx]).backgroundColor, i);
    if (bg === "transparent" || bg === "rgba(0, 0, 0, 0)") continue;
    const [r, g, b, a] = await toRgba(page, bg);
    if (a >= 1) {
      opaqueFloor = toHex([r, g, b]);
      break;
    }
    layers.push({ rgb: [r, g, b], alpha: a });
  }
  await handles.dispose();

  if (opaqueFloor === null) {
    const bg = await toRgba(page, "var(--background)");
    opaqueFloor = toHex([bg[0], bg[1], bg[2]]);
  }

  // Layers were collected element-outward (nearest ancestor first); painting
  // proceeds outward-in, so reverse to paint the furthest ancestor first.
  return compositeStack(layers.reverse(), opaqueFloor);
}

/**
 * Text contrast for `locator`: for HTML text, the computed `color` (composited
 * over `effectiveBackground` if translucent) against that background; for an
 * SVG `<text>` element (Recharts axis/legend labels), the computed `fill`
 * against the same effective background.
 */
export async function textContrast(locator: Locator): Promise<number> {
  const page = locator.page();
  const tagName = await locator.evaluate((el) => el.tagName.toLowerCase());
  const colorProp = tagName === "text" ? "fill" : "color";
  const computed = await locator.evaluate((el, prop) => getComputedStyle(el).getPropertyValue(prop), colorProp);
  const [r, g, b, a] = await toRgba(page, computed);
  const bg = await effectiveBackground(locator);
  const textHex = a >= 1 ? toHex([r, g, b]) : composite(toHex([r, g, b]), a, bg);
  return contrastRatio(textHex, bg);
}

/** Raw computed `background-color` of `locator`'s pseudo-element, as a hex string comparable to a resolved color. */
export async function pseudoBackground(locator: Locator, pseudo: "::before" | "::after"): Promise<string> {
  const page = locator.page();
  const raw = await locator.evaluate((el, p) => getComputedStyle(el, p).backgroundColor, pseudo);
  const [r, g, b] = await toRgba(page, raw);
  return toHex([r, g, b]);
}

/** Computed pixel width of `locator`'s pseudo-element — `0` when the value is `auto` (i.e. not laid out as a flex item). */
export async function pseudoWidth(locator: Locator, pseudo: "::before" | "::after"): Promise<number> {
  const raw = await locator.evaluate((el, p) => getComputedStyle(el, p).width, pseudo);
  if (raw === "auto") return 0;
  const parsed = parseFloat(raw);
  return Number.isNaN(parsed) ? 0 : parsed;
}

// Re-exported so a spec importing from this module doesn't also need a
// separate import from colorMetrics for the handful of pure helpers it uses
// directly (e.g. asserting a measured tint equals `resolveTint(...)`).
export { contrastRatio, composite, toHex, parseColor };
