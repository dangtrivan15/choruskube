/**
 * Leading block-markdown markers this preview strips from the first line:
 * heading `#`s, blockquote `>`, and both bullet (`-`/`*`/`+`) and ordered
 * (`1.`/`1)`) list markers.
 */
const LEADING_MARKERS = /^(#{1,6}\s+|>\s*|[-*+]\s+|\d+[.)]\s+)+/;

/** Inline emphasis/code markers stripped from anywhere in the line — `**bold**`, `_em_`, `` `code` ``. */
const INLINE_MARKERS = /[*_`]+/g;

/**
 * The first non-blank line of a markdown string, with leading block markers and inline
 * emphasis/code markers stripped, for a one-line preview row. There is no length cap —
 * the caller truncates with CSS, so this never needs to agree with any particular pixel
 * width.
 */
export function firstLinePreview(markdown: string | null): string {
  if (!markdown) return "";
  const line = markdown.split("\n").find((l) => l.trim().length > 0);
  if (!line) return "";
  return line
    .trim()
    .replace(LEADING_MARKERS, "")
    .replace(INLINE_MARKERS, "")
    .trim();
}
