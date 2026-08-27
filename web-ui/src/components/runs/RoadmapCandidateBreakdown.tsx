import { Plus, Trash2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { Card, CardContent, CardHeader } from "@/components/ui/card";
import { Separator } from "@/components/ui/separator";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import PriorityBadge from "@/components/roadmap/PriorityBadge";
import PrioritySelect from "@/components/roadmap/PrioritySelect";
import type {
  CandidateDependency,
  CandidateEpicProposal,
  CandidateMilestone,
  CandidateStoryProposal,
  CandidateTaskProposal,
  Priority,
  RoadmapCandidatesDocument,
} from "@/lib/types";

// Mirrors the server-side cap (max 8 Epics per breakdown, max 8 Stories per
// Epic, max 8 Tasks per Story). These are soft UI limits only — the server is
// the source of truth and will reject an over-cap submission regardless of
// what the client allows. Unlike Stories/Tasks (added one at a time via an
// "Add" button that we can disable), Epics originate entirely from the
// analyzer, so there's no "Add Epic" affordance to gate — instead we warn the
// reviewer up front so an over-cap Approve doesn't fail with a generic error
// and no indication of why.
const MAX_EPICS = 8;
const MAX_STORIES_PER_EPIC = 8;
const MAX_TASKS_PER_STORY = 8;

/** Sentinel value for the "No milestone" option — Base UI `Select` items need a non-empty
 * string value, so an unset/absent milestone reference is represented as this string and
 * translated back to `null` in `onChange`. Never collides with a real candidate `key`, since
 * the analyzer/reviewer never author a key equal to this literal. */
const MILESTONE_NONE_VALUE = "__none__";

interface RoadmapCandidateBreakdownProps {
  value: RoadmapCandidatesDocument;
  onChange: (next: RoadmapCandidatesDocument) => void;
}

function emptyTask(): CandidateTaskProposal {
  return { title: "", description: "" };
}

function emptyStory(): CandidateStoryProposal {
  return { title: "", description: "", tasks: [] };
}

/**
 * Normalizes a candidate item's free-text `priority` (`"High"`/`"Medium"`/`"Low"`, possibly
 * blank/unrecognized) down to the lowercase `Priority` union `PrioritySelect`/`PriorityBadge`
 * expect — mirroring the server's own case-insensitive parse-with-medium-default
 * (`DefaultRoadmapCandidateMaterializer.parsePriority`), so the editor shows the same value
 * that will be persisted on approve.
 */
function normalizePriority(value: string | null | undefined): Priority {
  const lower = value?.trim().toLowerCase();
  if (lower === "high" || lower === "medium" || lower === "low") return lower;
  return "medium";
}

/**
 * Epic-level "which Milestone does this Epic belong to" picker, scoped to the candidate
 * document's own `milestones` list (candidate items reference each other by
 * artifact-local `key`, not a persisted id — there is nothing to fetch). Only milestones that
 * carry a `key` are selectable, since an unkeyed milestone has nothing an Epic could reference.
 */
function CandidateMilestoneAssignmentSelect({
  milestones,
  value,
  onChange,
  testId,
}: {
  milestones: CandidateMilestone[];
  value: string | null | undefined;
  onChange: (key: string | null) => void;
  testId: string;
}) {
  const selectable = milestones.filter((m): m is CandidateMilestone & { key: string } => !!m.key);
  const selected = selectable.find((m) => m.key === value);

  return (
    <Select
      value={value ?? MILESTONE_NONE_VALUE}
      onValueChange={(v) => onChange(v && v !== MILESTONE_NONE_VALUE ? v : null)}
    >
      <SelectTrigger data-testid={testId} aria-label="Milestone" size="sm" className="w-auto">
        <SelectValue placeholder="No milestone">{selected ? selected.name : "No milestone"}</SelectValue>
      </SelectTrigger>
      <SelectContent>
        <SelectItem value={MILESTONE_NONE_VALUE} data-testid={`${testId}-none`}>
          No milestone
        </SelectItem>
        {selectable.map((m) => (
          <SelectItem key={m.key} value={m.key} data-testid={`${testId}-${m.key}`}>
            {m.name}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  );
}

export default function RoadmapCandidateBreakdown({ value, onChange }: RoadmapCandidateBreakdownProps) {
  const { milestones, epics, dependencies } = value;

  function updateEpic(epicIdx: number, patch: Partial<CandidateEpicProposal>) {
    onChange({ ...value, epics: epics.map((epic, i) => (i === epicIdx ? { ...epic, ...patch } : epic)) });
  }

  function removeEpic(epicIdx: number) {
    onChange({ ...value, epics: epics.filter((_, i) => i !== epicIdx) });
  }

  function addStory(epicIdx: number) {
    const epic = epics[epicIdx];
    if (epic.stories.length >= MAX_STORIES_PER_EPIC) return;
    updateEpic(epicIdx, { stories: [...epic.stories, emptyStory()] });
  }

  function removeStory(epicIdx: number, storyIdx: number) {
    const epic = epics[epicIdx];
    updateEpic(epicIdx, { stories: epic.stories.filter((_, i) => i !== storyIdx) });
  }

  function updateStory(epicIdx: number, storyIdx: number, patch: Partial<CandidateStoryProposal>) {
    const epic = epics[epicIdx];
    updateEpic(epicIdx, {
      stories: epic.stories.map((story, i) => (i === storyIdx ? { ...story, ...patch } : story)),
    });
  }

  function addTask(epicIdx: number, storyIdx: number) {
    const story = epics[epicIdx].stories[storyIdx];
    if (story.tasks.length >= MAX_TASKS_PER_STORY) return;
    updateStory(epicIdx, storyIdx, { tasks: [...story.tasks, emptyTask()] });
  }

  function removeTask(epicIdx: number, storyIdx: number, taskIdx: number) {
    const story = epics[epicIdx].stories[storyIdx];
    updateStory(epicIdx, storyIdx, { tasks: story.tasks.filter((_, i) => i !== taskIdx) });
  }

  function updateTask(epicIdx: number, storyIdx: number, taskIdx: number, patch: Partial<CandidateTaskProposal>) {
    const story = epics[epicIdx].stories[storyIdx];
    updateStory(epicIdx, storyIdx, {
      tasks: story.tasks.map((task, i) => (i === taskIdx ? { ...task, ...patch } : task)),
    });
  }

  if (epics.length === 0 && milestones.length === 0 && dependencies.length === 0) return null;

  return (
    <div data-testid="roadmap-candidate-breakdown" className="space-y-3">
      <h4 className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
        Proposed Roadmap Breakdown ({epics.length})
      </h4>
      {epics.length > MAX_EPICS && (
        <p
          data-testid="candidate-epic-cap-warning"
          className="rounded-md border border-destructive/50 bg-destructive/10 p-2 text-xs text-destructive"
        >
          {epics.length} Epics proposed, but only {MAX_EPICS} are allowed per breakdown. Remove{" "}
          {epics.length - MAX_EPICS} before approving, or the submission will be rejected.
        </p>
      )}

      {milestones.length > 0 && (
        <div data-testid="candidate-milestones" className="space-y-2">
          <h5 className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
            Milestones ({milestones.length})
          </h5>
          <div className="space-y-1.5">
            {milestones.map((milestone, i) => (
              <div
                key={milestone.key ?? i}
                data-testid={`candidate-milestone-${i}`}
                className="rounded-md bg-muted/50 p-2 text-xs"
              >
                <span className="font-medium">{milestone.name}</span>
                {milestone.targetDate && (
                  <span className="ml-2 text-muted-foreground">Target: {milestone.targetDate}</span>
                )}
                {milestone.description && (
                  <p className="mt-1 text-muted-foreground">{milestone.description}</p>
                )}
              </div>
            ))}
          </div>
        </div>
      )}

      {epics.map((epic, epicIdx) => (
        <Card key={epicIdx} data-testid={`candidate-epic-${epicIdx}`}>
          <CardHeader className="flex-row items-start justify-between gap-2">
            <div className="flex-1 space-y-2">
              <label
                htmlFor={`candidate-epic-title-${epicIdx}`}
                className="text-xs font-medium uppercase tracking-wide text-muted-foreground"
              >
                Epic Title
              </label>
              <Input
                id={`candidate-epic-title-${epicIdx}`}
                data-testid={`candidate-epic-title-${epicIdx}`}
                value={epic.title}
                onChange={(e) => updateEpic(epicIdx, { title: e.target.value })}
              />
            </div>
            <Button
              type="button"
              variant="destructive"
              size="icon-sm"
              data-testid={`candidate-epic-remove-${epicIdx}`}
              aria-label="Remove epic"
              onClick={() => removeEpic(epicIdx)}
            >
              <Trash2 className="h-4 w-4" />
            </Button>
          </CardHeader>
          <CardContent className="space-y-3">
            <div className="space-y-1.5">
              <label
                htmlFor={`candidate-epic-description-${epicIdx}`}
                className="text-xs font-medium uppercase tracking-wide text-muted-foreground"
              >
                Description
              </label>
              <Textarea
                id={`candidate-epic-description-${epicIdx}`}
                data-testid={`candidate-epic-description-${epicIdx}`}
                value={epic.description}
                onChange={(e) => updateEpic(epicIdx, { description: e.target.value })}
              />
            </div>

            <div className="space-y-1.5">
              <label
                htmlFor={`candidate-epic-motivation-${epicIdx}`}
                className="text-xs font-medium uppercase tracking-wide text-muted-foreground"
              >
                Motivation
              </label>
              <Textarea
                id={`candidate-epic-motivation-${epicIdx}`}
                data-testid={`candidate-epic-motivation-${epicIdx}`}
                value={epic.motivation}
                onChange={(e) => updateEpic(epicIdx, { motivation: e.target.value })}
              />
            </div>

            <div className="flex flex-wrap items-center gap-4">
              <div className="flex items-center gap-2">
                <span className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
                  Priority
                </span>
                <PriorityBadge
                  priority={normalizePriority(epic.priority)}
                  size="compact"
                  data-testid={`candidate-epic-priority-badge-${epicIdx}`}
                />
                <PrioritySelect
                  value={normalizePriority(epic.priority)}
                  size="sm"
                  onChange={(p) => updateEpic(epicIdx, { priority: p })}
                  testId={`candidate-epic-priority-select-${epicIdx}`}
                />
              </div>
              <div className="flex items-center gap-2">
                <span className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
                  Milestone
                </span>
                <CandidateMilestoneAssignmentSelect
                  milestones={milestones}
                  value={epic.milestone}
                  onChange={(key) => updateEpic(epicIdx, { milestone: key })}
                  testId={`candidate-epic-milestone-select-${epicIdx}`}
                />
              </div>
            </div>

            {epic.repos && epic.repos.length > 0 && (
              <div
                data-testid={`candidate-epic-context-${epicIdx}`}
                className="space-y-1 rounded-md bg-muted/50 p-2 text-xs text-muted-foreground"
              >
                <p>Likely touches: {epic.repos.join(", ")}</p>
              </div>
            )}

            <Separator />

            <div className="space-y-3">
              <div className="flex items-center justify-between">
                <h5 className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
                  Stories ({epic.stories.length})
                </h5>
                <Button
                  type="button"
                  variant="outline"
                  size="xs"
                  data-testid={`candidate-add-story-${epicIdx}`}
                  onClick={() => addStory(epicIdx)}
                  disabled={epic.stories.length >= MAX_STORIES_PER_EPIC}
                >
                  <Plus className="h-3 w-3" />
                  Add Story
                </Button>
              </div>

              {epic.stories.map((story, storyIdx) => (
                <div
                  key={storyIdx}
                  data-testid={`candidate-story-${epicIdx}-${storyIdx}`}
                  className="space-y-2 rounded-md border p-3"
                >
                  <div className="flex items-start justify-between gap-2">
                    <div className="flex-1 space-y-1.5">
                      <label
                        htmlFor={`candidate-story-title-${epicIdx}-${storyIdx}`}
                        className="text-xs font-medium uppercase tracking-wide text-muted-foreground"
                      >
                        Story Title
                      </label>
                      <Input
                        id={`candidate-story-title-${epicIdx}-${storyIdx}`}
                        data-testid={`candidate-story-title-${epicIdx}-${storyIdx}`}
                        value={story.title}
                        onChange={(e) => updateStory(epicIdx, storyIdx, { title: e.target.value })}
                      />
                    </div>
                    <Button
                      type="button"
                      variant="ghost"
                      size="icon-sm"
                      data-testid={`candidate-story-remove-${epicIdx}-${storyIdx}`}
                      aria-label="Remove story"
                      onClick={() => removeStory(epicIdx, storyIdx)}
                    >
                      <Trash2 className="h-4 w-4" />
                    </Button>
                  </div>

                  <div className="space-y-1.5">
                    <label
                      htmlFor={`candidate-story-description-${epicIdx}-${storyIdx}`}
                      className="text-xs font-medium uppercase tracking-wide text-muted-foreground"
                    >
                      Description
                    </label>
                    <Textarea
                      id={`candidate-story-description-${epicIdx}-${storyIdx}`}
                      data-testid={`candidate-story-description-${epicIdx}-${storyIdx}`}
                      value={story.description}
                      onChange={(e) => updateStory(epicIdx, storyIdx, { description: e.target.value })}
                    />
                  </div>

                  <div className="flex items-center gap-2">
                    <span className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
                      Priority
                    </span>
                    <PriorityBadge
                      priority={normalizePriority(story.priority)}
                      size="compact"
                      data-testid={`candidate-story-priority-badge-${epicIdx}-${storyIdx}`}
                    />
                    <PrioritySelect
                      value={normalizePriority(story.priority)}
                      size="sm"
                      onChange={(p) => updateStory(epicIdx, storyIdx, { priority: p })}
                      testId={`candidate-story-priority-select-${epicIdx}-${storyIdx}`}
                    />
                  </div>

                  <div className="space-y-2 pl-3">
                    <div className="flex items-center justify-between">
                      <h6 className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
                        Tasks ({story.tasks.length})
                      </h6>
                      <Button
                        type="button"
                        variant="outline"
                        size="xs"
                        data-testid={`candidate-add-task-${epicIdx}-${storyIdx}`}
                        onClick={() => addTask(epicIdx, storyIdx)}
                        disabled={story.tasks.length >= MAX_TASKS_PER_STORY}
                      >
                        <Plus className="h-3 w-3" />
                        Add Task
                      </Button>
                    </div>

                    {story.tasks.map((task, taskIdx) => (
                      <div
                        key={taskIdx}
                        data-testid={`candidate-task-${epicIdx}-${storyIdx}-${taskIdx}`}
                        className="space-y-2 rounded-md border p-2"
                      >
                        <div className="flex items-start justify-between gap-2">
                          <div className="flex-1 space-y-1.5">
                            <label
                              htmlFor={`candidate-task-title-${epicIdx}-${storyIdx}-${taskIdx}`}
                              className="text-xs font-medium uppercase tracking-wide text-muted-foreground"
                            >
                              Task Title
                            </label>
                            <Input
                              id={`candidate-task-title-${epicIdx}-${storyIdx}-${taskIdx}`}
                              data-testid={`candidate-task-title-${epicIdx}-${storyIdx}-${taskIdx}`}
                              value={task.title}
                              onChange={(e) =>
                                updateTask(epicIdx, storyIdx, taskIdx, { title: e.target.value })
                              }
                            />
                          </div>
                          <Button
                            type="button"
                            variant="ghost"
                            size="icon-sm"
                            data-testid={`candidate-task-remove-${epicIdx}-${storyIdx}-${taskIdx}`}
                            aria-label="Remove task"
                            onClick={() => removeTask(epicIdx, storyIdx, taskIdx)}
                          >
                            <Trash2 className="h-4 w-4" />
                          </Button>
                        </div>
                        <div className="space-y-1.5">
                          <label
                            htmlFor={`candidate-task-description-${epicIdx}-${storyIdx}-${taskIdx}`}
                            className="text-xs font-medium uppercase tracking-wide text-muted-foreground"
                          >
                            Description
                          </label>
                          <Textarea
                            id={`candidate-task-description-${epicIdx}-${storyIdx}-${taskIdx}`}
                            data-testid={`candidate-task-description-${epicIdx}-${storyIdx}-${taskIdx}`}
                            value={task.description}
                            onChange={(e) =>
                              updateTask(epicIdx, storyIdx, taskIdx, { description: e.target.value })
                            }
                          />
                        </div>
                        <div className="flex items-center gap-2">
                          <span className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
                            Priority
                          </span>
                          <PriorityBadge
                            priority={normalizePriority(task.priority)}
                            size="compact"
                            data-testid={`candidate-task-priority-badge-${epicIdx}-${storyIdx}-${taskIdx}`}
                          />
                          <PrioritySelect
                            value={normalizePriority(task.priority)}
                            size="sm"
                            onChange={(p) => updateTask(epicIdx, storyIdx, taskIdx, { priority: p })}
                            testId={`candidate-task-priority-select-${epicIdx}-${storyIdx}-${taskIdx}`}
                          />
                        </div>
                      </div>
                    ))}
                  </div>
                </div>
              ))}
            </div>
          </CardContent>
        </Card>
      ))}

      {dependencies.length > 0 && (
        <div data-testid="candidate-dependencies" className="space-y-2">
          <h5 className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
            Dependencies ({dependencies.length})
          </h5>
          {/* Read-only  — adding/removing edges happens post-materialization in the
              roadmap graph UI, not in this pre-approval editor. */}
          <ul className="space-y-1">
            {dependencies.map((dependency: CandidateDependency, i) => (
              <li
                key={i}
                data-testid={`candidate-dependency-${i}`}
                className="rounded-md bg-muted/50 p-2 text-xs text-muted-foreground"
              >
                <span className="font-medium text-foreground">{dependency.blocking}</span> blocks{" "}
                <span className="font-medium text-foreground">{dependency.blocked}</span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}
