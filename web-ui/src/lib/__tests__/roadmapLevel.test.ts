import { describe, it, expect } from "vitest";
import { roadmapLevelMeta, type RoadmapLevel } from "@/lib/roadmapLevel";

describe("roadmapLevelMeta", () => {
  const levels: { level: RoadmapLevel; label: string }[] = [
    { level: "epic", label: "Epic" },
    { level: "story", label: "Story" },
    { level: "task", label: "Task" },
  ];

  it.each(levels)("returns label, icon, and accent classes for $level", ({ level, label }) => {
    const meta = roadmapLevelMeta(level);
    expect(meta.label).toBe(label);
    expect(meta.Icon).toBeDefined();
    expect(meta.textClass).toMatch(/^text-/);
    expect(meta.bgClass).toMatch(/^bg-/);
    expect(meta.borderClass).toMatch(/^border-/);
  });

  it("gives each level a mutually distinct accent", () => {
    const accents = levels.map(({ level }) => roadmapLevelMeta(level).textClass);
    expect(new Set(accents).size).toBe(accents.length);
  });

  it("gives each level a distinct icon", () => {
    const icons = levels.map(({ level }) => roadmapLevelMeta(level).Icon);
    expect(new Set(icons).size).toBe(icons.length);
  });
});
