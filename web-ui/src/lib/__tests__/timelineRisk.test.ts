import { describe, it, expect } from "vitest";
import { deriveStoryRisk, deriveEpicRisk, riskDisplayOrder } from "../timelineRisk";
import type { TimelineEpicSummary, TimelineStorySummary } from "../types";

function makeStory(overrides: Partial<TimelineStorySummary> = {}): TimelineStorySummary {
  return {
    id: "story-1",
    epicId: "epic-1",
    title: "Story",
    stage: "backlog",
    priority: "medium",
    createdAt: "2026-04-01T00:00:00Z",
    updatedAt: "2026-04-01T00:00:00Z",
    readiness: "READY",
    stalled: false,
    ...overrides,
  };
}

function makeEpic(overrides: Partial<TimelineEpicSummary> = {}): TimelineEpicSummary {
  return {
    id: "epic-1",
    title: "Epic",
    stage: "backlog",
    priority: "medium",
    createdAt: "2026-04-01T00:00:00Z",
    updatedAt: "2026-04-01T00:00:00Z",
    stories: [],
    stalled: false,
    milestone: null,
    ...overrides,
  };
}

describe("deriveStoryRisk", () => {
  it("neither blocked nor stalled", () => {
    expect(deriveStoryRisk(makeStory({ readiness: "READY", stalled: false }))).toEqual({
      blocked: false,
      stalled: false,
    });
  });

  it("blocked only", () => {
    expect(deriveStoryRisk(makeStory({ readiness: "BLOCKED", stalled: false }))).toEqual({
      blocked: true,
      stalled: false,
    });
  });

  it("stalled only", () => {
    expect(deriveStoryRisk(makeStory({ readiness: "READY", stalled: true }))).toEqual({
      blocked: false,
      stalled: true,
    });
  });

  it("both blocked and stalled", () => {
    expect(deriveStoryRisk(makeStory({ readiness: "BLOCKED", stalled: true }))).toEqual({
      blocked: true,
      stalled: true,
    });
  });
});

describe("deriveEpicRisk", () => {
  it("neither blocked nor stalled when the Epic and all its Stories are clean", () => {
    const epic = makeEpic({ stalled: false, stories: [makeStory({ readiness: "READY", stalled: false })] });
    expect(deriveEpicRisk(epic)).toEqual({ blocked: false, stalled: false });
  });

  it("blocked when any Story is BLOCKED, even if the Epic itself is not stalled", () => {
    const epic = makeEpic({
      stalled: false,
      stories: [makeStory({ id: "s1", readiness: "READY" }), makeStory({ id: "s2", readiness: "BLOCKED" })],
    });
    expect(deriveEpicRisk(epic)).toEqual({ blocked: true, stalled: false });
  });

  it("stalled when a Story is stalled, even if the Epic's own stalled flag is false", () => {
    const epic = makeEpic({ stalled: false, stories: [makeStory({ stalled: true })] });
    expect(deriveEpicRisk(epic)).toEqual({ blocked: false, stalled: true });
  });

  it("stalled when the Epic's own stalled flag is true, even with no Stories", () => {
    const epic = makeEpic({ stalled: true, stories: [] });
    expect(deriveEpicRisk(epic)).toEqual({ blocked: false, stalled: true });
  });

  it("both blocked and stalled when aggregated from a mix of Stories and the Epic's own flag", () => {
    const epic = makeEpic({
      stalled: true,
      stories: [makeStory({ id: "s1", readiness: "BLOCKED", stalled: false })],
    });
    expect(deriveEpicRisk(epic)).toEqual({ blocked: true, stalled: true });
  });
});

describe("riskDisplayOrder", () => {
  it("none when neither blocked nor stalled", () => {
    expect(riskDisplayOrder({ blocked: false, stalled: false })).toBe("none");
  });

  it("blocked when only blocked", () => {
    expect(riskDisplayOrder({ blocked: true, stalled: false })).toBe("blocked");
  });

  it("stalled when only stalled", () => {
    expect(riskDisplayOrder({ blocked: false, stalled: true })).toBe("stalled");
  });

  it("blocked-stalled when both", () => {
    expect(riskDisplayOrder({ blocked: true, stalled: true })).toBe("blocked-stalled");
  });
});
