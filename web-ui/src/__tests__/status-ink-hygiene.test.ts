import { describe, it, expect } from "vitest";
import path from "path";
import { listScannableFiles } from "./palette-hygiene";
import {
  ACCEPTED_EXCEPTIONS,
  findToneColoredTextAndTranslucentTints,
  scanStatusInkSource,
} from "./status-ink-hygiene";

describe("findToneColoredTextAndTranslucentTints over the whole src/ tree", () => {
  it("is clean and scans more than 100 files", () => {
    const root = path.resolve(__dirname, "..");
    const findings = findToneColoredTextAndTranslucentTints([root]);
    if (findings.length > 0) {
      const report = findings.map((f) => `${f.file}:${f.line} ${f.match} (${f.category})`).join("\n");
      throw new Error(`Tone-colored text or translucent status/chart tints found:\n${report}`);
    }
    expect(findings).toHaveLength(0);
    expect(listScannableFiles([root]).length).toBeGreaterThan(100);
  });
});

describe("scanStatusInkSource self-tests", () => {
  it("flags a tone-colored text word in a .tsx file", () => {
    expect(scanStatusInkSource("a.tsx", '<span className="text-status-error">Failed</span>')).toEqual([
      expect.objectContaining({ category: "tone-text", match: "text-status-error" }),
    ]);
    expect(scanStatusInkSource("a.tsx", '<span className="text-chart-2">Epic</span>')).toEqual([
      expect.objectContaining({ category: "tone-text", match: "text-chart-2" }),
    ]);
  });

  it("does not flag a tone-colored text match in a .ts (non-.tsx) file — recipe modules are the source of truth", () => {
    expect(scanStatusInkSource("a.ts", 'text: "text-status-error",')).toEqual([]);
  });

  it("flags a translucent status/chart tint behind any variant chain", () => {
    expect(scanStatusInkSource("a.tsx", '<div className="bg-status-warning/15" />')).toEqual([
      expect.objectContaining({ category: "translucent-tint", match: "bg-status-warning/15" }),
    ]);
    expect(scanStatusInkSource("a.tsx", '<div className="hover:bg-chart-1/10" />')).toEqual([
      expect.objectContaining({ category: "translucent-tint", match: "hover:bg-chart-1/10" }),
    ]);
    expect(scanStatusInkSource("a.ts", 'bgClass: "bg-status-error/15",')).toEqual([
      expect.objectContaining({ category: "translucent-tint", match: "bg-status-error/15" }),
    ]);
    expect(scanStatusInkSource("a.tsx", '<div className="[a]:hover:bg-status-info/[.2]" />')).toEqual([
      expect.objectContaining({ category: "translucent-tint", match: "hover:bg-status-info/[.2]" }),
    ]);
  });

  it("does not flag translucent bg-primary/N or bg-destructive/N — those are control fills, out of scope", () => {
    expect(scanStatusInkSource("a.tsx", '<div className="hover:bg-primary/80" />')).toEqual([]);
    expect(scanStatusInkSource("a.tsx", '<div className="bg-destructive/20" />')).toEqual([]);
  });

  it("does not flag an opaque tint, a solid mark fill, or the badge recipe's ::before dot", () => {
    expect(
      scanStatusInkSource(
        "a.tsx",
        '<div className="bg-tint-status-success hover:bg-tint-strong-chart-1 bg-status-error before:bg-status-info" />',
      ),
    ).toEqual([]);
  });

  it("the DecisionButtons.tsx allowlist entry is not flagged in its own file but is flagged elsewhere", () => {
    expect(
      scanStatusInkSource("src/components/runs/DecisionButtons.tsx", '<button className="hover:bg-status-success/90" />'),
    ).toEqual([]);
    expect(
      scanStatusInkSource("src/components/Other.tsx", '<button className="hover:bg-status-success/90" />'),
    ).toHaveLength(1);
  });

  it("every allowlist key matches the path the walker reports for its real file", () => {
    const webUiRoot = path.resolve(__dirname, "../..");
    const findings = findToneColoredTextAndTranslucentTints(
      ACCEPTED_EXCEPTIONS.map((exception) => path.join(webUiRoot, exception.file)),
    );
    expect(findings).toEqual([]);
  });
});
