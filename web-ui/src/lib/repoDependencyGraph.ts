import ELK from "elkjs/lib/elk.bundled.js";
import type { RepoDependency } from "@/lib/types";

/** One arrow: `from` depends on `to`. */
export interface DependencyArrow {
  from: string;
  to: string;
}

export function toArrows(dependencies: RepoDependency[]): DependencyArrow[] {
  return dependencies.flatMap((d) => d.dependsOnRepoIds.map((to) => ({ from: d.repoId, to })));
}

export function addArrow(dependencies: RepoDependency[], arrow: DependencyArrow): RepoDependency[] {
  const existing = dependencies.find((d) => d.repoId === arrow.from);
  if (existing?.dependsOnRepoIds.includes(arrow.to)) return dependencies;
  if (!existing) return [...dependencies, { repoId: arrow.from, dependsOnRepoIds: [arrow.to] }];
  return dependencies.map((d) =>
    d === existing ? { ...d, dependsOnRepoIds: [...d.dependsOnRepoIds, arrow.to] } : d,
  );
}

/** Drops the arrow, and the repo's entry with it once it depends on nothing. */
export function removeArrow(dependencies: RepoDependency[], arrow: DependencyArrow): RepoDependency[] {
  return dependencies.flatMap((d) => {
    if (d.repoId !== arrow.from) return [d];
    const rest = d.dependsOnRepoIds.filter((to) => to !== arrow.to);
    return rest.length > 0 ? [{ ...d, dependsOnRepoIds: rest }] : [];
  });
}

/**
 * The chain an arrow `from -> to` would close into a loop, as repo ids starting and ending at
 * `from` (`[from, to, ..., from]`), or null when the arrow is safe. A loop exists exactly when
 * `to` already depends on `from`, directly or through other repos.
 */
export function cycleThrough(dependencies: RepoDependency[], arrow: DependencyArrow): string[] | null {
  if (arrow.from === arrow.to) return [arrow.from, arrow.from];
  const next = new Map(dependencies.map((d) => [d.repoId, d.dependsOnRepoIds]));
  const seen = new Set<string>();
  function pathToFrom(node: string): string[] | null {
    if (node === arrow.from) return [node];
    if (seen.has(node)) return null;
    seen.add(node);
    for (const dep of next.get(node) ?? []) {
      const rest = pathToFrom(dep);
      if (rest) return [node, ...rest];
    }
    return null;
  }
  const path = pathToFrom(arrow.to);
  return path ? [arrow.from, ...path] : null;
}

export const REPO_NODE_WIDTH = 168;
export const REPO_NODE_HEIGHT = 40;

const elk = new ELK();

/**
 * Positions for a layered drawing with dependencies at the bottom: a repo that depends on
 * nothing sits in the bottom row, and every other repo one row above the highest repo it
 * depends on. ELK lays the arrows out reversed and upward so that "depends on nothing" lands
 * in its first layer, which LONGEST_PATH_SOURCE fills with exactly those repos.
 */
export async function layoutRepoDependencies(
  repoIds: string[],
  dependencies: RepoDependency[],
): Promise<Map<string, { x: number; y: number }>> {
  const laidOut = await elk.layout({
    id: "root",
    layoutOptions: {
      "elk.algorithm": "layered",
      "elk.direction": "UP",
      "elk.layered.layering.strategy": "LONGEST_PATH_SOURCE",
      // Keeps unconnected repos in the same picture instead of separate side-by-side boxes.
      "elk.separateConnectedComponents": "false",
      "elk.spacing.nodeNode": "32",
      "elk.layered.spacing.nodeNodeBetweenLayers": "80",
    },
    children: repoIds.map((id) => ({ id, width: REPO_NODE_WIDTH, height: REPO_NODE_HEIGHT })),
    edges: toArrows(dependencies)
      .filter((a) => repoIds.includes(a.from) && repoIds.includes(a.to))
      .map((a) => ({ id: `${a.from}->${a.to}`, sources: [a.to], targets: [a.from] })),
  });
  return new Map((laidOut.children ?? []).map((c) => [c.id, { x: c.x ?? 0, y: c.y ?? 0 }]));
}
