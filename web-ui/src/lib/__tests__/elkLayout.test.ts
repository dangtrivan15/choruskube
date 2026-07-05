import { describe, it, expect } from "vitest";
import { computeElkLayout, elkEdgeId } from "../elkLayout";
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
