/**
 * Lexical scanner enforcing that status/chart tone color never colors a text
 * word and never backs a translucent tint: a `text-status-*`/`text-chart-*`
 * class on a word, or a translucent `bg-status-*`/`bg-chart-*` modifier, both
 * bypass the ink-is-foreground / opaque-tint contract this change establishes
 * (see `docs/decisions/2026-09-27---01-status-ink-labels-and-contrast-gate.md`).
 * A helper, not a test file, so Vitest does not collect it;
 * `status-ink-hygiene.test.ts` and any downstream build that composes this
 * web-ui call `findToneColoredTextAndTranslucentTints`.
 */
import fs from "fs";
import { listScannableFiles } from "./palette-hygiene";

export type StatusInkCategory = "tone-text" | "translucent-tint";

export interface Finding {
  file: string;
  line: number;
  match: string;
  category: StatusInkCategory;
}

export interface AcceptedException {
  file: string;
  pattern: string;
  reason: string;
}

/** Deliberate, reviewed exceptions to the scanner. Any new one needs a deliberate edit here. */
export const ACCEPTED_EXCEPTIONS: AcceptedException[] = [
  {
    file: "src/components/runs/DecisionButtons.tsx",
    pattern: "hover:bg-status-success/90",
    reason: "A control fill (button hover state), not a text label or a badge/callout tint — out of scope for this change.",
  },
];

const TONE_TEXT_RE = /\btext-(?:status|chart)-[a-z0-9-]+/g;
// `(?:[a-z-]+:)*` swallows any variant prefix (`hover:`, `dark:`, `[a]:hover:`,
// …) ahead of the utility itself, so a translucent tint hidden behind a
// variant chain is still caught.
const TRANSLUCENT_TINT_RE = /\b(?:[a-z-]+:)*bg-(?:status|chart)-[a-z0-9-]+\/\d+/g;

function isAcceptedException(relPath: string, match: string): boolean {
  return ACCEPTED_EXCEPTIONS.some(
    (exception) => exception.file === relPath && new RegExp(exception.pattern).test(match),
  );
}

/** Pure: scans one file's already-read text. `relPath` is relative to the web-ui root. */
export function scanStatusInkSource(relPath: string, text: string): Finding[] {
  const findings: Finding[] = [];
  const lines = text.split("\n");
  const isTsx = relPath.endsWith(".tsx");

  lines.forEach((line, idx) => {
    const lineNumber = idx + 1;

    if (isTsx) {
      TONE_TEXT_RE.lastIndex = 0;
      let match: RegExpExecArray | null;
      while ((match = TONE_TEXT_RE.exec(line)) !== null) {
        if (isAcceptedException(relPath, match[0])) continue;
        findings.push({ file: relPath, line: lineNumber, match: match[0], category: "tone-text" });
      }
    }

    TRANSLUCENT_TINT_RE.lastIndex = 0;
    let match: RegExpExecArray | null;
    while ((match = TRANSLUCENT_TINT_RE.exec(line)) !== null) {
      if (isAcceptedException(relPath, match[0])) continue;
      findings.push({ file: relPath, line: lineNumber, match: match[0], category: "translucent-tint" });
    }
  });

  return findings;
}

/**
 * Walks the given files/directories (via `listScannableFiles`, shared with
 * the palette/ink scanners) and reports every tone-colored text word or
 * translucent status/chart tint found.
 */
export function findToneColoredTextAndTranslucentTints(paths: string[]): Finding[] {
  const findings: Finding[] = [];

  for (const { abs, relPath } of listScannableFiles(paths)) {
    const text = fs.readFileSync(abs, "utf-8");
    findings.push(...scanStatusInkSource(relPath, text));
  }

  return findings;
}
