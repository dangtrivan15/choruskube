import { describe, it, expect } from "vitest";
import { cn, formatSize } from "@/lib/utils";

describe("cn", () => {
  it("merges multiple class names", () => {
    expect(cn("foo", "bar")).toBe("foo bar");
  });

  it("handles conditional classes via clsx", () => {
    const showExcluded: boolean = false;
    expect(cn("base", showExcluded && "excluded", "included")).toBe("base included");
  });

  it("handles undefined and null inputs", () => {
    expect(cn("base", undefined, null, "end")).toBe("base end");
  });

  it("resolves tailwind conflicts with last-wins", () => {
    // tailwind-merge should keep only the last conflicting utility
    expect(cn("px-2", "px-4")).toBe("px-4");
  });

  it("merges non-conflicting tailwind classes", () => {
    expect(cn("px-2", "py-4")).toBe("px-2 py-4");
  });

  it("handles empty input", () => {
    expect(cn()).toBe("");
  });

  it("handles array input", () => {
    expect(cn(["foo", "bar"])).toBe("foo bar");
  });
});

describe("formatSize", () => {
  it("formats bytes below 1024 as B", () => {
    expect(formatSize(0)).toBe("0 B");
    expect(formatSize(512)).toBe("512 B");
    expect(formatSize(1023)).toBe("1023 B");
  });

  it("formats 1024 bytes as 1.0 KB", () => {
    expect(formatSize(1024)).toBe("1.0 KB");
  });

  it("formats larger values in KB with one decimal place", () => {
    expect(formatSize(2048)).toBe("2.0 KB");
    expect(formatSize(1536)).toBe("1.5 KB");
  });
});
