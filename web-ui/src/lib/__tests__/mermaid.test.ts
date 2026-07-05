import { describe, it, expect } from "vitest";
import { healMermaidArrows, escapeMermaidSemicolons } from "../mermaid";

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
