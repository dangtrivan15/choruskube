import type { RoadmapCandidatesDocument } from "@/lib/types";
import type { RoadmapItemType } from "@/components/roadmap/RoadmapGraphNode";

/** A candidate item as a graph node. `id` is a synthetic, stable-within-a-render
 * identifier (candidate items have no DB id pre-materialization); `key` is the
 * artifact-local key a dependency can reference, or null when the item is
 * unkeyed and therefore cannot be a dependency endpoint. */
export interface CandidateGraphNode {
  id: string;
  parentId: string | null;
  itemType: RoadmapItemType;
  label: string;
  key: string | null;
  priority: string | null;
}

/** A resolved candidate dependency: `blocking`/`blocked` are the artifact-local
 * keys (as stored in the document); `source`/`target` are the corresponding
 * node ids for layout/rendering. */
export interface CandidateGraphEdge {
  id: string;
  source: string;
  target: string;
  blocking: string;
  blocked: string;
}

export interface CandidateGraphModel {
  nodes: CandidateGraphNode[];
  edges: CandidateGraphEdge[];
}

/** Appends a blocking→blocked edge, or returns the document unchanged if that
 * exact edge already exists. Never mutates the input. */
export function addCandidateDependency(
  doc: RoadmapCandidatesDocument,
  blocking: string,
  blocked: string,
): RoadmapCandidatesDocument {
  if (doc.dependencies.some((d) => d.blocking === blocking && d.blocked === blocked)) {
    return doc;
  }
  return { ...doc, dependencies: [...doc.dependencies, { blocking, blocked }] };
}

/** Removes the matching blocking→blocked edge. Never mutates the input. */
export function removeCandidateDependency(
  doc: RoadmapCandidatesDocument,
  blocking: string,
  blocked: string,
): RoadmapCandidatesDocument {
  return {
    ...doc,
    dependencies: doc.dependencies.filter((d) => !(d.blocking === blocking && d.blocked === blocked)),
  };
}

/**
 * Whether adding blocking→blocked would close a directed cycle. An edge means
 * "blocking must finish before blocked", so a cycle exists iff `blocked` can
 * already reach `blocking` by following existing edges (or the two are the same
 * item). Mirrors the server-side guard so a bad edge is rejected in the UI
 * rather than silently dropped at materialization.
 */
export function wouldCreateCandidateCycle(
  doc: RoadmapCandidatesDocument,
  blocking: string,
  blocked: string,
): boolean {
  if (blocking === blocked) return true;
  const adjacency = new Map<string, string[]>();
  for (const d of doc.dependencies) {
    const targets = adjacency.get(d.blocking);
    if (targets) targets.push(d.blocked);
    else adjacency.set(d.blocking, [d.blocked]);
  }
  const stack = [blocked];
  const seen = new Set<string>();
  while (stack.length > 0) {
    const current = stack.pop() as string;
    if (current === blocking) return true;
    if (seen.has(current)) continue;
    seen.add(current);
    for (const next of adjacency.get(current) ?? []) stack.push(next);
  }
  return false;
}

/** Why a proposed connection was refused, or `doc` with the edit applied. */
export interface ConnectResult {
  doc?: RoadmapCandidatesDocument;
  error?: "unkeyed" | "cycle";
}

/**
 * Resolves a canvas connection (two node ids, source blocks target) into a
 * guarded dependency edit: refuses when either endpoint is unkeyed (an unkeyed
 * item cannot be referenced) or when the edge would close a cycle; otherwise
 * returns the document with the edge added.
 */
export function connectCandidateNodes(
  model: CandidateGraphModel,
  doc: RoadmapCandidatesDocument,
  sourceNodeId: string,
  targetNodeId: string,
): ConnectResult {
  const source = model.nodes.find((n) => n.id === sourceNodeId);
  const target = model.nodes.find((n) => n.id === targetNodeId);
  if (!source?.key || !target?.key) return { error: "unkeyed" };
  if (wouldCreateCandidateCycle(doc, source.key, target.key)) return { error: "cycle" };
  return { doc: addCandidateDependency(doc, source.key, target.key) };
}

/**
 * Flattens a candidate document into a node/edge model for the graph. Ids are
 * synthetic (path-based) so unkeyed items still lay out; a `key→id` map resolves
 * dependency endpoints, and any dependency whose key does not resolve to a keyed
 * item is dropped (matching the server, which pre-cleans unresolved edges).
 */
export function candidateDocToGraph(doc: RoadmapCandidatesDocument): CandidateGraphModel {
  const nodes: CandidateGraphNode[] = [];
  const keyToId = new Map<string, string>();

  const register = (node: CandidateGraphNode) => {
    nodes.push(node);
    if (node.key) keyToId.set(node.key, node.id);
  };

  doc.epics.forEach((epicItem, epicIdx) => {
    const epicId = `e${epicIdx}`;
    register({
      id: epicId,
      parentId: null,
      itemType: "epic",
      label: epicItem.title,
      key: epicItem.key ?? null,
      priority: epicItem.priority ?? null,
    });
    epicItem.stories.forEach((storyItem, storyIdx) => {
      const storyId = `${epicId}s${storyIdx}`;
      register({
        id: storyId,
        parentId: epicId,
        itemType: "story",
        label: storyItem.title,
        key: storyItem.key ?? null,
        priority: storyItem.priority ?? null,
      });
      storyItem.tasks.forEach((taskItem, taskIdx) => {
        register({
          id: `${storyId}t${taskIdx}`,
          parentId: storyId,
          itemType: "task",
          label: taskItem.title,
          key: taskItem.key ?? null,
          priority: taskItem.priority ?? null,
        });
      });
    });
  });

  const edges: CandidateGraphEdge[] = [];
  doc.dependencies.forEach((dep, i) => {
    const source = keyToId.get(dep.blocking);
    const target = keyToId.get(dep.blocked);
    if (!source || !target) return;
    edges.push({ id: `dep:${i}`, source, target, blocking: dep.blocking, blocked: dep.blocked });
  });

  return { nodes, edges };
}
