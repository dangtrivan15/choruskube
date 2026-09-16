import { describe, it, expect } from "vitest";
import {
  addCandidateDependency,
  removeCandidateDependency,
  wouldCreateCandidateCycle,
  candidateDocToGraph,
  connectCandidateNodes,
} from "../roadmapCandidateGraph";
import type { CandidateEpicProposal, RoadmapCandidatesDocument } from "../types";

function doc(deps: { blocking: string; blocked: string }[] = []): RoadmapCandidatesDocument {
  return { milestones: [], epics: [], dependencies: deps };
}

function epic(overrides: Partial<CandidateEpicProposal> & { title: string }): CandidateEpicProposal {
  return {
    description: "",
    motivation: "",
    repos: null,
    priority: null,
    stories: [],
    ...overrides,
  };
}

const richDoc: RoadmapCandidatesDocument = {
  milestones: [],
  epics: [
    epic({
      title: "Epic A",
      key: "epA",
      stories: [
        {
          title: "Story A1",
          description: "",
          key: "stA1",
          tasks: [
            { title: "Task A1a", description: "", key: "tkA1a" },
            { title: "Task A1b", description: "" }, // unkeyed — cannot be a dependency endpoint
          ],
        },
      ],
    }),
    epic({ title: "Epic B", key: "epB" }),
  ],
  dependencies: [
    { blocking: "tkA1a", blocked: "epB" }, // resolvable
    { blocking: "ghost", blocked: "epB" }, // unresolvable key — dropped
  ],
};

describe("addCandidateDependency", () => {
  it("appends a new blocking→blocked edge", () => {
    const next = addCandidateDependency(doc(), "a", "b");
    expect(next.dependencies).toEqual([{ blocking: "a", blocked: "b" }]);
  });

  it("is idempotent — an existing edge is not duplicated", () => {
    const next = addCandidateDependency(doc([{ blocking: "a", blocked: "b" }]), "a", "b");
    expect(next.dependencies).toEqual([{ blocking: "a", blocked: "b" }]);
  });

  it("does not mutate the input document", () => {
    const input = doc();
    addCandidateDependency(input, "a", "b");
    expect(input.dependencies).toEqual([]);
  });
});

describe("removeCandidateDependency", () => {
  it("removes the matching edge and leaves the rest", () => {
    const next = removeCandidateDependency(
      doc([
        { blocking: "a", blocked: "b" },
        { blocking: "b", blocked: "c" },
      ]),
      "a",
      "b",
    );
    expect(next.dependencies).toEqual([{ blocking: "b", blocked: "c" }]);
  });
});

describe("wouldCreateCandidateCycle", () => {
  it("rejects a self-edge", () => {
    expect(wouldCreateCandidateCycle(doc(), "a", "a")).toBe(true);
  });

  it("rejects a direct back-edge (a→b already exists, adding b→a)", () => {
    expect(wouldCreateCandidateCycle(doc([{ blocking: "a", blocked: "b" }]), "b", "a")).toBe(true);
  });

  it("rejects a transitive cycle (a→b, b→c exist, adding c→a)", () => {
    const d = doc([
      { blocking: "a", blocked: "b" },
      { blocking: "b", blocked: "c" },
    ]);
    expect(wouldCreateCandidateCycle(d, "c", "a")).toBe(true);
  });

  it("allows an edge that introduces no cycle", () => {
    expect(wouldCreateCandidateCycle(doc([{ blocking: "a", blocked: "b" }]), "a", "c")).toBe(false);
  });
});

describe("candidateDocToGraph", () => {
  it("emits one node per Epic/Story/Task with the right parent and label", () => {
    const { nodes } = candidateDocToGraph(richDoc);
    expect(nodes).toHaveLength(5); // 2 epics + 1 story + 2 tasks

    const epicA = nodes.find((n) => n.label === "Epic A")!;
    expect(epicA.itemType).toBe("epic");
    expect(epicA.parentId).toBeNull();

    const storyA1 = nodes.find((n) => n.label === "Story A1")!;
    expect(storyA1.itemType).toBe("story");
    expect(storyA1.parentId).toBe(epicA.id);

    const taskA1a = nodes.find((n) => n.label === "Task A1a")!;
    expect(taskA1a.itemType).toBe("task");
    expect(taskA1a.parentId).toBe(storyA1.id);
  });

  it("carries each item's key, and null for unkeyed items", () => {
    const { nodes } = candidateDocToGraph(richDoc);
    expect(nodes.find((n) => n.label === "Task A1a")!.key).toBe("tkA1a");
    expect(nodes.find((n) => n.label === "Task A1b")!.key).toBeNull();
  });

  it("resolves dependency keys to node ids and drops edges referencing unknown keys", () => {
    const { nodes, edges } = candidateDocToGraph(richDoc);
    expect(edges).toHaveLength(1); // the "ghost" edge is dropped

    const blocking = nodes.find((n) => n.key === "tkA1a")!;
    const blocked = nodes.find((n) => n.key === "epB")!;
    expect(edges[0]).toMatchObject({
      source: blocking.id,
      target: blocked.id,
      blocking: "tkA1a",
      blocked: "epB",
    });
  });
});

describe("connectCandidateNodes", () => {
  const model = candidateDocToGraph(richDoc);
  const idFor = (key: string) => model.nodes.find((n) => n.key === key)!.id;
  const idForLabel = (label: string) => model.nodes.find((n) => n.label === label)!.id;

  it("adds a dependency between two keyed nodes", () => {
    const result = connectCandidateNodes(model, richDoc, idFor("stA1"), idFor("epB"));
    expect(result.error).toBeUndefined();
    expect(result.doc?.dependencies).toContainEqual({ blocking: "stA1", blocked: "epB" });
  });

  it("rejects a connection touching an unkeyed item", () => {
    const result = connectCandidateNodes(model, richDoc, idForLabel("Task A1b"), idFor("epB"));
    expect(result.doc).toBeUndefined();
    expect(result.error).toBe("unkeyed");
  });

  it("rejects a connection that would create a cycle", () => {
    // richDoc already has tkA1a → epB; connecting epB → tkA1a closes the loop.
    const result = connectCandidateNodes(model, richDoc, idFor("epB"), idFor("tkA1a"));
    expect(result.doc).toBeUndefined();
    expect(result.error).toBe("cycle");
  });
});
