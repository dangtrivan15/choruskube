import { useState } from "react";
import { Link } from "react-router";
import { formatDistanceToNow } from "date-fns";
import { PencilLine, Sparkles } from "lucide-react";
import Authorized from "@/components/Authorized";
import PageHeader from "@/components/layout/PageHeader";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { Textarea } from "@/components/ui/textarea";
import ErrorAlert from "@/components/ui/ErrorAlert";
import MarkdownViewer from "@/components/ui/MarkdownViewer";
import StatusCallout from "@/components/ui/StatusCallout";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import {
  useDesignStory,
  useReviseStory,
  useStoryConflicts,
  useStorySpec,
  useStorySpecVersion,
  useStorySpecVersions,
} from "@/hooks/useStorySpec";
import { apiErrorMessage } from "@/lib/api";
import type { StoryConflictResponse, StoryResponse, StorySpecResponse } from "@/lib/types";

/** Matches `StoryReviseRequest`'s server-side `@Size(max = 20000)`. */
const MAX_GUIDANCE_LENGTH = 20000;

interface StorySpecPanelProps {
  story: StoryResponse;
}

/**
 * The Story's approved spec, its earlier versions, and its design state. Design shows while there is
 * neither a spec nor a design run; Revise while there is a spec and no revision open.
 */
export default function StorySpecPanel({ story }: StorySpecPanelProps) {
  const hasSpec = story.specVersion != null;
  const designInFlight = !hasSpec && story.openDesignRunId != null;

  // The Story already says whether a spec exists, so a known-absent spec is not fetched for its 404.
  const specQuery = useStorySpec(hasSpec ? story.id : undefined);
  const { data: versions } = useStorySpecVersions(hasSpec ? story.id : undefined);
  const { data: conflicts } = useStoryConflicts(hasSpec ? story.id : undefined);
  const designStory = useDesignStory();

  const [pickedVersion, setPickedVersion] = useState<number | null>(null);
  const [reviseOpen, setReviseOpen] = useState(false);

  const current = specQuery.data ?? null;
  const viewingOlder = pickedVersion != null && current != null && pickedVersion !== current.version;
  const olderQuery = useStorySpecVersion(story.id, viewingOlder ? pickedVersion : null);
  const shown: StorySpecResponse | null = viewingOlder ? (olderQuery.data ?? null) : current;
  const shownLoading = specQuery.isLoading || (viewingOlder && olderQuery.isLoading);

  const answered = (conflicts ?? []).filter(
    (c) => c.revisionRunId != null && c.revisionRunId === story.openDesignRunId
  );
  const waiting = (conflicts ?? []).filter((c) => c.revisionRunId == null);

  const versionOptions = [...(versions ?? [])].sort((a, b) => b.version - a.version);
  if (current && !versionOptions.some((v) => v.version === current.version)) {
    versionOptions.unshift({ version: current.version, approvedAt: current.approvedAt, runId: current.runId });
  }

  return (
    <section data-testid="story-spec-panel" className="pt-4 border-t space-y-4">
      <PageHeader title="Spec">
        {current && (
          <Select
            value={String(pickedVersion ?? current.version)}
            onValueChange={(v) => {
              // The current version is stored as null so a newer approval replaces it on screen.
              if (v) setPickedVersion(Number(v) === current.version ? null : Number(v));
            }}
          >
            <SelectTrigger
              data-testid="story-spec-version-select"
              aria-label="Spec version"
              size="sm"
              className="w-auto"
            >
              <SelectValue>{versionLabel(pickedVersion ?? current.version, current.version)}</SelectValue>
            </SelectTrigger>
            <SelectContent>
              {versionOptions.map((v) => (
                <SelectItem
                  key={v.version}
                  value={String(v.version)}
                  data-testid={`story-spec-version-option-${v.version}`}
                >
                  {versionLabel(v.version, current.version)}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        )}
        <Authorized require="canOperate">
          {!hasSpec && !designInFlight && (
            <Button
              data-testid="story-spec-design-button"
              size="sm"
              onClick={() => designStory.mutate(story.id)}
              disabled={designStory.isPending}
            >
              <Sparkles className="size-4" />
              {designStory.isPending ? "Starting..." : "Design Story"}
            </Button>
          )}
          {hasSpec && !story.specUnderRevision && (
            <Button
              data-testid="story-spec-revise-button"
              size="sm"
              variant="outline"
              onClick={() => setReviseOpen(true)}
            >
              <PencilLine className="size-4" />
              Revise…
            </Button>
          )}
        </Authorized>
      </PageHeader>

      {designInFlight && (
        <StatusCallout tone="info" data-testid="story-spec-design-in-progress" title="Design in progress">
          <p>
            A <Link to={`/runs/${story.openDesignRunId}`} className="underline">Story Design run</Link> is
            drafting this spec. The Story&rsquo;s Tasks can start once you approve it.
          </p>
        </StatusCallout>
      )}

      {story.specUnderRevision && (
        <StatusCallout tone="warning" data-testid="story-spec-revision-banner" title="Spec under revision">
          <p>
            A <Link to={`/runs/${story.openDesignRunId}`} className="underline">revision run</Link> is
            reworking this spec. Tasks under this Story cannot start until it is approved.
          </p>
          {answered.length > 0 && (
            <>
              <p className="mt-2">It answers conflicts raised by:</p>
              <ConflictList conflicts={answered} />
            </>
          )}
        </StatusCallout>
      )}

      {waiting.length > 0 && (
        <StatusCallout tone="info" data-testid="story-spec-pending-conflicts" title="Waiting on a revision">
          <p>These Task runs found the spec wrong for them and are waiting for it to be revised:</p>
          <ConflictList conflicts={waiting} />
        </StatusCallout>
      )}

      {!hasSpec && !designInFlight && (
        <p data-testid="story-spec-empty" className="text-sm text-muted-foreground">
          No spec yet. The Story&rsquo;s Tasks can start once a Story Design run&rsquo;s spec is approved.
        </p>
      )}

      {hasSpec && specQuery.isError && <ErrorAlert message="Failed to load the Story spec." />}
      {viewingOlder && olderQuery.isError && <ErrorAlert message={`Failed to load spec v${pickedVersion}.`} />}
      {hasSpec && shownLoading && <Skeleton className="h-24 w-full" />}
      {shown && !shownLoading && (
        <div className="space-y-2">
          <p className="text-xs text-muted-foreground">
            v{shown.version} approved {formatDistanceToNow(new Date(shown.approvedAt), { addSuffix: true })}
            {shown.runId && (
              <>
                {" · "}
                <Link data-testid="story-spec-run-link" to={`/runs/${shown.runId}`} className="underline">
                  design run
                </Link>
              </>
            )}
          </p>
          <MarkdownViewer content={shown.content} maxHeight="max-h-96" />
        </div>
      )}

      {current && (
        <ReviseStoryDialog
          storyId={story.id}
          version={current.version}
          open={reviseOpen}
          onOpenChange={setReviseOpen}
        />
      )}
    </section>
  );
}

function versionLabel(version: number, currentVersion: number) {
  return version === currentVersion ? `v${version} (current)` : `v${version}`;
}

/** Each entry links the Task run that raised it, which is where its conflict report lives. */
function ConflictList({ conflicts }: { conflicts: StoryConflictResponse[] }) {
  return (
    <ul className="mt-1 list-disc pl-4">
      {conflicts.map((c) => (
        <li key={c.id}>
          <Link to={`/runs/${c.taskRunId}`} className="underline">
            {c.taskTitle ?? "A Task run"}
          </Link>{" "}
          <span className="text-muted-foreground">(against v{c.raisedAgainstVersion})</span>
        </li>
      ))}
    </ul>
  );
}

interface ReviseStoryDialogProps {
  storyId: string;
  version: number;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

function ReviseStoryDialog({ storyId, version, open, onOpenChange }: ReviseStoryDialogProps) {
  const [guidance, setGuidance] = useState("");
  const reviseStory = useReviseStory();

  function close() {
    onOpenChange(false);
    reviseStory.reset();
  }

  function handleSubmit() {
    if (!guidance.trim()) return;
    reviseStory.mutate(
      { storyId, guidance: guidance.trim() },
      {
        onSuccess: () => {
          setGuidance("");
          close();
        },
      }
    );
  }

  return (
    <Dialog
      open={open}
      onOpenChange={(isOpen) => {
        if (isOpen) onOpenChange(true);
        else close();
      }}
    >
      <DialogContent size="lg">
        <DialogHeader>
          <DialogTitle>Revise Story spec</DialogTitle>
          <DialogDescription>
            Starts a revision run of v{version}. The revised spec goes through Story Spec Review and your
            approval before it replaces the current one; until then this Story&rsquo;s Tasks cannot start.
          </DialogDescription>
        </DialogHeader>

        <div className="flex flex-col gap-1">
          <label htmlFor="story-spec-revise-guidance" className="text-sm font-medium">
            Guidance <span className="text-destructive">*</span>
          </label>
          <Textarea
            id="story-spec-revise-guidance"
            data-testid="story-spec-revise-guidance"
            placeholder="What should change in the spec, and why?"
            value={guidance}
            onChange={(e) => setGuidance(e.target.value)}
            maxLength={MAX_GUIDANCE_LENGTH}
            rows={6}
            disabled={reviseStory.isPending}
          />
        </div>

        <DialogFooter>
          {reviseStory.isError && (
            <p data-testid="story-spec-revise-error" className="text-sm text-destructive mr-auto">
              {apiErrorMessage(reviseStory.error, "Failed to start the revision.")}
            </p>
          )}
          <Button variant="ghost" onClick={close}>
            Cancel
          </Button>
          <Button
            data-testid="story-spec-revise-submit"
            onClick={handleSubmit}
            disabled={!guidance.trim() || reviseStory.isPending}
          >
            {reviseStory.isPending ? "Starting..." : "Start revision"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
