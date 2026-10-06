import { describe, it, expect } from "vitest";
import { mergeMethodOf, pullRequestStatus } from "../mergeOnApproval";
import type { RunPullRequestResponse, SnapshotNode } from "@/lib/types";

function makeNode(configOverrides?: Record<string, unknown>): SnapshotNode {
  return {
    template_node_id: "node-1",
    label: "final_approval",
    executor_type: "human",
    is_entrypoint: false,
    timeout_seconds: 86400,
    config_overrides: configOverrides,
  };
}

function makePr(overrides: Partial<RunPullRequestResponse> = {}): RunPullRequestResponse {
  return {
    id: "pr-1",
    workflowRunId: "run-1",
    gitRepoId: "repo-1",
    nodeExecutionId: null,
    prUrl: "https://github.com/org/repo/pull/1",
    prNumber: 1,
    title: "feat: x",
    repoName: "repo",
    repoUrl: "https://github.com/org/repo",
    createdAt: new Date().toISOString(),
    state: null,
    mergedAt: null,
    ...overrides,
  };
}

describe("mergeMethodOf", () => {
  it("returns the method for a valid value", () => {
    expect(mergeMethodOf(makeNode({ merge_pull_requests: "squash" }))).toBe("squash");
    expect(mergeMethodOf(makeNode({ merge_pull_requests: "merge" }))).toBe("merge");
    expect(mergeMethodOf(makeNode({ merge_pull_requests: "rebase" }))).toBe("rebase");
  });

  it("returns null for an invalid value", () => {
    expect(mergeMethodOf(makeNode({ merge_pull_requests: "bogus" }))).toBeNull();
  });

  it("returns null for a non-string value", () => {
    expect(mergeMethodOf(makeNode({ merge_pull_requests: 42 }))).toBeNull();
  });

  it("returns null when the key is missing", () => {
    expect(mergeMethodOf(makeNode({ loop_group: "impl-review" }))).toBeNull();
  });

  it("returns null when config_overrides itself is missing", () => {
    expect(mergeMethodOf(makeNode(undefined))).toBeNull();
  });

  it("returns null for an undefined node", () => {
    expect(mergeMethodOf(undefined)).toBeNull();
  });
});

describe("pullRequestStatus", () => {
  it("returns merged when mergedAt is set, even if state is stale", () => {
    expect(pullRequestStatus(makePr({ mergedAt: "2026-01-01T00:00:00Z", state: "open" }))).toBe("merged");
  });

  it("returns open when state is open and not merged", () => {
    expect(pullRequestStatus(makePr({ state: "open" }))).toBe("open");
  });

  it("returns closed when state is closed and not merged", () => {
    expect(pullRequestStatus(makePr({ state: "closed" }))).toBe("closed");
  });

  it("returns unknown when neither mergedAt nor state is set", () => {
    expect(pullRequestStatus(makePr({ state: null, mergedAt: null }))).toBe("unknown");
  });
});
