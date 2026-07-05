import { describe, it, expect } from "vitest";
import fs from "fs";
import path from "path";
import { THEME_COOKIE, DEFAULT_THEME } from "@/lib/theme";

/**
 * Sync tests ensure the FOUC prevention script in index.html and
 * the theme utilities in src/lib/theme.ts agree on the cookie contract.
 */
describe("FOUC / theme.ts sync", () => {
  const indexHtml = fs.readFileSync(
    path.resolve(__dirname, "../../../index.html"),
    "utf-8",
  );
  const themeTs = fs.readFileSync(
    path.resolve(__dirname, "../theme.ts"),
    "utf-8",
  );

  it("index.html contains the theme cookie name", () => {
    expect(indexHtml).toContain("theme=");
  });

  it("theme.ts exports THEME_COOKIE as theme", () => {
    expect(THEME_COOKIE).toBe("theme");
    expect(themeTs).toContain('THEME_COOKIE = "theme"');
  });

  it("FOUC script only adds .dark when cookie is dark (default is light)", () => {
    // The script should only add .dark — never explicitly set light.
    // Light is the default (no class = light = :root CSS).
    expect(indexHtml).toContain('=== "dark"');
    expect(indexHtml).toContain("classList.add");
    expect(indexHtml).not.toContain('classList.add("light")');
  });

  it("DEFAULT_THEME in theme.ts is light", () => {
    expect(DEFAULT_THEME).toBe("light");
  });
});
