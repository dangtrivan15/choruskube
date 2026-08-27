import type { Node, Edge } from "@xyflow/react";
import type { RoadmapTimelineResponse, TimelineEpicSummary, TimelineStorySummary } from "@/lib/types";
import { deriveEpicRisk, deriveStoryRisk } from "@/lib/timelineRisk";

/** Vertical space (px) each Epic's lane occupies, including inter-lane gap. */
export const TIMELINE_LANE_HEIGHT = 96;

/** Horizontal space (px) reserved on the left for the Epic lane header node. */
export const TIMELINE_AXIS_ORIGIN_X = 220;

/** Total horizontal span (px) the time axis stretches across, from the earliest to the latest Story. */
export const TIMELINE_AXIS_WIDTH = 2400;

/**
 * Deterministic secondary X offset (px) applied per colliding Story sharing the exact same
 * `createdAt` timestamp within one Epic's lane — without this, two Stories
 * created in the same instant would render exactly on top of each other on the linear time scale.
 */
export const TIMELINE_COLLISION_OFFSET = 28;

export interface TimelineEpicLaneNodeData {
  epicId: string;
  title: string;
  stage: string;
  /** Prioritization level — display-only on the lane header (compact PriorityBadge). */
  priority: string;
  /** Whether this lane's Epic is the currently-focused item (/) — drives highlight styling. */
  isFocused: boolean;
  /** OR-aggregated across this Epic's Stories (plus its own `stalled`, for `stalled`) — see `deriveEpicRisk`. */
  blocked: boolean;
  stalled: boolean;
  [key: string]: unknown;
}

export type TimelineEpicLaneNodeType = Node<TimelineEpicLaneNodeData, "timeline-epic-lane">;

export interface TimelineStoryNodeData {
  storyId: string;
  epicId: string;
  /** The owning Epic's title (§ item-detail hover/click) — carried on the Story node so the hover
   * preview can name the parent Epic without a separate lookup back into the full timeline data. */
  epicTitle: string;
  title: string;
  stage: string;
  /** Prioritization level — display-only on the Story marker (compact PriorityBadge). */
  priority: string;
  createdAt: string;
  /** Whether this Story is the currently-focused item (/) — drives highlight styling. */
  isFocused: boolean;
  /** This Story's own risk signal (§ blocked/stalled work) — see `deriveStoryRisk`. */
  blocked: boolean;
  stalled: boolean;
  [key: string]: unknown;
}

export type TimelineStoryNodeType = Node<TimelineStoryNodeData, "timeline-story">;

export type TimelineFlowNode = TimelineEpicLaneNodeType | TimelineStoryNodeType;

export interface TimelineLayoutResult {
  nodes: TimelineFlowNode[];
  edges: Edge[];
}

/** Ascending-`createdAt` sort, tie-broken by ascending Story `id` (UUID string comparison). */
function compareStories(a: TimelineStorySummary, b: TimelineStorySummary): number {
  const ta = new Date(a.createdAt).getTime();
  const tb = new Date(b.createdAt).getTime();
  if (ta !== tb) return ta - tb;
  if (a.id < b.id) return -1;
  if (a.id > b.id) return 1;
  return 0;
}

/** Builds a linear `createdAt` (ms since epoch) -> X scale spanning every Story in the response. */
function buildTimeScale(epics: TimelineEpicSummary[]): (timeMs: number) => number {
  const allTimes = epics.flatMap((e) => e.stories.map((s) => new Date(s.createdAt).getTime()));
  if (allTimes.length === 0) {
    return () => TIMELINE_AXIS_ORIGIN_X;
  }
  const min = Math.min(...allTimes);
  const max = Math.max(...allTimes);
  if (min === max) {
    // A single distinct timestamp across the whole roadmap (one Story, or every Story created in
    // the same instant) — nothing to scale against, so anchor at the axis origin. The collision
    // tie-break below still separates same-timestamp Stories within a lane from there.
    return () => TIMELINE_AXIS_ORIGIN_X;
  }
  return (timeMs: number) => TIMELINE_AXIS_ORIGIN_X + ((timeMs - min) / (max - min)) * TIMELINE_AXIS_WIDTH;
}

/**
 * Computes the Roadmap Timeline View's layout: one horizontal lane per Epic (Y axis, in the
 * order the backend already returns — ascending `createdAt`), with each Epic's Stories plotted
 * along a shared linear time scale (X axis) built from every Story's `createdAt` across the
 * entire response, not just the Stories in that one lane — so two Epics' lanes stay visually
 * comparable against the same axis.
 *
 * `focus`  sets `isFocused: true` on the lane/Story node(s) matching `focus.epicId`/
 * `focus.storyId`, and `false` on every other node — defaults to `{}` (nothing focused) so
 * existing callers that don't care about focus don't need to pass anything.
 *
 * Returns ready-to-render `@xyflow/react` nodes — unlike `computeElkLayout`/
 * `computeRoadmapTreeLayout`, which return bare positions for a caller to assemble Nodes from,
 * this is a synchronous, purely arithmetic layout (no async graph solver), so it builds the full
 * `Node`/`Edge` objects directly.
 */
export function computeRoadmapTimelineLayout(
  data: RoadmapTimelineResponse,
  focus: { epicId?: string; storyId?: string } = {},
): TimelineLayoutResult {
  const scale = buildTimeScale(data.epics);
  const nodes: TimelineFlowNode[] = [];

  data.epics.forEach((epic, laneIndex) => {
    const laneY = laneIndex * TIMELINE_LANE_HEIGHT;

    const epicRisk = deriveEpicRisk(epic);
    nodes.push({
      id: epic.id,
      type: "timeline-epic-lane",
      position: { x: 0, y: laneY },
      data: {
        epicId: epic.id,
        title: epic.title,
        stage: epic.stage,
        priority: epic.priority,
        isFocused: epic.id === focus.epicId,
        blocked: epicRisk.blocked,
        stalled: epicRisk.stalled,
      },
    });

    const sortedStories = [...epic.stories].sort(compareStories);
    let previousTimeMs: number | null = null;
    let collisionIndex = 0;
    for (const story of sortedStories) {
      const timeMs = new Date(story.createdAt).getTime();
      if (previousTimeMs !== null && timeMs === previousTimeMs) {
        collisionIndex += 1;
      } else {
        collisionIndex = 0;
        previousTimeMs = timeMs;
      }
      const x = scale(timeMs) + collisionIndex * TIMELINE_COLLISION_OFFSET;
      const storyRisk = deriveStoryRisk(story);

      nodes.push({
        id: story.id,
        type: "timeline-story",
        position: { x, y: laneY },
        data: {
          storyId: story.id,
          epicId: epic.id,
          epicTitle: epic.title,
          title: story.title,
          stage: story.stage,
          priority: story.priority,
          createdAt: story.createdAt,
          isFocused: story.id === focus.storyId,
          blocked: storyRisk.blocked,
          stalled: storyRisk.stalled,
        },
      });
    }
  });

  // No dependency/hierarchy edges in this view (a time-scale lane layout, not a
  // graph); every Epic/Story's position on its own lane already conveys the relationship.
  return { nodes, edges: [] };
}
