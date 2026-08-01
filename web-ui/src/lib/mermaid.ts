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

/** Sequence-diagram arrow tokens. The bidirectional forms (`<<-->>`/`<<->>`)
 *  must come first: they share a `-->>`/`->>` suffix with the unidirectional
 *  forms, and without a longer-alternative-first ordering (combined with the
 *  lazy source group below) the leading `<<` would be swallowed into the
 *  source alias instead of matching the arrow. The remaining forms are
 *  longest-prefix-first within their own family so the alternation cannot
 *  match a two-character form's shorter prefix and strand a stray `-`/`>`
 *  character. Mirrors Mermaid's own compiled sequenceDiagram arrow grammar. */
const ARROW_TOKEN =
  "(?:<<-->>|<<->>|-->>|--x|--\\)|-->|->>|-x|-\\)|->)";
// The source-alias group (group 2) is lazy (`[^\s:]+?`), not greedy: a greedy
// match backtracks from the longest run downward and, for a two-character
// arrow like `-->` or a bidirectional arrow like `<<->>`, can settle for a
// match one (or two) characters too long — absorbing a leading dash/`<<` into
// the source token — before ever trying the correct, shorter split. Lazy
// matching grows from the shortest possible source token upward, so it stops
// at the first (and correct) position where the arrow alternation matches.
//
// The target-alias group (group 5) excludes `:` (`[^\s:]+` instead of `\S+`)
// so a message with no space after the colon (`actor->>API:OK`) can't have
// the label swallowed into the captured token. Group 4 captures Mermaid's
// optional `+`/`-` activation-shorthand modifier separately from the alias,
// since it must stay outside the quotes when rewritten (`+"API"`, not
// `"+API"`). Both groups exclude `"` so alias tokens can't be captured
// mid-quote.
const MESSAGE_LINE_RX = new RegExp(
  `^(\\s*)([^\\s:]+?)(\\s*${ARROW_TOKEN}\\s*)([+-]?)([^\\s:]+)(\\s*:.*)?$`
);

const NOTE_LINE_RX = /^(\s*Note\s+(?:over|left of|right of)\s+)(.+?)(\s*:.*)?$/i;

const ACTIVATE_LINE_RX =
  /^(\s*(?:activate|deactivate|destroy)\s+)("?)([^\s"]+)\2\s*$/i;

/** Matches Mermaid's participant-metadata statements (`link`, `links`,
 *  `properties`, `details`), each of which references a participant alias
 *  directly with no arrow. Group 3 (everything from the first `:` onward,
 *  often a JSON payload with its own nested colons) is left untouched. */
const LINK_LINE_RX =
  /^(\s*(?:link|links|properties|details)\s+)([^\s:]+)(\s*:.*)$/i;

/** Quotes a bare token if it exactly matches a collected alias and is not
 *  already quoted; otherwise returns it unchanged. */
function quoteIfAlias(token: string, aliases: Set<string>): string {
  if (token.startsWith('"') && token.endsWith('"')) return token;
  return aliases.has(token) ? `"${token}"` : token;
}

/** Adds `token` to `aliases` unless it's already quoted. */
function collectAlias(token: string, aliases: Set<string>): void {
  if (token.startsWith('"')) return;
  aliases.add(token);
}

/** Detects the diagram type from the source's first remaining non-blank line
 *  after skipping any leading preamble Mermaid itself accepts before a
 *  diagram's type declaration: a YAML frontmatter block (`---` ... `---`)
 *  and/or `%%`-prefixed lines (both plain `%% comment` lines and
 *  `%%{init: ...}%%` directive lines). Mermaid imposes no fixed order or
 *  count on how these forms may be interleaved, so this loops rather than
 *  assuming a fixed sequence. */
function isSequenceDiagram(source: string): boolean {
  const lines = source.split("\n");
  let i = 0;
  for (;;) {
    while (i < lines.length && lines[i].trim() === "") i++;
    if (i >= lines.length) return false;
    const trimmed = lines[i].trim();
    if (trimmed === "---") {
      i++;
      while (i < lines.length && lines[i].trim() !== "---") i++;
      i++;
      continue;
    }
    if (trimmed.startsWith("%%")) {
      i++;
      continue;
    }
    return trimmed === "sequenceDiagram";
  }
}

/** Mermaid's sequenceDiagram grammar reserves a fixed set of bare words
 *  (`actor`, `loop`, `end`, `note`, `link`, `properties`, …). A
 *  participant/actor alias that happens to collide with one of these —
 *  case-insensitively — fails to parse (`Expecting 'ACTOR', got 'INVALID'`)
 *  even though the diagram is otherwise well-formed. Mermaid accepts a
 *  quoted alias (`participant "Actor" as Any user` / `"Actor"->>API: ...`)
 *  with no collision, producing an identical rendered diagram (the visible
 *  label always comes from the `as` clause, never the alias token). Rather
 *  than maintain a reserved-word list that silently drifts on Mermaid
 *  upgrades, every alias is quoted unconditionally. This is a no-op for
 *  diagrams with no collision, and only rewrites structural positions
 *  (declarations, arrow endpoints, Note participant lists,
 *  activate/deactivate/destroy targets, link/links/properties/details
 *  references) — never the free-text label after a message's `:`.
 *
 *  Mermaid also lets a participant come into existence implicitly, with no
 *  `participant`/`actor` declaration line at all, the first time its alias
 *  appears in any of the structural positions above — so the alias
 *  collection pass (below) scans every one of those positions, not just
 *  declaration lines, to build its candidate set. */
export function quoteSequenceParticipantAliases(source: string): string {
  if (!isSequenceDiagram(source)) return source;

  const lines = source.split("\n");

  // Pass 1: collect every not-already-quoted alias, from every structural
  // position the rewrite pass below also recognizes (declarations, message
  // arrows, Note lists, activate/deactivate/destroy, link/links/properties/
  // details) — not declaration lines alone, since Mermaid allows a
  // participant's first structural reference to implicitly declare it.
  const aliases = new Set<string>();
  for (const line of lines) {
    const declMatch = PARTICIPANT_DECL_RX.exec(line);
    if (declMatch) {
      if (declMatch[2] !== '"') collectAlias(declMatch[3], aliases);
      continue;
    }

    const msgMatch = MESSAGE_LINE_RX.exec(line);
    if (msgMatch) {
      collectAlias(msgMatch[2], aliases);
      collectAlias(msgMatch[5], aliases);
      continue;
    }

    const noteMatch = NOTE_LINE_RX.exec(line);
    if (noteMatch) {
      noteMatch[2].split(",").forEach((entry) => collectAlias(entry.trim(), aliases));
      continue;
    }

    const activateMatch = ACTIVATE_LINE_RX.exec(line);
    if (activateMatch) {
      if (activateMatch[2] !== '"') collectAlias(activateMatch[3], aliases);
      continue;
    }

    const linkMatch = LINK_LINE_RX.exec(line);
    if (linkMatch) {
      collectAlias(linkMatch[2], aliases);
      continue;
    }
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
      const [, indent, left, arrow, modifier, right, rest = ""] = msgMatch;
      const healedLeft = quoteIfAlias(left, aliases);
      const healedRight = quoteIfAlias(right, aliases);
      return `${indent}${healedLeft}${arrow}${modifier}${healedRight}${rest}`;
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

    const linkMatch = LINK_LINE_RX.exec(line);
    if (linkMatch) {
      const [, prefix, alias, rest] = linkMatch;
      return `${prefix}${quoteIfAlias(alias, aliases)}${rest}`;
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
