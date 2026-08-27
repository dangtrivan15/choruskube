import { useCallback, useEffect, useMemo, useState } from "react";
import { ReactFlow, Controls, Background, type NodeMouseHandler, type ReactFlowInstance } from "@xyflow/react";
import "@xyflow/react/dist/style.css";

import type { RoadmapTimelineResponse } from "@/lib/types";
import { computeRoadmapTimelineLayout, type TimelineFlowNode } from "@/lib/timelineLayout";
import { TimelineFocusProvider } from "@/lib/timelineFocus";
import { RoadmapTimelineEpicLaneNode, RoadmapTimelineStoryNode } from "./RoadmapTimelineNode";

const nodeTypes = {
  "timeline-epic-lane": RoadmapTimelineEpicLaneNode,
  "timeline-story": RoadmapTimelineStoryNode,
};

/** Zoom level / animation duration (ms) used when panning to a newly-focused node. */
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
  // State, not a ref: `@xyflow/react`'s `onInit` fires asynchronously (internally deferred via
  // `setTimeout`, after `viewportInitialized` flips), strictly after this component's own mount
  // effects have already run once. A ref write from that callback wouldn't trigger a re-render, so
  // the pan-to-focus effect below — whose whole job is restoring focus *on arrival*, i.e.
  // exactly the first-render case — would see a still-null instance and silently never pan. State
  // makes the instance becoming ready itself trigger the re-render this effect depends on.
  const [flowInstance, setFlowInstance] = useState<ReactFlowInstance<TimelineFlowNode> | null>(null);

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

  // Keyboard (Enter/Space) counterpart of onNodeClick above, exposed to the leaf nodes via
  // TimelineFocusContext (pointer clicks keep using onNodeClick unchanged) — a leaf already knows
  // its own epicId/storyId from `data`, so this just forwards to the same onFocusChange. Forwards
  // with exactly one argument for an Epic-lane activation (storyId omitted, not passed as
  // `undefined`) so it's indistinguishable from onNodeClick's own call shape above — callers doing
  // strict arg-list assertions (e.g. `toHaveBeenCalledWith("epic-1")`) see the same call either way.
  const onActivate = useCallback(
    (epicId: string, storyId?: string) => {
      if (storyId) {
        onFocusChange?.(epicId, storyId);
      } else {
        onFocusChange?.(epicId);
      }
    },
    [onFocusChange],
  );

  // Pan/center on whatever just became focused  — the Story marker if one is focused,
  // otherwise the Epic lane. A no-op if the focused id isn't present in the current layout (a
  // deleted or otherwise-missing Epic/Story —'s Negative/security case), or if the instance
  // isn't ready yet (re-runs once `flowInstance` itself changes from null to set).
  useEffect(() => {
    const focusedId = focusedStoryId ?? focusedEpicId;
    if (!flowInstance || !focusedId) return;

    const node = nodes.find((n) => n.id === focusedId);
    if (!node) return;

    flowInstance.setCenter(node.position.x, node.position.y, {
      zoom: FOCUS_PAN_ZOOM,
      duration: FOCUS_PAN_DURATION_MS,
    });
  }, [flowInstance, focusedEpicId, focusedStoryId, nodes]);

  return (
    <div data-testid="roadmap-timeline-container" className="relative h-full w-full">
      <TimelineFocusProvider value={onActivate}>
        <ReactFlow
          nodes={nodes}
          edges={edges}
          nodeTypes={nodeTypes}
          onInit={setFlowInstance}
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
      </TimelineFocusProvider>
    </div>
  );
}
