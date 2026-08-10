import { useCallback, useEffect, useMemo, useRef } from "react";
import { ReactFlow, Controls, Background, type NodeMouseHandler, type ReactFlowInstance } from "@xyflow/react";
import "@xyflow/react/dist/style.css";

import type { RoadmapTimelineResponse } from "@/lib/types";
import { computeRoadmapTimelineLayout, type TimelineFlowNode } from "@/lib/timelineLayout";
import { RoadmapTimelineEpicLaneNode, RoadmapTimelineStoryNode } from "./RoadmapTimelineNode";

const nodeTypes = {
  "timeline-epic-lane": RoadmapTimelineEpicLaneNode,
  "timeline-story": RoadmapTimelineStoryNode,
};

/** Zoom level / animation duration (ms) used when panning to a newly-focused node (§3.3, Caveat 4). */
const FOCUS_PAN_ZOOM = 1;
const FOCUS_PAN_DURATION_MS = 400;

interface RoadmapTimelineProps {
  data: RoadmapTimelineResponse;
  focusedEpicId?: string;
  focusedStoryId?: string;
  /** Called when an Epic lane or Story marker is clicked — `storyId` is omitted for a lane click. */
  onFocusChange?: (epicId: string, storyId?: string) => void;
}

/**
 * Roadmap Timeline View canvas: one horizontal lane per Epic, its Stories plotted along a shared
 * time axis. Renders on the same `@xyflow/react` substrate as the Roadmap Graph View, but with
 * `computeRoadmapTimelineLayout`'s synchronous time-scale layout instead of ELK — there is no
 * async layout step here, so (unlike RoadmapGraph) this needs no `useEffect`/loading-fallback
 * dance around the layout call.
 */
export default function RoadmapTimeline({
  data,
  focusedEpicId,
  focusedStoryId,
  onFocusChange,
}: RoadmapTimelineProps) {
  const { nodes, edges } = useMemo(
    () => computeRoadmapTimelineLayout(data, { epicId: focusedEpicId, storyId: focusedStoryId }),
    [data, focusedEpicId, focusedStoryId],
  );
  const flowInstanceRef = useRef<ReactFlowInstance<TimelineFlowNode> | null>(null);

  const onNodeClick: NodeMouseHandler<TimelineFlowNode> = useCallback(
    (_event, node) => {
      if (!onFocusChange) return;
      if (node.type === "timeline-epic-lane") {
        onFocusChange(node.data.epicId);
      } else if (node.type === "timeline-story") {
        onFocusChange(node.data.epicId, node.data.storyId);
      }
    },
    [onFocusChange],
  );

  // Pan/center on whatever just became focused (§3.3) — the Story marker if one is focused,
  // otherwise the Epic lane. A no-op if the focused id isn't present in the current layout (a
  // deleted or otherwise-missing Epic/Story — §6's Negative/security case).
  useEffect(() => {
    const instance = flowInstanceRef.current;
    const focusedId = focusedStoryId ?? focusedEpicId;
    if (!instance || !focusedId) return;

    const node = nodes.find((n) => n.id === focusedId);
    if (!node) return;

    instance.setCenter(node.position.x, node.position.y, {
      zoom: FOCUS_PAN_ZOOM,
      duration: FOCUS_PAN_DURATION_MS,
    });
  }, [focusedEpicId, focusedStoryId, nodes]);

  return (
    <div data-testid="roadmap-timeline-container" className="relative h-full w-full">
      <ReactFlow
        nodes={nodes}
        edges={edges}
        nodeTypes={nodeTypes}
        onInit={(instance) => {
          flowInstanceRef.current = instance;
        }}
        onNodeClick={onNodeClick}
        fitView
        fitViewOptions={{ padding: 0.2 }}
        nodesDraggable={false}
        nodesConnectable={false}
        elementsSelectable={true}
        minZoom={0.1}
        maxZoom={2}
        proOptions={{ hideAttribution: true }}
      >
        <Controls showInteractive={false} />
        <Background gap={16} size={1} />
      </ReactFlow>
    </div>
  );
}
