import { describe, it, expect } from "vitest";
import fs from "fs";
import os from "os";
import path from "path";
import { ACCEPTED_COLOR_EXCEPTIONS, findOffBrandColors, scanSource } from "./palette-hygiene";

describe("findOffBrandColors over the whole src/ tree", () => {
  it("is clean and scans more than 50 files", () => {
    const { findings, filesScanned } = findOffBrandColors([path.resolve(__dirname, "..")]);
    if (findings.length > 0) {
      const report = findings.map((f) => `${f.file}:${f.line} ${f.match}`).join("\n");
      throw new Error(`Off-brand colors found:\n${report}`);
    }
    expect(findings).toHaveLength(0);
    expect(filesScanned).toBeGreaterThan(50);
  });
});

describe("scanSource self-tests", () => {
  it("flags one positive per category", () => {
    expect(scanSource("a.tsx", '<div className="dark:bg-amber-900/60" />')).toEqual([
      expect.objectContaining({ category: "palette", match: "bg-amber-900" }),
    ]);
    expect(scanSource("a.tsx", '<div className="text-white" />')).toEqual([
      expect.objectContaining({ category: "white-black", match: "text-white" }),
    ]);
    expect(scanSource("a.tsx", 'const c = "#ff00aa";')).toEqual([
      expect.objectContaining({ category: "hex", match: "#ff00aa" }),
    ]);
    expect(scanSource("a.tsx", '<div className="bg-[#f0a]" />')).toEqual([
      expect.objectContaining({ category: "hex", match: "[#f0a]" }),
    ]);
    expect(scanSource("a.tsx", 'style.color = "rgb(255, 0, 0)";')).toEqual([
      expect.objectContaining({ category: "color-function", match: "rgb(" }),
    ]);
  });

  it("allows the documented negatives", () => {
    expect(scanSource("a.tsx", '<div className="bg-black/40" />')).toEqual([]);
    expect(scanSource("a.tsx", 'color: var(--status-info);')).toEqual([]);
    expect(
      scanSource("a.tsx", "color-mix(in oklab, var(--x) 10%, transparent)")
    ).toEqual([]);
    expect(scanSource("a.tsx", '<div className="bg-status-success/15" />')).toEqual([]);
  });

  it("skips *.test.tsx via the file walker", () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "palette-hygiene-"));
    try {
      fs.writeFileSync(path.join(dir, "Foo.test.tsx"), '<div className="bg-red-500" />');
      const { findings, filesScanned } = findOffBrandColors([dir]);
      expect(findings).toEqual([]);
      expect(filesScanned).toBe(0);
    } finally {
      fs.rmSync(dir, { recursive: true, force: true });
    }
  });

  it("throws on a target that does not exist rather than scanning nothing", () => {
    const missing = path.join(os.tmpdir(), "palette-hygiene-missing", "Gone.tsx");
    expect(() => findOffBrandColors([missing])).toThrow(/does not exist/);
  });

  it("an empty directory gives filesScanned === 0", () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "palette-hygiene-empty-"));
    try {
      const { findings, filesScanned } = findOffBrandColors([dir]);
      expect(findings).toEqual([]);
      expect(filesScanned).toBe(0);
    } finally {
      fs.rmSync(dir, { recursive: true, force: true });
    }
  });
});

describe("ACCEPTED_COLOR_EXCEPTIONS", () => {
  it("does not flag an exception's own file for its listed pattern", () => {
    expect(scanSource("src/components/Logo.tsx", 'stopColor="#907aa9"')).toEqual([]);
    expect(
      scanSource("src/components/layout/ActivityFeedButton.tsx", 'className="text-white"')
    ).toEqual([]);
  });

  it("still flags the same pattern in any other file", () => {
    expect(scanSource("src/components/Other.tsx", 'stopColor="#907aa9"')).toHaveLength(1);
  });

  it("keys match the paths the file walker reports for each exception's real file", () => {
    const webUiRoot = path.resolve(__dirname, "../..");
    const { findings, filesScanned } = findOffBrandColors(
      ACCEPTED_COLOR_EXCEPTIONS.map((exception) => path.join(webUiRoot, exception.file))
    );
    expect(findings).toEqual([]);
    expect(filesScanned).toBe(ACCEPTED_COLOR_EXCEPTIONS.length);
  });
});
