import type { RunPullRequestResponse, SnapshotNode } from "@/lib/types";

/** Mirrors the API server's `PullRequestMergeMethod` enum — GitHub's own `merge_method` values. */
export type MergeMethod = "merge" | "squash" | "rebase";

const MERGE_METHODS: readonly MergeMethod[] = ["merge", "squash", "rebase"];

/**
 * Reads a snapshot node's `config_overrides.merge_pull_requests` value, narrowed from `unknown`.
 * Returns `null` for a node without the key, or an unrecognized value — the same "inert unless
 * valid" behavior graph validation already enforces at run start.
 */
export function mergeMethodOf(node: SnapshotNode | undefined): MergeMethod | null {
  const overrides = node?.config_overrides;
  if (!overrides) return null;
  const value = overrides.merge_pull_requests;
  if (typeof value !== "string") return null;
  return MERGE_METHODS.includes(value as MergeMethod) ? (value as MergeMethod) : null;
}

export type PullRequestStatus = "merged" | "open" | "closed" | "unknown";

/** `mergedAt` takes precedence over `state`: a merged PR's `state` is `closed` on GitHub too. */
export function pullRequestStatus(pr: RunPullRequestResponse): PullRequestStatus {
  if (pr.mergedAt != null) return "merged";
  if (pr.state === "open") return "open";
  if (pr.state === "closed") return "closed";
  return "unknown";
}
