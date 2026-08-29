import { describe, it, expect } from "vitest";
import fs from "fs";
import path from "path";

/**
 * Anti-drift guard over index.css's light `:root` neutrals: pins them to the
 * canonical Rose Pine Dawn values (docs/decisions/2026-08-29---01-original-rose-pine-dawn-light-theme.md)
 * and asserts `:root` never falls out of parity with `.dark`.
 */
const cssPath = path.resolve(__dirname, "../../index.css");
const css = fs.readFileSync(cssPath, "utf-8");

function block(selector: string): string {
  const re = new RegExp(`${selector}\\s*{([^}]*)}`);
  const match = css.match(re);
  if (!match) {
    throw new Error(`Could not find ${selector} block in index.css`);
  }
  return match[1];
}

function tokenValue(cssBlock: string, token: string): string {
  const match = cssBlock.match(new RegExp(`--${token}:\\s*([^;]+);`));
  if (!match) {
    throw new Error(`Token --${token} not found`);
  }
  return match[1].trim();
}

function tokenNames(cssBlock: string): string[] {
  return Array.from(cssBlock.matchAll(/--([a-z0-9-]+):/g)).map((m) => m[1]);
}

describe("light :root neutrals equal canonical Rose Pine Dawn", () => {
  const root = block(":root");

  it.each([
    ["background", "#faf4ed"],
    ["foreground", "#575279"],
    ["card-foreground", "#575279"],
    ["popover", "#fffaf3"],
    ["popover-foreground", "#575279"],
    ["secondary", "#f2e9e1"],
    ["secondary-foreground", "#575279"],
    ["muted", "#f2e9e1"],
    ["accent", "#f2e9e1"],
    ["accent-foreground", "#575279"],
    ["sidebar", "#fffaf3"],
    ["sidebar-foreground", "#575279"],
    ["sidebar-accent", "#f2e9e1"],
    ["sidebar-accent-foreground", "#575279"],
  ])("--%s equals canonical Dawn %s", (token, canonical) => {
    expect(tokenValue(root, token)).toBe(canonical);
  });

  it.each([
    ["border", "rgba(87,82,121,0.08)"],
    ["input", "rgba(87,82,121,0.12)"],
    ["sidebar-border", "rgba(87,82,121,0.08)"],
  ])("--%s equals the corrected ink-alpha value %s", (token, canonical) => {
    expect(tokenValue(root, token)).toBe(canonical);
  });

  it("no drifted lilac literal remains in the light :root block", () => {
    for (const drifted of ["#3e3859", "#fafaf7", "#f1eef5", "#e6dfee", "62,56,89", "62, 56, 89"]) {
      expect(root).not.toContain(drifted);
    }
  });
});

describe(":root / .dark token parity", () => {
  it("every token defined in .dark is also defined in :root", () => {
    const root = block(":root");
    const dark = block("\\.dark");
    const rootNames = new Set(tokenNames(root));
    for (const name of tokenNames(dark)) {
      expect(rootNames.has(name)).toBe(true);
    }
  });
});
