/**
 * Leading block-markdown markers this preview strips from the first line:
 * heading `#`s, blockquote `>`, and both bullet (`-`/`*`/`+`) and ordered
 * (`1.`/`1)`) list markers.
 */
const LEADING_MARKERS = /^(#{1,6}\s+|>\s*|[-*+]\s+|\d+[.)]\s+)+/;

/** Code-span backticks, stripped wherever they appear. */
const CODE_MARKERS = /`+/g;

/**
 * Emphasis runs (`**bold**`, `_em_`) only where they open or close a word. An intra-word `_`
 * is part of an identifier (`git_repo_id`), and stripping it would misquote the request.
 * Captures the preceding character instead of using a lookbehind, which older Safari rejects.
 */
const EMPHASIS_MARKERS = /(^|[^\p{L}\p{N}*_])[*_]+|[*_]+(?=[^\p{L}\p{N}]|$)/gu;

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
    .replace(CODE_MARKERS, "")
    .replace(EMPHASIS_MARKERS, "$1")
    .trim();
}
