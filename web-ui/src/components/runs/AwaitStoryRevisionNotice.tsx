import { Link } from "react-router";
import StatusCallout from "@/components/ui/StatusCallout";
import { useStoryConflicts } from "@/hooks/useStorySpec";

interface AwaitStoryRevisionNoticeProps {
  /** The parked Task run, matched against each conflict's `taskRunId`. */
  runId: string;
  storyId: string | null;
  epicId: string | null;
}

/**
 * Shown on a Task run's Await Story Revision gate: the run raised a conflict with its Story's spec
 * and is parked until a revision answers it. The conflict report itself is in the gate's artifacts.
 */
export default function AwaitStoryRevisionNotice({ runId, storyId, epicId }: AwaitStoryRevisionNoticeProps) {
  const { data: conflicts } = useStoryConflicts(storyId ?? undefined);
  const revisionRunId = conflicts?.find((c) => c.taskRunId === runId)?.revisionRunId ?? null;

  return (
    <StatusCallout tone="info" data-testid="await-story-revision-notice" title="Waiting on a Story revision">
      <p>
        This Task found its Story&rsquo;s spec wrong for it. The run resumes on its own once a revision of
        the spec is approved.
      </p>
      {storyId && (
        <p className="mt-1">
          {revisionRunId ? (
            <>
              The <Link to={`/runs/${revisionRunId}`} className="underline">revision run</Link> is answering
              this conflict.
            </>
          ) : (
            "No revision run has taken up this conflict yet; one starts automatically."
          )}
          {epicId && (
            <>
              {" "}
              <Link to={`/roadmap/epics/${epicId}/stories/${storyId}`} className="underline">
                Open the Story
              </Link>
              .
            </>
          )}
        </p>
      )}
    </StatusCallout>
  );
}
