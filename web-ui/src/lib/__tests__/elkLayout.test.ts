import { describe, it, expect } from "vitest";
import {
  computeElkLayout,
  elkEdgeId,
  computeRoadmapTreeLayout,
  buildRoadmapTopologyKey,
  roadmapHierarchyEdgeId,
  roadmapDependencyEdgeId,
  type RoadmapTreeInput,
} from "../elkLayout";
import type { GraphSnapshot } from "../types";

const linearSnapshot: GraphSnapshot = {
  nodes: [
    { template_node_id: "a", label: "A", executor_type: "ai", is_entrypoint: true  },
    { template_node_id: "b", label: "B", executor_type: "ai", is_entrypoint: false },
  ],
  edges: [{ template_edge_id: "e-ab", source_node_id: "a", target_node_id: "b", condition: null }],
};

describe("computeElkLayout — basic", () => {
  it("returns top-left positions for every node", async () => {
    const result = await computeElkLayout(linearSnapshot);
    expect(result.nodes.size).toBe(2);
    expect(result.nodes.get("a")).toMatchObject({ x: expect.any(Number), y: expect.any(Number) });
    expect(result.nodes.get("b")).toMatchObject({ x: expect.any(Number), y: expect.any(Number) });
  });

  it("returns a route with at least source and target points for every edge", async () => {
    const result = await computeElkLayout(linearSnapshot);
    const route = result.edges.get(elkEdgeId("a", "b", null));
    expect(route).toBeDefined();
    expect(route!.points.length).toBeGreaterThanOrEqual(2);
  });
});

const fanoutSnapshot: GraphSnapshot = {
  nodes: [
    { template_node_id: "root", label: "Root", executor_type: "ai", is_entrypoint: true  },
    { template_node_id: "c1",   label: "C1",   executor_type: "ai", is_entrypoint: false },
    { template_node_id: "c2",   label: "C2",   executor_type: "ai", is_entrypoint: false },
  ],
  edges: [
    { template_edge_id: "e-rc1", source_node_id: "root", target_node_id: "c1", condition: "approved" },
    { template_edge_id: "e-rc2", source_node_id: "root", target_node_id: "c2", condition: "rejected" },
  ],
};

const cycleSnapshot: GraphSnapshot = {
  nodes: [
    { template_node_id: "a", label: "A", executor_type: "ai", is_entrypoint: true  },
    { template_node_id: "b", label: "B", executor_type: "ai", is_entrypoint: false },
    { template_node_id: "c", label: "C", executor_type: "ai", is_entrypoint: false },
  ],
  edges: [
    { template_edge_id: "e-ab", source_node_id: "a", target_node_id: "b", condition: null },
    { template_edge_id: "e-bc", source_node_id: "b", target_node_id: "c", condition: null },
    { template_edge_id: "e-ca", source_node_id: "c", target_node_id: "a", condition: "retry" },
  ],
};

const isolatedSnapshot: GraphSnapshot = {
  nodes: [
    { template_node_id: "lonely", label: "Lonely", executor_type: "ai", is_entrypoint: false },
  ],
  edges: [],
};

describe("computeElkLayout — topology coverage", () => {
  it("places every node in a fan-out", async () => {
    const result = await computeElkLayout(fanoutSnapshot);
    expect(result.nodes.size).toBe(3);
    expect(result.edges.size).toBe(2);
  });

  it("returns routes for back-edges without throwing", async () => {
    const result = await computeElkLayout(cycleSnapshot);
    expect(result.nodes.size).toBe(3);
    expect(result.edges.size).toBe(3);
    const back = result.edges.get(elkEdgeId("c", "a", "retry"));
    expect(back).toBeDefined();
    expect(back!.points.length).toBeGreaterThanOrEqual(2);
  });

  it("places isolated nodes", async () => {
    const result = await computeElkLayout(isolatedSnapshot);
    expect(result.nodes.size).toBe(1);
    expect(result.nodes.get("lonely")).toBeDefined();
  });
});

describe("computeElkLayout — labels", () => {
  it("returns a placed label rect for edges with a condition", async () => {
    const result = await computeElkLayout(fanoutSnapshot);
    const approved = result.edges.get(elkEdgeId("root", "c1", "approved"));
    expect(approved?.label).toBeDefined();
    expect(approved!.label!.width).toBeGreaterThan(0);
    expect(approved!.label!.height).toBeGreaterThan(0);
  });

  it("does not return a label for edges without a condition", async () => {
    const result = await computeElkLayout(cycleSnapshot);
    const ab = result.edges.get(elkEdgeId("a", "b", null));
    expect(ab?.label).toBeUndefined();
  });
});

// --- Roadmap Graph View: tree layout ---------------------------------------

const roadmapFixture: RoadmapTreeInput = {
  nodes: [
    { id: "epic-1", parentId: null },
    { id: "story-1", parentId: "epic-1" },
    { id: "story-2", parentId: "epic-1" },
    { id: "task-1", parentId: "story-1" },
    { id: "task-2", parentId: "story-1" },
    { id: "task-3", parentId: "story-2" },
  ],
  dependencyEdges: [{ id: "dep-1", source: "task-1", target: "task-3" }],
};

describe("computeRoadmapTreeLayout", () => {
  it("places every node and routes every hierarchy edge for a fixture tree", async () => {
    const result = await computeRoadmapTreeLayout(roadmapFixture);

    expect(result.nodes.size).toBe(6);
    for (const node of roadmapFixture.nodes) {
      expect(result.nodes.get(node.id)).toMatchObject({ x: expect.any(Number), y: expect.any(Number) });
    }

    expect(result.edges.get(roadmapHierarchyEdgeId("epic-1", "story-1"))).toBeDefined();
    expect(result.edges.get(roadmapHierarchyEdgeId("epic-1", "story-2"))).toBeDefined();
    expect(result.edges.get(roadmapHierarchyEdgeId("story-1", "task-1"))).toBeDefined();
  });

  it("routes a blocking dependency edge distinctly from hierarchy edges", async () => {
    const result = await computeRoadmapTreeLayout(roadmapFixture);

    const dependencyRoute = result.edges.get(roadmapDependencyEdgeId("dep-1"));
    expect(dependencyRoute).toBeDefined();
    expect(dependencyRoute!.points.length).toBeGreaterThanOrEqual(2);
    // Distinct id namespace from hierarchy edges — never collides with `parent=>child`.
    expect(roadmapDependencyEdgeId("dep-1")).not.toBe(roadmapHierarchyEdgeId("task-1", "task-3"));
  });

  it("produces stable output across repeated calls for the same fixture", async () => {
    const first = await computeRoadmapTreeLayout(roadmapFixture);
    const second = await computeRoadmapTreeLayout(roadmapFixture);

    expect([...second.nodes.entries()]).toEqual([...first.nodes.entries()]);
    expect([...second.edges.keys()].sort()).toEqual([...first.edges.keys()].sort());
  });

  it("omits a dependency edge whose endpoint has been filtered out (collapsed away)", async () => {
    const withoutTask3: RoadmapTreeInput = {
      nodes: roadmapFixture.nodes.filter((n) => n.id !== "task-3"),
      dependencyEdges: roadmapFixture.dependencyEdges,
    };
    const result = await computeRoadmapTreeLayout(withoutTask3);
    expect(result.edges.get(roadmapDependencyEdgeId("dep-1"))).toBeUndefined();
  });
});

describe("buildRoadmapTopologyKey", () => {
  it("is stable for the same topology and collapsed set regardless of set iteration order", () => {
    const a = buildRoadmapTopologyKey(roadmapFixture, new Set(["story-1", "story-2"]));
    const b = buildRoadmapTopologyKey(roadmapFixture, new Set(["story-2", "story-1"]));
    expect(a).toBe(b);
  });

  it("does not change when only status-like data would change (topology + collapse set fixed)", () => {
    const a = buildRoadmapTopologyKey(roadmapFixture, new Set());
    const b = buildRoadmapTopologyKey(roadmapFixture, new Set());
    expect(a).toBe(b);
  });

  it("changes when the collapsed-node set changes, with topology held fixed", () => {
    const collapsed = buildRoadmapTopologyKey(roadmapFixture, new Set(["story-1"]));
    const expanded = buildRoadmapTopologyKey(roadmapFixture, new Set());
    expect(collapsed).not.toBe(expanded);
  });

  it("changes when the underlying topology changes, with collapse set held fixed", () => {
    const withExtraTask: RoadmapTreeInput = {
      nodes: [...roadmapFixture.nodes, { id: "task-4", parentId: "story-2" }],
      dependencyEdges: roadmapFixture.dependencyEdges,
    };
    const before = buildRoadmapTopologyKey(roadmapFixture, new Set());
    const after = buildRoadmapTopologyKey(withExtraTask, new Set());
    expect(before).not.toBe(after);
  });
});
