import { ROADMAP_EDGE_STYLES, type RoadmapEdgeStyle } from "@/lib/roadmapEdgeStyles";

/**
 * Inline SVG line swatch drawn with the exact stroke color and dash array a
 * `ROADMAP_EDGE_STYLES` entry's edges render with, so this legend can never
 * approximate a pattern (e.g. showing a dash-dot edge as plain dashed) the
 * way the previous hardcoded `border-dashed`/`border-dotted` classes did.
 */
function EdgeSwatch({ style, opacity }: { style: RoadmapEdgeStyle; opacity?: number }) {
  return (
    <svg width="24" height="8" viewBox="0 0 24 8" className="shrink-0" aria-hidden="true">
      <line
        x1="0"
        y1="4"
        x2="24"
        y2="4"
        stroke={`var(${style.token})`}
        strokeWidth="2"
        strokeDasharray={style.dashArray || undefined}
        opacity={opacity}
      />
    </svg>
  );
}

/**
 * Small legend labeling the roadmap graph canvas's four edge styles — grew
 * from the original quiet hierarchy edge and within-Epic blocking dependency
 * edge, to a third kind for cross-Epic dependencies, to a fourth
 * for Epic-tier dependencies (an edge whose blocking/blocked endpoint is the
 * Epic itself — see `RoadmapEpicDependencyEdge`) — so each new edge language
 * stays discoverable without trial and error.
 */
export default function RoadmapGraphLegend() {
  return (
    <div
      data-testid="roadmap-graph-legend"
      className="pointer-events-none absolute top-3 right-3 z-10 flex flex-col gap-1.5 rounded-md border bg-background/90 px-3 py-2 text-xs shadow-sm backdrop-blur"
    >
      <div className="flex items-center gap-2" data-testid="roadmap-graph-legend-hierarchy">
        <EdgeSwatch style={ROADMAP_EDGE_STYLES.hierarchy} opacity={0.5} />
        <span className="text-muted-foreground">{ROADMAP_EDGE_STYLES.hierarchy.label}</span>
      </div>
      <div className="flex items-center gap-2" data-testid="roadmap-graph-legend-dependency">
        <EdgeSwatch style={ROADMAP_EDGE_STYLES.dependency} />
        <span className="text-muted-foreground">{ROADMAP_EDGE_STYLES.dependency.label}</span>
      </div>
      <div className="flex items-center gap-2" data-testid="roadmap-graph-legend-epic-dependency">
        <EdgeSwatch style={ROADMAP_EDGE_STYLES.epicDependency} />
        <span className="text-muted-foreground">{ROADMAP_EDGE_STYLES.epicDependency.label}</span>
      </div>
      <div className="flex items-center gap-2" data-testid="roadmap-graph-legend-cross-epic">
        <EdgeSwatch style={ROADMAP_EDGE_STYLES.crossEpic} />
        <span className="text-muted-foreground">{ROADMAP_EDGE_STYLES.crossEpic.label}</span>
      </div>
    </div>
  );
}
