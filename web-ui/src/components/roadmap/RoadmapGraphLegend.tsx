/**
 * Small legend labeling the roadmap graph canvas's three edge styles — needed
 * once the canvas grew a third edge kind (cross-Epic dependency, Decision 1)
 * alongside the existing quiet hierarchy edge and the within-Epic blocking
 * dependency edge, so the distinction is discoverable without trial and error
 * (§3.4 of the cross-Epic-blockers spec).
 */
export default function RoadmapGraphLegend() {
  return (
    <div
      data-testid="roadmap-graph-legend"
      className="pointer-events-none absolute top-3 right-3 z-10 flex flex-col gap-1.5 rounded-md border bg-background/90 px-3 py-2 text-xs shadow-sm backdrop-blur"
    >
      <div className="flex items-center gap-2" data-testid="roadmap-graph-legend-hierarchy">
        <span className="h-0 w-6 border-t-2 border-muted-foreground opacity-50" />
        <span className="text-muted-foreground">Hierarchy</span>
      </div>
      <div className="flex items-center gap-2" data-testid="roadmap-graph-legend-dependency">
        <span className="h-0 w-6 border-t-2 border-dashed border-status-warning" />
        <span className="text-muted-foreground">Blocking dependency</span>
      </div>
      <div className="flex items-center gap-2" data-testid="roadmap-graph-legend-cross-epic">
        <span className="h-0 w-6 border-t-2 border-dotted border-status-accent" />
        <span className="text-muted-foreground">Cross-Epic dependency</span>
      </div>
    </div>
  );
}
