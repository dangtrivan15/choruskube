import type { StoryConflictResponse } from "@/lib/types";

const ENDED_WITHOUT_VERSION = new Set(["failed", "cancelled"]);

/**
 * Whether the revision run holding this conflict failed or was cancelled; the server never restarts a
 * revision for such a conflict. A deleted run (no status) is not counted: its deletion unassigns the
 * conflict, and the reconciler then starts a revision for it.
 */
export function revisionEndedWithoutVersion(conflict: StoryConflictResponse): boolean {
  return (
    conflict.revisionRunId != null &&
    conflict.revisionRunStatus != null &&
    ENDED_WITHOUT_VERSION.has(conflict.revisionRunStatus)
  );
}
