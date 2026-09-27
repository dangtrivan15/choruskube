import { describe, it, expect } from "vitest";
import path from "path";
import { ACCEPTED_INK_EXCEPTIONS, findTranslucentInks, scanInkSource } from "./ink-hygiene";
import { buttonVariants } from "../components/ui/button";

describe("findTranslucentInks over the whole src/ tree", () => {
  it("is clean and scans more than 100 files", () => {
    const { findings, filesScanned } = findTranslucentInks([path.resolve(__dirname, "..")]);
    if (findings.length > 0) {
      const report = findings.map((f) => `${f.file}:${f.line} ${f.match}`).join("\n");
      throw new Error(`Translucent inks / faded state indicators found:\n${report}`);
    }
    expect(findings).toHaveLength(0);
    expect(filesScanned).toBeGreaterThan(100);
  });
});

describe("scanInkSource self-tests", () => {
  it("flags translucent inks behind any variant chain", () => {
    // The lookbehind only verifies a legitimate token boundary before `text-`;
    // it does not capture the variant chain into the match, so both of these
    // report the same bare token — the point is that the prefix cannot hide it.
    expect(scanInkSource("a.tsx", '<a className="hover:text-primary/80" />')).toEqual([
      expect.objectContaining({ category: "ink", match: "text-primary/80" }),
    ]);
    expect(scanInkSource("a.tsx", '<a className="[a]:hover:text-primary/80" />')).toEqual([
      expect.objectContaining({ category: "ink", match: "text-primary/80" }),
    ]);
    expect(scanInkSource("a.tsx", '<p className="text-muted-foreground/70" />')).toEqual([
      expect.objectContaining({ category: "ink", match: "text-muted-foreground/70" }),
    ]);
    expect(scanInkSource("a.tsx", '<p className="text-muted-foreground/[.7]" />')).toEqual([
      expect.objectContaining({ category: "ink", match: "text-muted-foreground/[.7]" }),
    ]);
    expect(scanInkSource("a.tsx", '<p className="dark:text-foreground/60" />')).toEqual([
      expect.objectContaining({ category: "ink", match: "text-foreground/60" }),
    ]);
    expect(scanInkSource("a.tsx", '<span className="text-background/70" />')).toEqual([
      expect.objectContaining({ category: "ink", match: "text-background/70" }),
    ]);
  });

  it("flags faded focus and invalid-state indicators, with no double-count on an overlapping match", () => {
    expect(scanInkSource("a.tsx", '<button className="focus-visible:ring-ring/50" />')).toHaveLength(1);
    expect(scanInkSource("a.tsx", '<button className="focus-visible:ring-ring/50" />')).toEqual([
      expect.objectContaining({ category: "focus", match: "focus-visible:ring-ring/50" }),
    ]);
    expect(scanInkSource("a.tsx", '<button className="focus-visible:ring-destructive/20" />')).toEqual([
      expect.objectContaining({ category: "focus", match: "focus-visible:ring-destructive/20" }),
    ]);
    expect(scanInkSource("a.tsx", '  @apply outline-ring/50;')).toEqual([
      expect.objectContaining({ category: "focus", match: "outline-ring/50" }),
    ]);
    expect(scanInkSource("a.tsx", '<input className="dark:aria-invalid:border-destructive/50" />')).toEqual([
      expect.objectContaining({ category: "invalid-border", match: "aria-invalid:border-destructive/50" }),
    ]);
  });

  it("does not flag full-opacity inks, the supplementary invalid halo, or decorative borders", () => {
    expect(scanInkSource("a.tsx", '<p className="text-muted-foreground" />')).toEqual([]);
    expect(scanInkSource("a.tsx", '<p className="text-status-warning/80" />')).toEqual([]);
    expect(scanInkSource("a.tsx", '<input className="aria-invalid:ring-destructive/20" />')).toEqual([]);
    expect(scanInkSource("a.tsx", '<input className="dark:aria-invalid:ring-destructive/40" />')).toEqual([]);
    expect(scanInkSource("a.tsx", '<div className="bg-primary/10" />')).toEqual([]);
    expect(scanInkSource("a.tsx", '<div className="bg-background/80" />')).toEqual([]);
    expect(scanInkSource("a.tsx", '<kbd className="border-sidebar-foreground/20" />')).toEqual([]);
    expect(scanInkSource("a.tsx", '<div className="border-destructive/30" />')).toEqual([]);
  });

  it("the EmptyState allowlist entry is not flagged in its own file but is flagged elsewhere", () => {
    expect(
      scanInkSource("src/components/ui/EmptyState.tsx", '<div className="text-muted-foreground/40" />'),
    ).toEqual([]);
    expect(scanInkSource("src/components/Other.tsx", '<div className="text-muted-foreground/40" />')).toHaveLength(
      1,
    );
  });

  it("every allowlist key matches the path the walker reports for its real file", () => {
    const webUiRoot = path.resolve(__dirname, "../..");
    const { findings, filesScanned } = findTranslucentInks(
      ACCEPTED_INK_EXCEPTIONS.map((exception) => path.join(webUiRoot, exception.file)),
    );
    expect(findings).toEqual([]);
    expect(filesScanned).toBe(ACCEPTED_INK_EXCEPTIONS.length);
  });
});

describe("button.tsx filled variants carry no alpha-lowering hover", () => {
  it("default and destructive variants have no hover:bg-primary/ or hover:bg-destructive/", () => {
    const defaultClasses = buttonVariants({ variant: "default" });
    const destructiveClasses = buttonVariants({ variant: "destructive" });
    expect(defaultClasses).not.toMatch(/hover:bg-primary\//);
    expect(defaultClasses).not.toMatch(/hover:bg-destructive\//);
    expect(destructiveClasses).not.toMatch(/hover:bg-primary\//);
    expect(destructiveClasses).not.toMatch(/hover:bg-destructive\//);
  });

  it("the destructive variant carries text-destructive-foreground", () => {
    expect(buttonVariants({ variant: "destructive" })).toContain("text-destructive-foreground");
  });
});
