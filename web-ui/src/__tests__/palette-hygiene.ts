/**
 * Lexical scanner for off-brand colors: raw Tailwind palette utilities, hex
 * literals and CSS color functions that bypass the `--status-*` / theme
 * token system. A helper, not a test file, so Vitest does not collect it;
 * `palette-hygiene.test.ts` and any downstream build that composes this
 * web-ui call `findOffBrandColors`.
 */
import fs from "fs";
import path from "path";

// Finding paths, and so the allow-list's `file` keys, are relative to this
// web-ui root; anchoring on process.cwd() instead makes every exception miss
// when Vitest runs from another directory.
const WEB_UI_ROOT = path.resolve(__dirname, "../..");

export type OffBrandCategory = "palette" | "white-black" | "hex" | "color-function";

export interface OffBrandFinding {
  file: string;
  line: number;
  match: string;
  category: OffBrandCategory;
}

export interface AcceptedColorException {
  file: string;
  pattern: string;
  reason: string;
}

/** Deliberate, reviewed exceptions to the scanner. Any new one needs a deliberate edit here. */
export const ACCEPTED_COLOR_EXCEPTIONS: AcceptedColorException[] = [
  {
    file: "src/components/Logo.tsx",
    pattern: "#907aa9|#56949f|#faf4ed",
    reason: "Frozen brand-mark hex values.",
  },
  {
    file: "src/components/layout/ActivityFeedButton.tsx",
    pattern: "text-white",
    reason: "Unread-count badge text, pending a foreground token.",
  },
];

const PALETTE_RE =
  /\b(?:bg|text|border|ring|fill|stroke|from|via|to|outline|decoration|divide|shadow|accent|caret|placeholder)-(?:red|orange|amber|yellow|lime|green|emerald|teal|cyan|sky|blue|indigo|violet|purple|fuchsia|pink|rose|slate|gray|zinc|neutral|stone)-\d{2,3}\b/g;
const WHITE_BLACK_RE =
  /\b(?:bg|text|border|ring|fill|stroke|from|via|to|outline)-(?:white|black)\b/g;
const HEX_RE = /#[0-9a-fA-F]{6}(?:[0-9a-fA-F]{2})?\b|\[#[0-9a-fA-F]{3,4}\]/g;
const COLOR_FN_RE = /\b(?:rgba?|hsla?|oklch|oklab|lab|lch|hwb)\(/g;

/** `bg-black/<n>` (a backdrop scrim) is the one allowed raw white/black utility. */
function isBackdropScrim(line: string, matchStart: number, match: string): boolean {
  if (match !== "bg-black") return false;
  return /^\/\d+\b/.test(line.slice(matchStart + match.length));
}

function isAcceptedException(relPath: string, match: string): boolean {
  return ACCEPTED_COLOR_EXCEPTIONS.some(
    (exception) => exception.file === relPath && new RegExp(exception.pattern).test(match)
  );
}

/** Pure: scans one file's already-read text. `relPath` is relative to the web-ui root. */
export function scanSource(relPath: string, text: string): OffBrandFinding[] {
  const findings: OffBrandFinding[] = [];
  const lines = text.split("\n");

  lines.forEach((line, idx) => {
    const lineNumber = idx + 1;

    for (const [category, re] of [
      ["palette", PALETTE_RE],
      ["white-black", WHITE_BLACK_RE],
      ["hex", HEX_RE],
      ["color-function", COLOR_FN_RE],
    ] as [OffBrandCategory, RegExp][]) {
      re.lastIndex = 0;
      let match: RegExpExecArray | null;
      while ((match = re.exec(line)) !== null) {
        if (category === "white-black" && isBackdropScrim(line, match.index, match[0])) continue;
        if (isAcceptedException(relPath, match[0])) continue;
        findings.push({ file: relPath, line: lineNumber, match: match[0], category });
      }
    }
  });

  return findings;
}

function shouldSkip(fileName: string): boolean {
  return fileName.includes(".test.") || fileName.includes(".spec.");
}

function walk(root: string, entry: string, out: string[]): void {
  const abs = path.join(root, entry);
  const stat = fs.statSync(abs);
  if (stat.isDirectory()) {
    if (path.basename(entry) === "__tests__") return;
    for (const child of fs.readdirSync(abs)) {
      walk(root, path.join(entry, child), out);
    }
    return;
  }
  if (!/\.tsx?$/.test(entry)) return;
  if (shouldSkip(path.basename(entry))) return;
  out.push(entry);
}

/**
 * Walks the given files/directories for `*.ts`/`*.tsx`, skipping
 * `__tests__/` directories and `*.test.*`/`*.spec.*` files, and reports
 * every off-brand color found.
 */
export function findOffBrandColors(paths: string[]): {
  findings: OffBrandFinding[];
  filesScanned: number;
} {
  const findings: OffBrandFinding[] = [];
  let filesScanned = 0;

  for (const target of paths) {
    // A missing target would otherwise shrink the scan silently while it still passes.
    if (!fs.existsSync(target)) throw new Error(`palette scan target does not exist: ${target}`);
    const stat = fs.statSync(target);
    const root = stat.isDirectory() ? target : path.dirname(target);
    const relFiles: string[] = [];
    if (stat.isDirectory()) {
      walk(target, "", relFiles);
    } else {
      const base = path.basename(target);
      if (/\.tsx?$/.test(base) && !shouldSkip(base)) relFiles.push(base);
    }

    for (const relFile of relFiles) {
      const abs = path.join(root, relFile);
      const text = fs.readFileSync(abs, "utf-8");
      const relPath = path.relative(WEB_UI_ROOT, abs).split(path.sep).join("/");
      findings.push(...scanSource(relPath, text));
      filesScanned += 1;
    }
  }

  return { findings, filesScanned };
}
