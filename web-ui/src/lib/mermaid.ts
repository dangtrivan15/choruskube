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

/** Matches a `participant`/`actor` declaration line (optionally prefixed by
 *  `create`), capturing an optional leading quote (group 1) and the alias
 *  token itself (group 2). Used both to collect aliases (pass 1) and to
 *  rewrite declaration lines (pass 2). */
const PARTICIPANT_DECL_RX =
  /^(\s*(?:create\s+)?(?:participant|actor)\s+)("?)([^\s"]+)\2(\s+as\s+.*)?$/i;

/** Sequence-diagram arrow tokens, longest-prefix-first within each family so the
 *  alternation cannot match a two-character form's shorter prefix and strand a
 *  stray `-`/`>` character. Mirrors Mermaid's own sequenceDiagram arrow grammar. */
const ARROW_TOKEN = "(?:-->>|--x|--\\)|-->|->>|-x|-\\)|->)";
// The left-hand capture (group 2) is lazy (`\S+?`), not greedy: a greedy match
// backtracks from the longest run of non-space characters downward, and for a
// two-character arrow like `-->` it can settle for a match one character too
// long (absorbing a leading dash into the left token and leaving only `->`
// for the arrow) before ever trying the correct, shorter split. Lazy matching
// grows from the shortest possible left token upward, so it stops at the
// first (and correct) position where the arrow alternation matches in full.
const MESSAGE_LINE_RX = new RegExp(
  `^(\\s*)(\\S+?)(\\s*${ARROW_TOKEN}\\s*)(\\S+)(\\s*:.*)?$`
);

const NOTE_LINE_RX = /^(\s*Note\s+(?:over|left of|right of)\s+)(.+?)(\s*:.*)?$/i;

const ACTIVATE_LINE_RX =
  /^(\s*(?:activate|deactivate|destroy)\s+)("?)([^\s"]+)\2\s*$/i;

/** Quotes a bare token if it exactly matches a collected alias and is not
 *  already quoted; otherwise returns it unchanged. */
function quoteIfAlias(token: string, aliases: Set<string>): string {
  if (token.startsWith('"') && token.endsWith('"')) return token;
  return aliases.has(token) ? `"${token}"` : token;
}

/** Detects the diagram type from the source's first non-blank line, skipping
 *  an optional leading `%%{init: ...}%%` directive line. */
function isSequenceDiagram(source: string): boolean {
  const lines = source.split("\n");
  for (const line of lines) {
    const trimmed = line.trim();
    if (trimmed === "") continue;
    if (/^%%\{.*\}%%$/.test(trimmed)) continue;
    return trimmed === "sequenceDiagram";
  }
  return false;
}

/** Mermaid's sequenceDiagram grammar reserves a fixed set of bare words
 *  (`actor`, `loop`, `end`, `note`, …). A participant/actor alias that
 *  happens to collide with one of these — case-insensitively — fails to
 *  parse (`Expecting 'ACTOR', got 'INVALID'`) even though the diagram is
 *  otherwise well-formed. Mermaid accepts a quoted alias
 *  (`participant "Actor" as Any user` / `"Actor"->>API: ...`) with no
 *  collision, producing an identical rendered diagram (the visible label
 *  always comes from the `as` clause, never the alias token). Rather than
 *  maintain a reserved-word list that silently drifts on Mermaid upgrades,
 *  every declared alias is quoted unconditionally. This is a no-op for
 *  diagrams with no collision, and only rewrites structural positions
 *  (declarations, arrow endpoints, Note participant lists,
 *  activate/deactivate/destroy targets) — never the free-text label after
 *  a message's `:`. */
export function quoteSequenceParticipantAliases(source: string): string {
  if (!isSequenceDiagram(source)) return source;

  const lines = source.split("\n");

  // Pass 1: collect every not-already-quoted declared alias.
  const aliases = new Set<string>();
  for (const line of lines) {
    const match = PARTICIPANT_DECL_RX.exec(line);
    if (!match) continue;
    const quote = match[2];
    const alias = match[3];
    if (quote === '"') continue; // already quoted — leave alone
    aliases.add(alias);
  }

  if (aliases.size === 0) return source;

  // Pass 2: rewrite structural occurrences only.
  const healedLines = lines.map((line) => {
    const declMatch = PARTICIPANT_DECL_RX.exec(line);
    if (declMatch) {
      const [, prefix, quote, alias, asSuffix = ""] = declMatch;
      if (quote === '"') return line;
      if (!aliases.has(alias)) return line;
      return `${prefix}"${alias}"${asSuffix}`;
    }

    const msgMatch = MESSAGE_LINE_RX.exec(line);
    if (msgMatch) {
      const [, indent, left, arrow, right, rest = ""] = msgMatch;
      const healedLeft = quoteIfAlias(left, aliases);
      const healedRight = quoteIfAlias(right, aliases);
      return `${indent}${healedLeft}${arrow}${healedRight}${rest}`;
    }

    const noteMatch = NOTE_LINE_RX.exec(line);
    if (noteMatch) {
      const [, prefix, participantList, rest = ""] = noteMatch;
      const healedList = participantList
        .split(",")
        .map((entry) => quoteIfAlias(entry.trim(), aliases))
        .join(", ");
      return `${prefix}${healedList}${rest}`;
    }

    const activateMatch = ACTIVATE_LINE_RX.exec(line);
    if (activateMatch) {
      const [, prefix, quote, alias] = activateMatch;
      if (quote === '"') return line;
      if (!aliases.has(alias)) return line;
      return `${prefix}"${alias}"`;
    }

    return line;
  });

  return healedLines.join("\n");
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
