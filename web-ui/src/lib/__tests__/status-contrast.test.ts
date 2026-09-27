/**
 * The status/badge/chart contrast gate: recomputes every label-on-tint, badge
 * variant, plain-ink, mark and tone-pair combination from index.css and the
 * recipe modules on each run, so a palette or recipe edit that drops a pair
 * below its floor is caught here instead of at eyeball review. See
 * docs/decisions/2026-09-27---01-status-ink-labels-and-contrast-gate.md.
 */
import { describe, it, expect } from "vitest";
import {
  readThemeTokens,
  resolveTint,
  lightCanvasSamples,
  lightCardSamples,
  contrastRatio,
  deltaE,
  TEXT_CONTRAST_MIN,
  MARK_CONTRAST_MIN,
} from "./helpers/colorMetrics";
import { STATUS_TONE_CLASSES, type StatusTone } from "../statusColors";
import { badgeVariants } from "@/components/ui/badge";

const STATUS_TONES: StatusTone[] = ["success", "error", "info", "warning", "accent", "neutral"];
const CHART_INDICES = [1, 2, 3, 4, 5];

/** Every color a `bg-tint-*`/`bg-tint-strong-*` utility is used with somewhere in this app. */
const TINT_TOKENS = [
  ...STATUS_TONES.map((t) => `status-${t}`),
  ...CHART_INDICES.map((n) => `chart-${n}`),
  "primary",
  "destructive",
];

const THEMES: { theme: "light" | "dark"; selector: ":root" | ".dark" }[] = [
  { theme: "light", selector: ":root" },
  { theme: "dark", selector: ".dark" },
];

// ---------------------------------------------------------------------------
// 1. Label on tint
// ---------------------------------------------------------------------------

describe.each(THEMES)("label on tint clears the text floor — $theme", ({ theme, selector }) => {
  const tokens = readThemeTokens(selector);

  it.each(TINT_TOKENS.flatMap((token) => (["tint", "tintStrong"] as const).map((s) => [token, s] as const)))(
    "--foreground on %s's %s clears 4.5:1",
    (token, strength) => {
      const bg = resolveTint(selector, token, strength);
      const ratio = contrastRatio(tokens["foreground"], bg);
      expect(ratio, `${theme}: --foreground on ${token} ${strength} = ${ratio.toFixed(2)}`).toBeGreaterThanOrEqual(
        TEXT_CONTRAST_MIN,
      );
    },
  );
});

// ---------------------------------------------------------------------------
// 2. Recipe shape
// ---------------------------------------------------------------------------

describe("recipe shape — badge and callout are ink-labeled, tint-backed", () => {
  it.each(STATUS_TONES)("%s's badge is ink-labeled, tinted, and carries its mark's hue dot", (tone) => {
    const { badge } = STATUS_TONE_CLASSES[tone];
    expect(badge).toContain("text-foreground");
    expect(badge).toContain(`bg-tint-status-${tone}`);
    expect(badge).toContain(`before:bg-status-${tone}`);
    expect(badge).not.toMatch(/\btext-status-/);
  });

  it.each(STATUS_TONES)("%s's callout is ink-labeled and tinted", (tone) => {
    const { callout } = STATUS_TONE_CLASSES[tone];
    expect(callout).toContain("text-foreground");
    expect(callout).toContain(`bg-tint-status-${tone}`);
  });
});

// ---------------------------------------------------------------------------
// 3 & 4. Badge variants and plain ink, against every surface
// ---------------------------------------------------------------------------

interface Surfaces {
  theme: "light" | "dark";
  selector: ":root" | ".dark";
  surfaces: Record<string, string>;
}

function buildSurfaces(): Surfaces[] {
  const lightTokens = readThemeTokens(":root");
  const lightSurfaces: Record<string, string> = {
    background: lightTokens["background"],
    popover: lightTokens["popover"],
  };
  lightCanvasSamples().forEach((hex, i) => (lightSurfaces[`canvas${i}`] = hex));
  lightCardSamples().forEach((hex, i) => (lightSurfaces[`card${i}`] = hex));

  const darkTokens = readThemeTokens(".dark");
  const darkSurfaces: Record<string, string> = {
    background: darkTokens["background"],
    card: darkTokens["card"],
    popover: darkTokens["popover"],
    muted: darkTokens["muted"],
  };

  return [
    { theme: "light", selector: ":root", surfaces: lightSurfaces },
    { theme: "dark", selector: ".dark", surfaces: darkSurfaces },
  ];
}

/**
 * A bare (no variant-prefix) *color* utility of the given kind — ignores
 * `[a]:hover:`, `focus-visible:`, `dark:`, etc., and non-color utilities that
 * happen to share the `bg-`/`text-` prefix (`text-xs`, a font-size utility).
 */
function activeUtilities(classString: string, kind: "bg" | "text", tokens: Record<string, string>): string[] {
  return classString.split(/\s+/).filter((token) => {
    if (token.includes(":") || !token.startsWith(`${kind}-`)) return false;
    const name = token.slice(kind.length + 1);
    return name.startsWith("tint-") || tokens[name] !== undefined;
  });
}

/**
 * Resolves a bare `bg-*`/`text-*` utility to a hex color for `selector`'s theme.
 * `bg-tint(-strong)?-<token>` routes through `resolveTint` (the same math the
 * browser renders); anything else is a plain CSS custom property, assumed opaque
 * (true of every token a badge variant actually uses).
 */
function resolveUtilityColor(selector: ":root" | ".dark", utility: string, tokens: Record<string, string>): string {
  const [kind, ...rest] = utility.split("-");
  const name = rest.join("-");
  if (kind === "bg" && name.startsWith("tint-strong-")) {
    return resolveTint(selector, name.slice("tint-strong-".length), "tintStrong");
  }
  if (kind === "bg" && name.startsWith("tint-")) {
    return resolveTint(selector, name.slice("tint-".length), "tint");
  }
  const value = tokens[name];
  if (value === undefined) throw new Error(`Unknown ${kind} utility token: ${utility}`);
  return value;
}

describe.each(buildSurfaces())("badge variants clear the text floor — $theme", ({ theme, selector, surfaces }) => {
  const tokens = readThemeTokens(selector);
  const VARIANTS = ["default", "secondary", "destructive", "outline"] as const;

  it.each(VARIANTS)("%s variant's text clears 4.5:1 against its background", (variant) => {
    const classString = badgeVariants({ variant });
    const textUtilities = activeUtilities(classString, "text", tokens);
    expect(textUtilities.length, `${variant} has no bare text-* color utility`).toBeGreaterThan(0);
    const textHex = resolveUtilityColor(selector, textUtilities[0], tokens);

    const bgUtilities = activeUtilities(classString, "bg", tokens);
    if (bgUtilities.length === 0) {
      // Transparent background (outline): surface-dependent — check every surface.
      for (const [surfaceName, surfaceHex] of Object.entries(surfaces)) {
        const ratio = contrastRatio(textHex, surfaceHex);
        expect(
          ratio,
          `${theme}: ${variant} text on ${surfaceName} = ${ratio.toFixed(2)}`,
        ).toBeGreaterThanOrEqual(TEXT_CONTRAST_MIN);
      }
    } else {
      // Opaque background: surface-independent by construction — one check suffices.
      const bgHex = resolveUtilityColor(selector, bgUtilities[0], tokens);
      const ratio = contrastRatio(textHex, bgHex);
      expect(ratio, `${theme}: ${variant} text on its own background = ${ratio.toFixed(2)}`).toBeGreaterThanOrEqual(
        TEXT_CONTRAST_MIN,
      );
    }
  });
});

describe.each(buildSurfaces())("plain ink clears the text floor on every surface — $theme", ({ theme, selector, surfaces }) => {
  const tokens = readThemeTokens(selector);

  it.each(Object.entries(surfaces))("--foreground on %s clears 4.5:1", (surfaceName, surfaceHex) => {
    const ratio = contrastRatio(tokens["foreground"], surfaceHex);
    expect(ratio, `${theme}: --foreground on ${surfaceName} = ${ratio.toFixed(2)}`).toBeGreaterThanOrEqual(
      TEXT_CONTRAST_MIN,
    );
  });
});

// ---------------------------------------------------------------------------
// 5. Marks — status/chart/primary token vs. --background and --popover at 3:1
// ---------------------------------------------------------------------------

const MARK_TOKENS = [...STATUS_TONES.map((t) => `status-${t}`), ...CHART_INDICES.map((n) => `chart-${n}`), "primary"];

/**
 * Marks the palette cannot lift above 3:1 against both `--background` and
 * `--popover` within the existing Rose Pine hues — computed against the real
 * palette (see the module doc comment), not the approximate figures a spec
 * may have guessed. Every one of these marks always renders beside its own
 * ink-colored word (the badge dot sits next to `text-foreground`, the DAG/graph
 * node icon sits next to the status word), so the state is never conveyed by
 * the mark's color alone.
 */
const MARK_RELIEF: Record<"light" | "dark", { token: string; cue: string }[]> = {
  light: [
    { token: "status-warning", cue: "status word beside the mark" },
    { token: "chart-3", cue: "status word beside the mark" },
    { token: "chart-5", cue: "status word beside the mark" },
  ],
  dark: [],
};

describe.each(THEMES)("marks clear 3:1 against background and popover, or are relieved — $theme", ({ theme, selector }) => {
  const tokens = readThemeTokens(selector);
  const reliefTokens = new Set(MARK_RELIEF[theme].map((r) => r.token));

  it.each(MARK_TOKENS)("%s", (token) => {
    const bg = contrastRatio(tokens[token], tokens["background"]);
    const pop = contrastRatio(tokens[token], tokens["popover"]);
    const passes = bg >= MARK_CONTRAST_MIN && pop >= MARK_CONTRAST_MIN;
    const relieved = reliefTokens.has(token);
    expect(
      passes || relieved,
      `${theme}: ${token} vs background=${bg.toFixed(2)} popover=${pop.toFixed(2)}, not in MARK_RELIEF`,
    ).toBe(true);
  });

  it("MARK_RELIEF's entries are exactly the tokens that actually fail 3:1", () => {
    const actuallyFailing = MARK_TOKENS.filter((token) => {
      const bg = contrastRatio(tokens[token], tokens["background"]);
      const pop = contrastRatio(tokens[token], tokens["popover"]);
      return bg < MARK_CONTRAST_MIN || pop < MARK_CONTRAST_MIN;
    });
    expect(new Set(reliefTokens)).toEqual(new Set(actuallyFailing));
  });

  it.each(MARK_RELIEF[theme])("relief entry %o carries a non-empty cue", (entry) => {
    expect(entry.cue.length).toBeGreaterThan(0);
  });
});

// ---------------------------------------------------------------------------
// 6. Tone pairs
// ---------------------------------------------------------------------------

const NORMAL_DELTA_E_MIN = 15;
const CVD_DELTA_E_MIN = 8;

function tonePairFails(a: string, b: string): boolean {
  const normal = deltaE(a, b);
  const protan = deltaE(a, b, "protan");
  const deutan = deltaE(a, b, "deutan");
  return normal < NORMAL_DELTA_E_MIN || protan < CVD_DELTA_E_MIN || deutan < CVD_DELTA_E_MIN;
}

function allTonePairs(): [StatusTone, StatusTone][] {
  const pairs: [StatusTone, StatusTone][] = [];
  for (let i = 0; i < STATUS_TONES.length; i++) {
    for (let j = i + 1; j < STATUS_TONES.length; j++) {
      pairs.push([STATUS_TONES[i], STATUS_TONES[j]]);
    }
  }
  return pairs;
}

/**
 * Tone pairs too close in hue (by CIE-ish OKLab ΔE, normal vision and a
 * Machado 2009 protan/deutan simulation — see colorMetrics.ts) to tell apart
 * by color alone within the existing Rose Pine hues — computed against the
 * real palette, not guessed. Every pairing here is always disambiguated by its
 * own ink-colored word (never two same-shaped dots with no label side by side).
 */
const TONE_PAIR_RELIEF: Record<"light" | "dark", { pair: [StatusTone, StatusTone]; cue: string }[]> = {
  light: [
    { pair: ["success", "error"], cue: "status word beside the mark" },
    { pair: ["success", "info"], cue: "status word beside the mark" },
    { pair: ["success", "accent"], cue: "status word beside the mark" },
    { pair: ["success", "neutral"], cue: "status word beside the mark" },
    { pair: ["error", "info"], cue: "status word beside the mark" },
    { pair: ["error", "accent"], cue: "status word beside the mark" },
    { pair: ["error", "neutral"], cue: "status word beside the mark" },
    { pair: ["info", "neutral"], cue: "status word beside the mark" },
    { pair: ["accent", "neutral"], cue: "status word beside the mark" },
  ],
  dark: [
    { pair: ["success", "accent"], cue: "status word beside the mark" },
    { pair: ["error", "neutral"], cue: "status word beside the mark" },
    { pair: ["info", "neutral"], cue: "status word beside the mark" },
    { pair: ["accent", "neutral"], cue: "status word beside the mark" },
  ],
};

describe.each(THEMES)("tone pairs clear ΔE floors, or are relieved — $theme", ({ theme, selector }) => {
  const tokens = readThemeTokens(selector);
  const reliefPairs = new Set(TONE_PAIR_RELIEF[theme].map(({ pair }) => pair.join("-")));

  it.each(allTonePairs())("%s vs %s", (a, b) => {
    const fails = tonePairFails(tokens[`status-${a}`], tokens[`status-${b}`]);
    const relieved = reliefPairs.has(`${a}-${b}`);
    expect(!fails || relieved, `${theme}: ${a} vs ${b} too close and not in TONE_PAIR_RELIEF`).toBe(true);
  });

  it("TONE_PAIR_RELIEF's entries are exactly the pairs that actually fail", () => {
    const actuallyFailing = allTonePairs()
      .filter(([a, b]) => tonePairFails(tokens[`status-${a}`], tokens[`status-${b}`]))
      .map(([a, b]) => `${a}-${b}`);
    expect(new Set(reliefPairs)).toEqual(new Set(actuallyFailing));
  });

  it.each(TONE_PAIR_RELIEF[theme])("relief entry %o carries a non-empty cue", (entry) => {
    expect(entry.cue.length).toBeGreaterThan(0);
  });
});
