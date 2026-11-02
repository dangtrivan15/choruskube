import type { StoryConflictResponse } from "@/lib/types";

const ENDED_WITHOUT_VERSION = new Set(["failed", "cancelled"]);

/**
 * Whether the revision run holding this conflict ended without recording a new spec version. The
 * server never restarts a revision for such a conflict, so only a human Revise answers it. A run
 * that was deleted reports no status.
 */
export function revisionEndedWithoutVersion(conflict: StoryConflictResponse): boolean {
  return (
    conflict.revisionRunId != null &&
    (conflict.revisionRunStatus == null || ENDED_WITHOUT_VERSION.has(conflict.revisionRunStatus))
  );
}
