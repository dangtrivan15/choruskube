import { describe, it, expect } from "vitest";
import {
  computeRoadmapTimelineLayout,
  TIMELINE_LANE_HEIGHT,
  type TimelineEpicLaneNodeType,
  type TimelineStoryNodeType,
} from "../timelineLayout";
import type { RoadmapTimelineResponse, TimelineEpicSummary, TimelineStorySummary } from "../types";

function makeStory(overrides: Partial<TimelineStorySummary> = {}): TimelineStorySummary {
  return {
    id: "00000000-0000-0000-0000-000000000001",
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
    ...overrides,
  };
}

function isStoryNode(node: { type?: string }): node is TimelineStoryNodeType {
  return node.type === "timeline-story";
}

function isEpicLaneNode(node: { type?: string }): node is TimelineEpicLaneNodeType {
  return node.type === "timeline-epic-lane";
}

describe("computeRoadmapTimelineLayout", () => {
  it("single Epic with a single Story produces one lane node and one positioned Story node", () => {
    const data: RoadmapTimelineResponse = {
      epics: [makeEpic({ id: "epic-1", stories: [makeStory({ id: "story-1", epicId: "epic-1" })] })],
    };

    const { nodes, edges } = computeRoadmapTimelineLayout(data);

    const laneNodes = nodes.filter(isEpicLaneNode);
    const storyNodes = nodes.filter(isStoryNode);
    expect(laneNodes).toHaveLength(1);
    expect(laneNodes[0].id).toBe("epic-1");
    expect(storyNodes).toHaveLength(1);
    expect(storyNodes[0].id).toBe("story-1");
    expect(storyNodes[0].position).toEqual(expect.objectContaining({ x: expect.any(Number), y: expect.any(Number) }));
    expect(edges).toEqual([]);
  });

  it("an Epic with zero Stories produces a lane with no Story nodes", () => {
    const data: RoadmapTimelineResponse = { epics: [makeEpic({ id: "epic-1", stories: [] })] };

    const { nodes } = computeRoadmapTimelineLayout(data);

    expect(nodes.filter(isEpicLaneNode)).toHaveLength(1);
    expect(nodes.filter(isStoryNode)).toHaveLength(0);
  });

  it("two Stories sharing an identical createdAt in the same lane get distinct, non-overlapping X positions", () => {
    const sameTimestamp = "2026-04-01T00:00:00Z";
    const data: RoadmapTimelineResponse = {
      epics: [
        makeEpic({
          id: "epic-1",
          stories: [
            makeStory({ id: "aaaaaaaa-0000-0000-0000-000000000001", createdAt: sameTimestamp }),
            makeStory({ id: "bbbbbbbb-0000-0000-0000-000000000002", createdAt: sameTimestamp }),
          ],
        }),
      ],
    };

    const { nodes } = computeRoadmapTimelineLayout(data);
    const storyNodes = nodes.filter(isStoryNode);

    expect(storyNodes).toHaveLength(2);
    const xs = storyNodes.map((n) => n.position.x);
    expect(new Set(xs).size).toBe(2);
    // Ascending-id tie-break: the lexicographically smaller UUID resolves first (leftmost).
    const sortedById = [...storyNodes].sort((a, b) => (a.id < b.id ? -1 : 1));
    expect(sortedById[0].position.x).toBeLessThan(sortedById[1].position.x);
  });

  it("Stories within a lane are ordered left-to-right by ascending createdAt", () => {
    const data: RoadmapTimelineResponse = {
      epics: [
        makeEpic({
          id: "epic-1",
          stories: [
            makeStory({ id: "story-newest", title: "Newest", createdAt: "2026-04-03T00:00:00Z" }),
            makeStory({ id: "story-oldest", title: "Oldest", createdAt: "2026-04-01T00:00:00Z" }),
            makeStory({ id: "story-middle", title: "Middle", createdAt: "2026-04-02T00:00:00Z" }),
          ],
        }),
      ],
    };

    const { nodes } = computeRoadmapTimelineLayout(data);
    const storyNodes = nodes.filter(isStoryNode);

    const byId = new Map(storyNodes.map((n) => [n.id, n.position.x]));
    expect(byId.get("story-oldest")!).toBeLessThan(byId.get("story-middle")!);
    expect(byId.get("story-middle")!).toBeLessThan(byId.get("story-newest")!);
  });

  it("places each Epic's lane on its own row, TIMELINE_LANE_HEIGHT apart, in response order", () => {
    const data: RoadmapTimelineResponse = {
      epics: [makeEpic({ id: "epic-1", stories: [] }), makeEpic({ id: "epic-2", stories: [] })],
    };

    const { nodes } = computeRoadmapTimelineLayout(data);
    const laneNodes = nodes.filter(isEpicLaneNode);

    const epic1 = laneNodes.find((n) => n.id === "epic-1")!;
    const epic2 = laneNodes.find((n) => n.id === "epic-2")!;
    expect(epic2.position.y - epic1.position.y).toBe(TIMELINE_LANE_HEIGHT);
  });

  it("an empty roadmap produces no nodes and no edges", () => {
    const { nodes, edges } = computeRoadmapTimelineLayout({ epics: [] });

    expect(nodes).toEqual([]);
    expect(edges).toEqual([]);
  });

  describe("blocked/stalled risk data", () => {
    it("carries a Story's own readiness/stalled straight through to its node data", () => {
      const data: RoadmapTimelineResponse = {
        epics: [
          makeEpic({
            id: "epic-1",
            stories: [
              makeStory({ id: "story-blocked", readiness: "BLOCKED", stalled: false }),
              makeStory({ id: "story-stalled", readiness: "READY", stalled: true }),
              makeStory({ id: "story-fine", readiness: "READY", stalled: false }),
            ],
          }),
        ],
      };

      const { nodes } = computeRoadmapTimelineLayout(data);
      const storyNodes = nodes.filter(isStoryNode);
      const byId = new Map(storyNodes.map((n) => [n.id, n.data]));

      expect(byId.get("story-blocked")).toEqual(expect.objectContaining({ blocked: true, stalled: false }));
      expect(byId.get("story-stalled")).toEqual(expect.objectContaining({ blocked: false, stalled: true }));
      expect(byId.get("story-fine")).toEqual(expect.objectContaining({ blocked: false, stalled: false }));
    });

    it("aggregates an Epic lane's blocked/stalled via OR across its Stories, plus its own stalled", () => {
      const data: RoadmapTimelineResponse = {
        epics: [
          makeEpic({
            id: "epic-with-blocked-story",
            stalled: false,
            stories: [makeStory({ id: "s1", readiness: "BLOCKED", stalled: false })],
          }),
          makeEpic({
            id: "epic-with-stalled-story",
            stalled: false,
            stories: [makeStory({ id: "s2", readiness: "READY", stalled: true })],
          }),
          makeEpic({
            id: "epic-itself-stalled",
            stalled: true,
            stories: [makeStory({ id: "s3", readiness: "READY", stalled: false })],
          }),
          makeEpic({ id: "epic-clean", stalled: false, stories: [makeStory({ id: "s4" })] }),
        ],
      };

      const { nodes } = computeRoadmapTimelineLayout(data);
      const laneNodes = nodes.filter(isEpicLaneNode);
      const byId = new Map(laneNodes.map((n) => [n.id, n.data]));

      expect(byId.get("epic-with-blocked-story")).toEqual(expect.objectContaining({ blocked: true, stalled: false }));
      expect(byId.get("epic-with-stalled-story")).toEqual(expect.objectContaining({ blocked: false, stalled: true }));
      expect(byId.get("epic-itself-stalled")).toEqual(expect.objectContaining({ blocked: false, stalled: true }));
      expect(byId.get("epic-clean")).toEqual(expect.objectContaining({ blocked: false, stalled: false }));
    });
  });

  describe("epicTitle", () => {
    it("populates each Story node's data.epicTitle with its parent Epic's title", () => {
      const data: RoadmapTimelineResponse = {
        epics: [
          makeEpic({
            id: "epic-1",
            title: "Payments Overhaul",
            stories: [makeStory({ id: "story-1", epicId: "epic-1" })],
          }),
          makeEpic({
            id: "epic-2",
            title: "Search Revamp",
            stories: [makeStory({ id: "story-2", epicId: "epic-2" })],
          }),
        ],
      };

      const { nodes } = computeRoadmapTimelineLayout(data);
      const storyNodes = nodes.filter(isStoryNode);
      const byId = new Map(storyNodes.map((n) => [n.id, n.data.epicTitle]));

      expect(byId.get("story-1")).toBe("Payments Overhaul");
      expect(byId.get("story-2")).toBe("Search Revamp");
    });

    it("an empty Epic (no Stories) still lays out with just its lane node", () => {
      const data: RoadmapTimelineResponse = { epics: [makeEpic({ id: "epic-1", title: "Empty Epic", stories: [] })] };

      const { nodes } = computeRoadmapTimelineLayout(data);

      expect(nodes.filter(isEpicLaneNode)).toHaveLength(1);
      expect(nodes.filter(isStoryNode)).toHaveLength(0);
    });
  });

  describe("focus", () => {
    function twoEpicData(): RoadmapTimelineResponse {
      return {
        epics: [
          makeEpic({ id: "epic-1", stories: [makeStory({ id: "story-1", epicId: "epic-1" })] }),
          makeEpic({ id: "epic-2", stories: [makeStory({ id: "story-2", epicId: "epic-2" })] }),
        ],
      };
    }

    it("sets isFocused: true on exactly the matching lane node and false on all others", () => {
      const { nodes } = computeRoadmapTimelineLayout(twoEpicData(), { epicId: "epic-1" });
      const laneNodes = nodes.filter(isEpicLaneNode);

      expect(laneNodes.find((n) => n.id === "epic-1")!.data.isFocused).toBe(true);
      expect(laneNodes.find((n) => n.id === "epic-2")!.data.isFocused).toBe(false);
    });

    it("sets isFocused: true on exactly the matching story node", () => {
      const { nodes } = computeRoadmapTimelineLayout(twoEpicData(), {
        epicId: "epic-1",
        storyId: "story-1",
      });
      const storyNodes = nodes.filter(isStoryNode);

      expect(storyNodes.find((n) => n.id === "story-1")!.data.isFocused).toBe(true);
      expect(storyNodes.find((n) => n.id === "story-2")!.data.isFocused).toBe(false);
    });

    it("leaves every node isFocused: false when focus is non-matching or absent", () => {
      const noFocus = computeRoadmapTimelineLayout(twoEpicData());
      expect(noFocus.nodes.every((n) => n.data.isFocused === false)).toBe(true);

      const nonMatching = computeRoadmapTimelineLayout(twoEpicData(), {
        epicId: "epic-does-not-exist",
        storyId: "story-does-not-exist",
      });
      expect(nonMatching.nodes.every((n) => n.data.isFocused === false)).toBe(true);
    });
  });
});
