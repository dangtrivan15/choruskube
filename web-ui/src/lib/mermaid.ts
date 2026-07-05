/** PARKED (not currently called from MarkdownViewer). Mermaid's arrow-token
 *  grammar accepts only ASCII hyphen-minus (U+002D); en-dash (U+2013) and
 *  em-dash (U+2014) are not valid in arrow tokens. This heal was originally
 *  added on the hypothesis that AI generation emits Unicode dashes in arrows,
 *  but that pathology has not been confirmed in production content. Kept here
 *  (with passing tests) so we can re-enable cheaply if the failure mode does
 *  surface, without re-deriving the regex and edge cases. */
const ARROW_TOKEN_DASH_RX = /[–—]+(?=[>x)])/g;

export function healMermaidArrows(source: string): string {
  return source.replace(ARROW_TOKEN_DASH_RX, (match) =>
    match
      .split("")
      .map((ch) => (ch === "—" ? "--" : "-"))
      .join("")
  );
}

/** Mermaid treats `;` as a statement separator inside diagram source. When AI
 *  (or any author) emits a `;` mid-sentence inside a sequenceDiagram message
 *  label, the parser terminates the statement at the semicolon and then fails
 *  on the trailing text. Mermaid's canonical workaround is to escape `;` as the
 *  numeric character reference `#59;` (see the sequenceDiagram syntax docs).
 *
 *  Heuristic: for each line, if it contains a `:`, treat the text after the
 *  first `:` as message-label content (this is true for `Actor ARROW Actor:
 *  text`, `Note over X, Y: text`, `accTitle:`, etc.) and rewrite `;` →
 *  `#59;` there. Lines without `:` (participant, loop, alt, opt, blank,
 *  keyword-only) are left alone. `;` that already closes an entity reference
 *  like `#9829;` or `#hearts;` is preserved as-is so we don't double-escape. */
export function escapeMermaidSemicolons(source: string): string {
  return source
    .split("\n")
    .map((line) => {
      const colonIdx = line.indexOf(":");
      if (colonIdx === -1) return line;
      const prefix = line.slice(0, colonIdx + 1);
      const label = line.slice(colonIdx + 1);
      const escaped = label.replace(
        /;/g,
        (_m, offset: number, str: string) => {
          // Walk back over [A-Za-z0-9]+; if we land on `#`, this `;` already
          // closes an entity reference — leave it intact.
          let i = offset - 1;
          while (i >= 0 && /[A-Za-z0-9]/.test(str[i])) i--;
          const closesEntity =
            i >= 0 && str[i] === "#" && i < offset - 1;
          return closesEntity ? ";" : "#59;";
        }
      );
      return prefix + escaped;
    })
    .join("\n");
}
