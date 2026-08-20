import { describe, it, expect } from "vitest";
import {
  ROADMAP_VIEW_ORDER,
  carryViewToLevel,
  roadmapDestination,
  roadmapViewMeta,
  roadmapViewsForLevel,
  type RoadmapViewType,
} from "@/lib/roadmapNavigation";
import { ROADMAP_LEVEL_ORDER, type RoadmapLevel } from "@/lib/roadmapLevel";
import type { RoadmapView } from "@/lib/roadmapFocus";

const EPIC_ONLY = { epicId: "epic-1" };
const EPIC_AND_STORY = { epicId: "epic-1", storyId: "story-1" };

describe("roadmapDestination", () => {
  // The full (view x level) availability table, written out rather than derived, so a change
  // to the implementation's own table can never quietly change what this asserts. `null` means
  // "this combination has no page" — the header renders no button for it at all.
  const cases: { view: RoadmapView; level: RoadmapLevel; url: string | null }[] = [
    { view: "list", level: "epic", url: "/roadmap" },
    { view: "board", level: "epic", url: "/roadmap/board" },
    { view: "timeline", level: "epic", url: "/roadmap/timeline" },
    { view: "graph", level: "epic", url: null },

    { view: "list", level: "story", url: "/roadmap/stories" },
    { view: "board", level: "story", url: "/roadmap/board/stories" },
    { view: "timeline", level: "story", url: null },
    { view: "graph", level: "story", url: null },

    { view: "list", level: "task", url: "/roadmap/tasks" },
    { view: "board", level: "task", url: "/roadmap/board/tasks" },
    { view: "timeline", level: "task", url: null },
    { view: "graph", level: "task", url: null },
  ];

  it.each(cases)("$view x $level with nothing focused -> $url", ({ view, level, url }) => {
    expect(roadmapDestination(view, level, {})).toBe(url);
  });

  // --- Epic-level targets carry the focus ---

  it("carries a focused Epic into every Epic-level view", () => {
    expect(roadmapDestination("list", "epic", EPIC_ONLY)).toBe("/roadmap?epic=epic-1");
    expect(roadmapDestination("board", "epic", EPIC_ONLY)).toBe("/roadmap/board?epic=epic-1");
    expect(roadmapDestination("timeline", "epic", EPIC_ONLY)).toBe("/roadmap/timeline?epic=epic-1");
  });

  it("carries a focused Epic+Story into every Epic-level view, epic first", () => {
    expect(roadmapDestination("list", "epic", EPIC_AND_STORY)).toBe("/roadmap?epic=epic-1&story=story-1");
    expect(roadmapDestination("board", "epic", EPIC_AND_STORY)).toBe(
      "/roadmap/board?epic=epic-1&story=story-1",
    );
    expect(roadmapDestination("timeline", "epic", EPIC_AND_STORY)).toBe(
      "/roadmap/timeline?epic=epic-1&story=story-1",
    );
  });

  // --- Story/Task-level targets drop the focus ---

  it.each([
    { view: "list" as const, level: "story" as const, url: "/roadmap/stories" },
    { view: "board" as const, level: "story" as const, url: "/roadmap/board/stories" },
    { view: "list" as const, level: "task" as const, url: "/roadmap/tasks" },
    { view: "board" as const, level: "task" as const, url: "/roadmap/board/tasks" },
  ])("drops the focus crossing to $view x $level — the target page never reads it", ({ view, level, url }) => {
    expect(roadmapDestination(view, level, EPIC_AND_STORY)).toBe(url);
  });

  // --- Graph is focus-driven, not level-driven ---

  it("resolves Graph from the focused Epic alone, whatever the current ticket type", () => {
    for (const level of ROADMAP_LEVEL_ORDER) {
      expect(roadmapDestination("graph", level, EPIC_ONLY)).toBe("/roadmap/epics/epic-1/graph");
    }
  });

  it("ignores a focused Story for Graph — the graph route is Epic-scoped", () => {
    expect(roadmapDestination("graph", "epic", EPIC_AND_STORY)).toBe("/roadmap/epics/epic-1/graph");
  });

  it("returns null for Graph with a Story focused but no Epic — there is no Story graph route", () => {
    expect(roadmapDestination("graph", "epic", { storyId: "story-1" })).toBeNull();
  });

  it("defaults `focus` to empty so callers with nothing focused can omit it", () => {
    expect(roadmapDestination("board", "epic")).toBe("/roadmap/board");
    expect(roadmapDestination("graph", "epic")).toBeNull();
  });

  it("keeps every pinned Epic-level URL byte-for-byte", () => {
    expect(roadmapDestination("list", "epic", {})).toBe("/roadmap");
    expect(roadmapDestination("board", "epic", EPIC_AND_STORY)).toBe(
      "/roadmap/board?epic=epic-1&story=story-1",
    );
    expect(roadmapDestination("timeline", "epic", EPIC_AND_STORY)).toBe(
      "/roadmap/timeline?epic=epic-1&story=story-1",
    );
    expect(roadmapDestination("graph", "epic", EPIC_ONLY)).toBe("/roadmap/epics/epic-1/graph");
  });
});

describe("roadmapViewsForLevel", () => {
  it("offers all three view types for Epics", () => {
    expect(roadmapViewsForLevel("epic")).toEqual(["list", "board", "timeline"]);
  });

  it("omits Timeline for Stories and Tasks rather than offering a dead view", () => {
    expect(roadmapViewsForLevel("story")).toEqual(["list", "board"]);
    expect(roadmapViewsForLevel("task")).toEqual(["list", "board"]);
  });

  it("returns views in the canonical order, and never Graph", () => {
    for (const level of ROADMAP_LEVEL_ORDER) {
      const views = roadmapViewsForLevel(level);
      expect(views).toEqual(ROADMAP_VIEW_ORDER.filter((v) => views.includes(v)));
      expect(views).not.toContain("graph");
    }
  });

  it("returns only views that actually resolve to a URL", () => {
    for (const level of ROADMAP_LEVEL_ORDER) {
      for (const view of roadmapViewsForLevel(level)) {
        expect(roadmapDestination(view, level, {})).not.toBeNull();
      }
    }
  });
});

describe("carryViewToLevel", () => {
  it("keeps the current view when the new ticket type has it", () => {
    expect(carryViewToLevel("list", "story")).toBe("list");
    expect(carryViewToLevel("board", "task")).toBe("board");
    expect(carryViewToLevel("timeline", "epic")).toBe("timeline");
  });

  it("falls back to Board when the current view has no page for the new ticket type", () => {
    expect(carryViewToLevel("timeline", "story")).toBe("board");
    expect(carryViewToLevel("timeline", "task")).toBe("board");
  });

  it("falls back to Board from Graph — Graph is not a view type", () => {
    expect(carryViewToLevel("graph", "epic")).toBe("board");
    expect(carryViewToLevel("graph", "story")).toBe("board");
  });

  it("always names a view that exists for the target level", () => {
    const views: RoadmapView[] = ["list", "board", "timeline", "graph"];
    for (const level of ROADMAP_LEVEL_ORDER) {
      for (const view of views) {
        expect(roadmapViewsForLevel(level)).toContain(carryViewToLevel(view, level));
      }
    }
  });
});

describe("roadmapViewMeta", () => {
  const expected: { view: RoadmapView; label: string }[] = [
    { view: "list", label: "List" },
    { view: "board", label: "Board" },
    { view: "timeline", label: "Timeline" },
    { view: "graph", label: "Open graph" },
  ];

  it.each(expected)("returns a label and icon for $view", ({ view, label }) => {
    const meta = roadmapViewMeta(view);
    expect(meta.label).toBe(label);
    expect(meta.Icon).toBeDefined();
  });

  it("gives each view a distinct icon", () => {
    const icons = expected.map(({ view }) => roadmapViewMeta(view).Icon);
    expect(new Set(icons).size).toBe(icons.length);
  });
});

describe("ROADMAP_VIEW_ORDER", () => {
  it("lists the three view types least-to-most spatial, and excludes Graph", () => {
    const order: readonly RoadmapViewType[] = ROADMAP_VIEW_ORDER;
    expect(order).toEqual(["list", "board", "timeline"]);
  });
});
