import { Link } from "react-router";
import StatusCallout from "@/components/ui/StatusCallout";
import { useStory } from "@/hooks/useStories";
import { useStoryConflicts } from "@/hooks/useStorySpec";
import { useStorySubscription } from "@/hooks/useStorySubscription";
import { revisionEndedWithoutVersion } from "@/lib/storyConflicts";

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
  // The Run page mounts no roadmap subscription, and the revision run is assigned after the
  // conflict is recorded: without this the notice never learns which run took it up.
  useStorySubscription(storyId);
  const conflict = conflicts?.find((c) => c.taskRunId === runId);
  const revisionRunId = conflict?.revisionRunId ?? null;
  const revisionEnded = conflict != null && revisionEndedWithoutVersion(conflict);
  // Revise is refused while another revision is open, which then releases this conflict on its version.
  const { data: story } = useStory(revisionEnded ? (storyId ?? undefined) : undefined);
  const otherRevisionOpen = revisionEnded && story?.specUnderRevision === true;

  return (
    <StatusCallout tone="info" data-testid="await-story-revision-notice" title="Waiting on a Story revision">
      <p>
        This Task found its Story&rsquo;s spec wrong for it. The run resumes on its own once a revision of
        the spec is approved.
      </p>
      {storyId && (
        <p className="mt-1">
          {revisionEnded ? (
            <>
              The <Link to={`/runs/${revisionRunId}`} className="underline">revision run</Link> that took up
              this conflict ended without a new version.{" "}
              {otherRevisionOpen
                ? "Another revision of the Story is open; it was not asked about this conflict, and this run resumes on the version it records."
                : "None restarts on its own; a Revise of the Story answers this conflict."}
            </>
          ) : revisionRunId ? (
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
