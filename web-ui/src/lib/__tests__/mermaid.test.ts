import { describe, it, expect } from "vitest";
import {
  healMermaidArrows,
  escapeMermaidSemicolons,
  quoteSequenceParticipantAliases,
} from "../mermaid";

describe("healMermaidArrows", () => {
  it("rewrites en-dash before solid arrow (->>) — single en-dash → '-'", () => {
    expect(healMermaidArrows("A–>>B: hi")).toBe("A->>B: hi");
  });

  it("rewrites double en-dash before dotted arrow (-->>) — '––' → '--'", () => {
    expect(healMermaidArrows("A––>>B: hi")).toBe("A-->>B: hi");
  });

  it("rewrites em-dash before arrow — each '—' → '--'", () => {
    expect(healMermaidArrows("A—>>B: hi")).toBe("A-->>B: hi");
  });

  it("rewrites dash variants before cross-end (-x) and async (-))", () => {
    expect(healMermaidArrows("A–xB: bye")).toBe("A-xB: bye");
    expect(healMermaidArrows("A–)B: async")).toBe("A-)B: async");
  });

  it("preserves en-dash inside a message label (no arrow terminator after)", () => {
    const src = "A->>B: cost – $10 today";
    expect(healMermaidArrows(src)).toBe(src);
  });

  it("preserves em-dash inside a message label", () => {
    const src = "A->>B: Alice — the boss — agrees";
    expect(healMermaidArrows(src)).toBe(src);
  });

  it("leaves ASCII arrow source unchanged", () => {
    const src =
      "sequenceDiagram\n  A->>B: hi\n  B-->>A: bye\n  A--xB: gone\n";
    expect(healMermaidArrows(src)).toBe(src);
  });
});

describe("escapeMermaidSemicolons", () => {
  it("escapes `;` mid-message to `#59;` (Mermaid's canonical entity)", () => {
    expect(escapeMermaidSemicolons("  A->>B: width; no border")).toBe(
      "  A->>B: width#59; no border"
    );
  });

  it("escapes multiple `;` in a single message label", () => {
    expect(escapeMermaidSemicolons("  A->>B: x; y; z")).toBe(
      "  A->>B: x#59; y#59; z"
    );
  });

  it("preserves a `;` that already closes a numeric entity `#9829;`", () => {
    expect(escapeMermaidSemicolons("  A->>B: love #9829; you")).toBe(
      "  A->>B: love #9829; you"
    );
  });

  it("preserves a `;` that already closes a named entity `#hearts;`", () => {
    expect(escapeMermaidSemicolons("  A->>B: love #hearts; you")).toBe(
      "  A->>B: love #hearts; you"
    );
  });

  it("escapes the stray `;` even when an entity also appears on the same line", () => {
    expect(
      escapeMermaidSemicolons("  A->>B: love #9829; you; very much")
    ).toBe("  A->>B: love #9829; you#59; very much");
  });

  it("leaves lines without `:` untouched", () => {
    const src =
      "sequenceDiagram\n  participant A\n  participant B\n  loop Every minute";
    expect(escapeMermaidSemicolons(src)).toBe(src);
  });

  it("processes each line independently (multi-line diagram)", () => {
    const src = [
      "sequenceDiagram",
      "  participant A",
      "  A->>B: width; no border",
      "  B->>A: ok; thanks",
    ].join("\n");
    const expected = [
      "sequenceDiagram",
      "  participant A",
      "  A->>B: width#59; no border",
      "  B->>A: ok#59; thanks",
    ].join("\n");
    expect(escapeMermaidSemicolons(src)).toBe(expected);
  });

  it("escapes `;` in `Note` directive labels too (Note has `:` separator)", () => {
    expect(escapeMermaidSemicolons("Note over A, B: hi; there")).toBe(
      "Note over A, B: hi#59; there"
    );
  });

  it("leaves ASCII-without-semicolon source unchanged", () => {
    const src = "sequenceDiagram\n  A->>B: hello world\n";
    expect(escapeMermaidSemicolons(src)).toBe(src);
  });

  it("treats only the FIRST `:` per line as the label separator", () => {
    // The `:` inside the message label is part of the message, not a separator.
    expect(
      escapeMermaidSemicolons("  A->>B: GET /docs; HTTP/1.1")
    ).toBe("  A->>B: GET /docs#59; HTTP/1.1");
  });
});

describe("quoteSequenceParticipantAliases", () => {
  it("quotes a reserved-word alias (`actor`) at both declaration and message-line reference", () => {
    const src = [
      "sequenceDiagram",
      "  participant actor as Reviewer",
      "  actor->>API: Approve",
    ].join("\n");
    // `API` is never declared via a `participant`/`actor` line, but Mermaid
    // implicitly creates it as a participant the first time it's used as a
    // message target — so the collection pass picks it up too, and Decision
    // 2 (quote every alias unconditionally) means it gets quoted like `actor`.
    const expected = [
      "sequenceDiagram",
      '  participant "actor" as Reviewer',
      '  "actor"->>"API": Approve',
    ].join("\n");
    expect(quoteSequenceParticipantAliases(src)).toBe(expected);
  });

  it("quotes reserved-word aliases `loop` and `end` too", () => {
    const src = [
      "sequenceDiagram",
      "  participant loop as Looper",
      "  participant end as Ender",
      "  loop->>end: hi",
    ].join("\n");
    const expected = [
      "sequenceDiagram",
      '  participant "loop" as Looper',
      '  participant "end" as Ender',
      '  "loop"->>"end": hi',
    ].join("\n");
    expect(quoteSequenceParticipantAliases(src)).toBe(expected);
  });

  it("quotes a non-colliding alias too (unconditional quoting)", () => {
    const src = [
      "sequenceDiagram",
      "  participant API as api-server",
      "  participant DB as Database",
      "  API->>DB: query",
    ].join("\n");
    const expected = [
      "sequenceDiagram",
      '  participant "API" as api-server',
      '  participant "DB" as Database',
      '  "API"->>"DB": query',
    ].join("\n");
    expect(quoteSequenceParticipantAliases(src)).toBe(expected);
  });

  it("leaves an already-quoted alias as-is (no double quoting)", () => {
    const src = [
      "sequenceDiagram",
      '  participant "Actor" as Any user',
      '  participant "API" as api-server',
      '  "Actor"->>"API": hi',
    ].join("\n");
    expect(quoteSequenceParticipantAliases(src)).toBe(src);
  });

  it("heals a participant declared with no `as` label", () => {
    const src = ["sequenceDiagram", "  participant actor", "  actor->>API: hi"].join(
      "\n"
    );
    const expected = [
      "sequenceDiagram",
      '  participant "actor"',
      '  "actor"->>"API": hi',
    ].join("\n");
    expect(quoteSequenceParticipantAliases(src)).toBe(expected);
  });

  it("heals `Note over X,Y:` and `Note left of X:` forms, including a participant only ever referenced in a Note list", () => {
    // "Nobody" is never declared and never used as a message endpoint — only
    // named in this Note's participant list. Confirmed empirically via
    // mermaid.parse() that Mermaid still implicitly creates it as a
    // participant in that position (a bare, undeclared token in `Note over`
    // parses successfully), so it must be collected and quoted like any
    // other alias (this is unconditional).
    const src = [
      "sequenceDiagram",
      "  participant actor as Reviewer",
      "  participant API as api-server",
      "  Note over actor,API: reviewing",
      "  Note left of actor: waiting",
      "  Note over actor,Nobody: mixed",
    ].join("\n");
    const expected = [
      "sequenceDiagram",
      '  participant "actor" as Reviewer',
      '  participant "API" as api-server',
      '  Note over "actor", "API": reviewing',
      '  Note left of "actor": waiting',
      '  Note over "actor", "Nobody": mixed',
    ].join("\n");
    expect(quoteSequenceParticipantAliases(src)).toBe(expected);
  });

  it("heals activate/deactivate/create participant/destroy lines referencing a healed alias", () => {
    const src = [
      "sequenceDiagram",
      "  create participant actor as Reviewer",
      "  activate actor",
      "  deactivate actor",
      "  destroy actor",
    ].join("\n");
    const expected = [
      "sequenceDiagram",
      '  create participant "actor" as Reviewer',
      '  activate "actor"',
      '  deactivate "actor"',
      '  destroy "actor"',
    ].join("\n");
    expect(quoteSequenceParticipantAliases(src)).toBe(expected);
  });

  it("heals plain-line message arrows without an arrowhead symbol the same as arrowhead forms", () => {
    const src = [
      "sequenceDiagram",
      "  participant actor as Reviewer",
      "  participant API as api-server",
      "  actor->API: sync call",
      "  API-->actor: sync reply",
    ].join("\n");
    const expected = [
      "sequenceDiagram",
      '  participant "actor" as Reviewer',
      '  participant "API" as api-server',
      '  "actor"->"API": sync call',
      '  "API"-->"actor": sync reply',
    ].join("\n");
    expect(quoteSequenceParticipantAliases(src)).toBe(expected);
  });

  it("returns a non-sequenceDiagram source byte-for-byte unchanged", () => {
    const src = ["flowchart TD", "  actor-->API", "  API-->DB"].join("\n");
    expect(quoteSequenceParticipantAliases(src)).toBe(src);
  });

  it("is idempotent — running it twice matches running it once", () => {
    const src = [
      "sequenceDiagram",
      "  participant actor as Reviewer",
      "  actor->>API: Approve",
    ].join("\n");
    const once = quoteSequenceParticipantAliases(src);
    const twice = quoteSequenceParticipantAliases(once);
    expect(twice).toBe(once);
  });

  it("never rewrites the label text after `:`, even when it contains a collected alias's word", () => {
    const src = [
      "sequenceDiagram",
      "  participant Actor as Reviewer",
      "  participant API as api-server",
      "  Actor->>API: the Actor pattern",
    ].join("\n");
    const expected = [
      "sequenceDiagram",
      '  participant "Actor" as Reviewer',
      '  participant "API" as api-server',
      '  "Actor"->>"API": the Actor pattern',
    ].join("\n");
    expect(quoteSequenceParticipantAliases(src)).toBe(expected);
  });

  it("quotes a reserved-word alias that is never explicitly declared, used only via message-arrow lines", () => {
    const src = [
      "sequenceDiagram",
      "  actor->>API: Approve",
      "  API-->>actor: 200 OK",
    ].join("\n");
    // Both `actor` and `API` are collected — `API` is never declared but is
    // used as a message-arrow endpoint, which implicitly creates it as a
    // participant too, so the unconditional quoting covers it.
    const expected = [
      "sequenceDiagram",
      '  "actor"->>"API": Approve',
      '  "API"-->>"actor": 200 OK',
    ].join("\n");
    expect(quoteSequenceParticipantAliases(src)).toBe(expected);
  });

  it("quotes a never-declared reserved-word alias consistently across a message arrow, a Note, and activate/deactivate", () => {
    const src = [
      "sequenceDiagram",
      "  actor->>+API: Approve",
      "  Note over actor,API: reviewing",
      "  activate actor",
      "  deactivate actor",
    ].join("\n");
    const expected = [
      "sequenceDiagram",
      '  "actor"->>+"API": Approve',
      '  Note over "actor", "API": reviewing',
      '  activate "actor"',
      '  deactivate "actor"',
    ].join("\n");
    expect(quoteSequenceParticipantAliases(src)).toBe(expected);
  });

  it("quotes both an explicitly declared and a purely implicit reserved-word alias in the same diagram", () => {
    const src = [
      "sequenceDiagram",
      "  participant actor as Reviewer",
      "  actor->>loop: Approve",
    ].join("\n");
    const expected = [
      "sequenceDiagram",
      '  participant "actor" as Reviewer',
      '  "actor"->>"loop": Approve',
    ].join("\n");
    expect(quoteSequenceParticipantAliases(src)).toBe(expected);
  });

  it("quotes a reserved-word alias on the source side of a two-character arrow token (`-->>`, `--x`, `--)`)", () => {
    const src = [
      "sequenceDiagram",
      "  participant actor as Reviewer",
      "  participant API as api-server",
      "  actor-->>API: 200 OK",
      "  actor--xAPI: gone",
    ].join("\n");
    const expected = [
      "sequenceDiagram",
      '  participant "actor" as Reviewer',
      '  participant "API" as api-server',
      '  "actor"-->>"API": 200 OK',
      '  "actor"--x"API": gone',
    ].join("\n");
    expect(quoteSequenceParticipantAliases(src)).toBe(expected);
  });

  it("quotes the target alias correctly when there's no space after the colon or the label is empty", () => {
    const src = [
      "sequenceDiagram",
      "  participant actor as Reviewer",
      "  participant API as api-server",
      "  actor->>API:OK",
      "  actor->>API:",
    ].join("\n");
    const expected = [
      "sequenceDiagram",
      '  participant "actor" as Reviewer',
      '  participant "API" as api-server',
      '  "actor"->>"API":OK',
      '  "actor"->>"API":',
    ].join("\n");
    expect(quoteSequenceParticipantAliases(src)).toBe(expected);
  });

  it("quotes a reserved-word alias referenced with the activation-shorthand `+`/`-` modifier, preserving the modifier outside the quotes", () => {
    const src = [
      "sequenceDiagram",
      "  participant actor as Reviewer",
      "  participant API as api-server",
      "  actor->>+API: call",
      "  API-->>-actor: reply",
    ].join("\n");
    const expected = [
      "sequenceDiagram",
      '  participant "actor" as Reviewer',
      '  participant "API" as api-server',
      '  "actor"->>+"API": call',
      '  "API"-->>-"actor": reply',
    ].join("\n");
    expect(quoteSequenceParticipantAliases(src)).toBe(expected);
  });

  it("quotes a reserved-word alias referenced via bidirectional arrow forms, including combined with the activation modifier", () => {
    const src = [
      "sequenceDiagram",
      "  participant actor as Reviewer",
      "  participant API as api-server",
      "  actor<<->>API: hi",
      "  API<<-->>actor: hi",
      "  actor<<->>+API: hi",
    ].join("\n");
    const expected = [
      "sequenceDiagram",
      '  participant "actor" as Reviewer',
      '  participant "API" as api-server',
      '  "actor"<<->>"API": hi',
      '  "API"<<-->>"actor": hi',
      '  "actor"<<->>+"API": hi',
    ].join("\n");
    expect(quoteSequenceParticipantAliases(src)).toBe(expected);
  });

  it("quotes a reserved-word alias referenced via link/links/properties/details lines, leaving everything after the first colon untouched", () => {
    const src = [
      "sequenceDiagram",
      "  participant loop as Looper",
      "  link loop: Google@https://google.com",
      '  links loop: {"Google": "https://google.com"}',
      '  properties loop: {"class": "x", "nested": {"k": "v:w"}}',
      "  details loop: some free text",
    ].join("\n");
    const expected = [
      "sequenceDiagram",
      '  participant "loop" as Looper',
      '  link "loop": Google@https://google.com',
      '  links "loop": {"Google": "https://google.com"}',
      '  properties "loop": {"class": "x", "nested": {"k": "v:w"}}',
      '  details "loop": some free text',
    ].join("\n");
    expect(quoteSequenceParticipantAliases(src)).toBe(expected);
  });

  it("detects and heals a sequenceDiagram preceded by a leading YAML frontmatter block", () => {
    const src = [
      "---",
      "title: Approval",
      "---",
      "sequenceDiagram",
      "  participant actor as Reviewer",
      "  actor->>API: Approve",
    ].join("\n");
    const expected = [
      "---",
      "title: Approval",
      "---",
      "sequenceDiagram",
      '  participant "actor" as Reviewer',
      '  "actor"->>"API": Approve',
    ].join("\n");
    expect(quoteSequenceParticipantAliases(src)).toBe(expected);
  });

  it("detects and heals a sequenceDiagram preceded by frontmatter followed by an %%{init: ...}%% directive", () => {
    const src = [
      "---",
      "title: Approval",
      "---",
      "%%{init: {'theme': 'dark'}}%%",
      "sequenceDiagram",
      "  participant actor as Reviewer",
      "  actor->>API: Approve",
    ].join("\n");
    const expected = [
      "---",
      "title: Approval",
      "---",
      "%%{init: {'theme': 'dark'}}%%",
      "sequenceDiagram",
      '  participant "actor" as Reviewer',
      '  "actor"->>"API": Approve',
    ].join("\n");
    expect(quoteSequenceParticipantAliases(src)).toBe(expected);
  });

  it("detects and heals a sequenceDiagram preceded by a leading plain `%%` comment line (no directive body)", () => {
    const src = [
      "%% just a comment",
      "sequenceDiagram",
      "  participant actor as Reviewer",
      "  actor->>API: Approve",
    ].join("\n");
    const expected = [
      "%% just a comment",
      "sequenceDiagram",
      '  participant "actor" as Reviewer',
      '  "actor"->>"API": Approve',
    ].join("\n");
    expect(quoteSequenceParticipantAliases(src)).toBe(expected);
  });

  it("detects and heals a sequenceDiagram with comments, frontmatter, and an init directive interleaved in varying order/count", () => {
    const cases = [
      ["%% c1", "---", "title: t", "---", "sequenceDiagram"],
      ["---", "title: t", "---", "%% c1", "sequenceDiagram"],
      ["%% c1", "%% c2", "sequenceDiagram"],
      ["%% c1", "%%{init: {}}%%", "sequenceDiagram"],
      ["%%{init: {}}%%", "%% c1", "sequenceDiagram"],
    ];
    for (const preamble of cases) {
      const src = [...preamble, "  participant actor as Reviewer", "  actor->>API: Approve"].join(
        "\n"
      );
      const healed = quoteSequenceParticipantAliases(src);
      expect(healed).toContain('participant "actor" as Reviewer');
      expect(healed).toContain('"actor"->>"API": Approve');
    }
  });

  it("leaves a non-sequenceDiagram source preceded by a comment line byte-for-byte unchanged", () => {
    const src = ["%% a comment", "flowchart TD", "  actor-->API"].join("\n");
    expect(quoteSequenceParticipantAliases(src)).toBe(src);
  });
});
