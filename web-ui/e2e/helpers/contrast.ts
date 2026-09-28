import type { Locator, Page } from "@playwright/test";
import { blendOver, contrastRatio, toHex } from "../../src/lib/__tests__/helpers/colorMetrics";

// Re-exported so specs (including a downstream build's specs that compose this
// helper) take thresholds and maths from the one module the unit gates use.
export {
  contrastRatio,
  toHex,
  resolveTint,
  TEXT_CONTRAST_MIN,
  NON_TEXT_CONTRAST_MIN as MARK_CONTRAST_MIN,
} from "../../src/lib/__tests__/helpers/colorMetrics";

type Layer = { rgb: [number, number, number]; alpha: number };

/**
 * Straight sRGB alpha compositing of translucent `layers`, in paint order
 * (`layers[0]` painted first), onto an opaque `baseHex`.
 */
export function compositeStack(layers: Layer[], baseHex: string): string {
  let result = baseHex;
  for (const layer of layers) {
    result = blendOver(toHex(layer.rgb), layer.alpha, result);
  }
  return result;
}

/**
 * Normalizes any CSS color — `var(--x)`, `rgb()`, `oklab()`, `color(srgb …)`,
 * `transparent` — to `[r, g, b, a]` (a in 0–1). The value is resolved through
 * computed style first: a canvas `fillStyle` silently ignores `var()`, and would
 * otherwise read back its default black.
 */
export async function toRgba(page: Page, cssColor: string): Promise<[number, number, number, number]> {
  return page.evaluate((color) => {
    const probe = document.createElement("span");
    probe.style.color = color;
    document.body.appendChild(probe);
    const resolved = getComputedStyle(probe).color;
    probe.remove();

    const canvas = document.createElement("canvas");
    canvas.width = 1;
    canvas.height = 1;
    const ctx = canvas.getContext("2d", { willReadFrequently: true })!;
    ctx.fillStyle = resolved;
    ctx.fillRect(0, 0, 1, 1);
    const [r, g, b, a] = ctx.getImageData(0, 0, 1, 1).data;
    return [r, g, b, a / 255] as [number, number, number, number];
  }, cssColor);
}

/**
 * The opaque color `locator`'s element paints over: its own and its ancestors'
 * `background-color`s composited down to the first opaque one, or onto
 * `var(--background)` when none is opaque (light mode's body carries only a
 * gradient image, so this approximates the gradient by its lightest stop).
 */
export async function effectiveBackground(locator: Locator): Promise<string> {
  const page = locator.page();
  const chain = await locator.evaluate((el) => {
    const colors: string[] = [];
    for (let node: Element | null = el; node; node = node.parentElement) {
      colors.push(getComputedStyle(node).backgroundColor);
    }
    return colors;
  });

  const layers: Layer[] = [];
  let opaqueFloor: string | null = null;
  for (const color of chain) {
    const [r, g, b, a] = await toRgba(page, color);
    if (a === 0) continue;
    if (a >= 1) {
      opaqueFloor = toHex([r, g, b]);
      break;
    }
    layers.push({ rgb: [r, g, b], alpha: a });
  }
  if (opaqueFloor === null) {
    const [r, g, b] = await toRgba(page, "var(--background)");
    opaqueFloor = toHex([r, g, b]);
  }

  // Collected nearest-first; the furthest ancestor paints first.
  return compositeStack(layers.reverse(), opaqueFloor);
}

/**
 * WCAG contrast of `locator`'s text against `effectiveBackground`: computed
 * `color` for HTML text, computed `fill` for an SVG `<text>` (Recharts labels).
 */
export async function textContrast(locator: Locator): Promise<number> {
  const page = locator.page();
  const computed = await locator.evaluate((el) =>
    getComputedStyle(el).getPropertyValue(el.tagName.toLowerCase() === "text" ? "fill" : "color"),
  );
  const [r, g, b, a] = await toRgba(page, computed);
  const bg = await effectiveBackground(locator);
  const textHex = a >= 1 ? toHex([r, g, b]) : blendOver(toHex([r, g, b]), a, bg);
  return contrastRatio(textHex, bg);
}

/** Alpha (0–1) of `locator`'s own computed `background-color`. */
export async function backgroundAlpha(locator: Locator): Promise<number> {
  const raw = await locator.evaluate((el) => getComputedStyle(el).backgroundColor);
  const [, , , a] = await toRgba(locator.page(), raw);
  return a;
}

/** Computed `background-color` of `locator`'s pseudo-element, as `#rrggbb`. */
export async function pseudoBackground(locator: Locator, pseudo: "::before" | "::after"): Promise<string> {
  const raw = await locator.evaluate((el, p) => getComputedStyle(el, p).backgroundColor, pseudo);
  const [r, g, b] = await toRgba(locator.page(), raw);
  return toHex([r, g, b]);
}

/** `cssColor` (e.g. `var(--status-warning)`) resolved in the current theme, as `#rrggbb`. */
export async function resolveHex(page: Page, cssColor: string): Promise<string> {
  const [r, g, b] = await toRgba(page, cssColor);
  return toHex([r, g, b]);
}

/** Computed pixel width of `locator`'s pseudo-element — `0` when `auto`, i.e. an inline box that ignores its size. */
export async function pseudoWidth(locator: Locator, pseudo: "::before" | "::after"): Promise<number> {
  const raw = await locator.evaluate((el, p) => getComputedStyle(el, p).width, pseudo);
  const parsed = parseFloat(raw);
  return Number.isNaN(parsed) ? 0 : parsed;
}
