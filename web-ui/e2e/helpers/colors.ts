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
