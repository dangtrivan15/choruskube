/**
 * Lexical scanner for translucent inks and state indicators: anything that
 * lowers an AA-checked ink, focus ring/outline/border, or invalid-state
 * border below full opacity. A helper, not a test file, so Vitest does not
 * collect it; `ink-hygiene.test.ts` and any downstream build that composes
 * this web-ui call `findTranslucentInks`. See
 * docs/decisions/2026-09-26---01-aa-contrast-text-and-controls.md.
 */
import fs from "fs";
import { listScannableFiles } from "./palette-hygiene";

export type InkCategory = "ink" | "focus" | "invalid-border";

export interface InkFinding {
  file: string;
  line: number;
  match: string;
  category: InkCategory;
}

export interface AcceptedInkException {
  file: string;
  pattern: string;
  reason: string;
}

/** Deliberate, reviewed exceptions to the scanner. Any new one needs a deliberate edit here. */
export const ACCEPTED_INK_EXCEPTIONS: AcceptedInkException[] = [
  {
    file: "src/components/ui/EmptyState.tsx",
    pattern: "text-muted-foreground/40",
    reason: "Decorative empty-state illustration; not text.",
  },
];

// Opacity modifier: a plain fraction (`/50`) or a bracketed arbitrary value (`/[.7]`).
const OPACITY = String.raw`\/(?:\d+|\[[^\]]+\])`;

// Text/inverse inks the contrast gate holds to the 4.5:1 text floor.
const INK_TOKENS =
  "foreground|muted-foreground|primary|primary-foreground|destructive|destructive-foreground|secondary-foreground|accent-foreground|card-foreground|popover-foreground|sidebar-foreground|sidebar-accent-foreground|sidebar-primary-foreground|background";

interface PatternSpec {
  category: InkCategory;
  re: RegExp;
}

// `(?<![\w-])` rather than a whitespace/quote anchor, so a match still fires
// behind any variant chain: bracketed (`[a]:hover:`), slash-named groups
// (`group-hover/row:`) or a `!` prefix.
const PATTERNS: PatternSpec[] = [
  { category: "ink", re: new RegExp(String.raw`(?<![\w-])text-(?:${INK_TOKENS})${OPACITY}`, "g") },
  { category: "focus", re: new RegExp(String.raw`\bfocus-visible:(?:ring|outline|border)-[a-z-]+${OPACITY}`, "g") },
  { category: "focus", re: new RegExp(String.raw`(?<![\w-])(?:ring|outline)-ring${OPACITY}`, "g") },
  { category: "invalid-border", re: new RegExp(String.raw`\baria-invalid:border-[a-z-]+${OPACITY}`, "g") },
];

function isAcceptedException(relPath: string, match: string): boolean {
  return ACCEPTED_INK_EXCEPTIONS.some(
    (exception) => exception.file === relPath && new RegExp(exception.pattern).test(match),
  );
}

/** Pure: scans one file's already-read text. `relPath` is relative to the web-ui root. */
export function scanInkSource(relPath: string, text: string): InkFinding[] {
  const findings: InkFinding[] = [];
  const lines = text.split("\n");

  lines.forEach((line, idx) => {
    const lineNumber = idx + 1;
    const spans: { start: number; end: number; category: InkCategory; match: string }[] = [];

    for (const { category, re } of PATTERNS) {
      re.lastIndex = 0;
      let match: RegExpExecArray | null;
      while ((match = re.exec(line)) !== null) {
        spans.push({ start: match.index, end: match.index + match[0].length, category, match: match[0] });
      }
    }

    // Prefer the outer (longer) match when one span is fully nested inside
    // another on the same line, so e.g. `focus-visible:ring-ring/50` (matched
    // whole by the focus pattern, and again as `ring-ring/50` by the bare
    // ring/outline pattern) reports once.
    spans.sort((a, b) => a.start - b.start || b.end - a.end);
    const kept: typeof spans = [];
    for (const span of spans) {
      const containedInKept = kept.some((k) => span.start >= k.start && span.end <= k.end);
      if (containedInKept) continue;
      kept.push(span);
    }

    for (const span of kept) {
      if (isAcceptedException(relPath, span.match)) continue;
      findings.push({ file: relPath, line: lineNumber, match: span.match, category: span.category });
    }
  });

  return findings;
}

/**
 * Walks the given files/directories (via `listScannableFiles`, shared with
 * the palette scanner) and reports every translucent ink, focus color or
 * invalid-state border found.
 */
export function findTranslucentInks(paths: string[]): {
  findings: InkFinding[];
  filesScanned: number;
} {
  const findings: InkFinding[] = [];
  let filesScanned = 0;

  for (const { abs, relPath } of listScannableFiles(paths)) {
    const text = fs.readFileSync(abs, "utf-8");
    findings.push(...scanInkSource(relPath, text));
    filesScanned += 1;
  }

  return { findings, filesScanned };
}
