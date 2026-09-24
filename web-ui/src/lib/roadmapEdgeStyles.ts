export type RoadmapEdgeKind = "hierarchy" | "dependency" | "epicDependency" | "crossEpic";

export interface RoadmapEdgeStyle {
  /**
   * Raw CSS custom-property name (e.g. `--status-warning`), not the Tailwind
   * `--color-*` alias. One field feeds every consumer: `var(<token>)` for a
   * component's stroke and the legend's inline-SVG swatch, and
   * `resolveStatusColors()[<token>]` (dagLayout.ts) for a React Flow arrowhead
   * marker's literal color, which is a literal SVG attribute rather than an
   * inline style and cannot resolve `var()`.
   */
  token: string;
  /** SVG `stroke-dasharray` value; empty string means a solid line. */
  dashArray: string;
  label: string;
}

/**
 * Single source of truth for each roadmap dependency-edge kind's color and
 * dash pattern — read by the edge components' stroke, the arrowhead-marker
 * construction at both RoadmapGraph.tsx and RoadmapCandidateGraph.tsx, and
 * RoadmapGraphLegend's swatches, so none of those four call sites can drift
 * from another by carrying its own hardcoded copy. See
 * docs/decisions/2026-09-21---02-roadmap-edge-style-registry.md.
 */
export const ROADMAP_EDGE_STYLES: Record<RoadmapEdgeKind, RoadmapEdgeStyle> = {
  hierarchy: { token: "--muted-foreground", dashArray: "", label: "Hierarchy" },
  dependency: { token: "--status-warning", dashArray: "6 4", label: "Blocking dependency" },
  epicDependency: { token: "--status-info", dashArray: "8 3 2 3", label: "Epic-tier dependency" },
  crossEpic: { token: "--status-accent", dashArray: "2 3", label: "Cross-Epic dependency" },
};
