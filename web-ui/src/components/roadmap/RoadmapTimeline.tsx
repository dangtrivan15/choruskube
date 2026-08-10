import { useMemo } from "react";
import { ReactFlow, Controls, Background } from "@xyflow/react";
import "@xyflow/react/dist/style.css";

import type { RoadmapTimelineResponse } from "@/lib/types";
import { computeRoadmapTimelineLayout } from "@/lib/timelineLayout";
import { RoadmapTimelineEpicLaneNode, RoadmapTimelineStoryNode } from "./RoadmapTimelineNode";

const nodeTypes = {
  "timeline-epic-lane": RoadmapTimelineEpicLaneNode,
  "timeline-story": RoadmapTimelineStoryNode,
};

interface RoadmapTimelineProps {
  data: RoadmapTimelineResponse;
}

/**
 * Roadmap Timeline View canvas: one horizontal lane per Epic, its Stories plotted along a shared
 * time axis. Renders on the same `@xyflow/react` substrate as the Roadmap Graph View, but with
 * `computeRoadmapTimelineLayout`'s synchronous time-scale layout instead of ELK — there is no
 * async layout step here, so (unlike RoadmapGraph) this needs no `useEffect`/loading-fallback
 * dance around the layout call.
 */
export default function RoadmapTimeline({ data }: RoadmapTimelineProps) {
  const { nodes, edges } = useMemo(() => computeRoadmapTimelineLayout(data), [data]);

  return (
    <div data-testid="roadmap-timeline-container" className="relative h-full w-full">
      <ReactFlow
        nodes={nodes}
        edges={edges}
        nodeTypes={nodeTypes}
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
