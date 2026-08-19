import { Link, useNavigate } from "react-router";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Separator } from "@/components/ui/separator";
import { roadmapLevelMeta, ROADMAP_LEVEL_ORDER, type RoadmapLevel } from "@/lib/roadmapLevel";
import {
  carryViewToLevel,
  roadmapDestination,
  roadmapViewMeta,
  roadmapViewsForLevel,
} from "@/lib/roadmapNavigation";
import type { RoadmapFocus, RoadmapView } from "@/lib/roadmapFocus";
import { cn } from "@/lib/utils";

interface Props {
  /** Which ticket type this surface lists — the value the type dropdown shows as selected. */
  level: RoadmapLevel;
  /** Which view this surface is. `"graph"` marks the Graph action as current instead of a view. */
  view: RoadmapView;
  focusedEpicId?: string;
  focusedStoryId?: string;
}

const GROUP_CLASS =
  "inline-flex h-8 shrink-0 items-center gap-0.5 rounded-lg border border-border bg-background p-0.5 text-sm";

const VIEW_CLASS =
  "inline-flex h-7 shrink-0 items-center gap-1.5 rounded-md px-2.5 font-medium transition-colors outline-none select-none focus-visible:ring-3 focus-visible:ring-ring/50";

const ACTION_CLASS =
  "inline-flex h-8 shrink-0 items-center gap-1.5 rounded-lg border border-border bg-background px-2.5 text-sm font-medium text-muted-foreground transition-colors outline-none select-none hover:bg-muted hover:text-foreground focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50";

/** Explains why the Graph action is inert, on the control itself rather than only in docs. */
const GRAPH_DISABLED_HINT = "Focus an Epic or Story to open its graph";

/**
 * The one Roadmap header control: a ticket-type dropdown, the view types that exist for that
 * ticket type, and the contextual Graph action. It replaces the per-page hand-rolled navigation
 * links that had grown five different names for the same three destinations.
 *
 * Two axes, deliberately shaped differently. **Ticket type** (Epic/Story/Task) is a dropdown
 * because you change it rarely and it is the noun the rest of the header is about. **View**
 * (List/Board/Timeline) is a row of buttons because switching views is the frequent move and must
 * stay one click — a second dropdown would make the common action cost two.
 *
 * Only views that exist for the selected ticket type are rendered at all (`roadmapViewsForLevel`):
 * a disabled view button would be a button that never becomes clickable, which reads as broken
 * rather than as unavailable. **Graph** is the one exception, and it is not on the view axis: it
 * shows one Epic's whole Story/Task tree, so it needs a focused Epic rather than a ticket type.
 * That makes it a contextual action whose availability changes as you click around, which is
 * exactly the case a disabled control with an explanation is for.
 *
 * The "Ready to start" filter is not part of this control. It filters *what* is listed rather than
 * naming *where* you are, so each page supplies it as a sibling, separated from the navigation by
 * a rule — see the pages that render this.
 */
export default function RoadmapViewControls({
  level,
  view,
  focusedEpicId,
  focusedStoryId,
}: Props) {
  const navigate = useNavigate();
  const focus: RoadmapFocus = { epicId: focusedEpicId, storyId: focusedStoryId };

  const levelInfo = roadmapLevelMeta(level);
  const LevelIcon = levelInfo.Icon;
  const views = roadmapViewsForLevel(level);
  const graphMeta = roadmapViewMeta("graph");
  const GraphIcon = graphMeta.Icon;
  const graphUrl = roadmapDestination("graph", level, focus);

  function handleLevelChange(next: string | null) {
    // Base UI can emit null (see SortDropdown/PrioritySelect), and re-picking the current type is
    // a no-op rather than a navigation back to this page's own default view.
    if (!next || next === level) return;
    const nextLevel = next as RoadmapLevel;
    const url = roadmapDestination(carryViewToLevel(view, nextLevel), nextLevel, focus);
    if (url) navigate(url);
  }

  return (
    // `flex-wrap` here as well as on PageHeader's row: this control is a single child of that row,
    // so the wrapping it gained only lets this whole unit move to a line of its own. At 375px the
    // unit is itself wider than the viewport, and every part of it is `shrink-0`, so without its
    // own wrapping it just overhangs — invisibly, because the page column is `min-w-0` and clips
    // rather than scrolling. `gap-2` already supplies the row gap the wrapped lines need.
    <div className="flex flex-wrap items-center gap-2" data-testid="roadmap-view-controls">
      <Select value={level} onValueChange={handleLevelChange}>
        <SelectTrigger data-testid="roadmap-level-select" aria-label="Ticket type" className="w-auto">
          <SelectValue>
            <LevelIcon className={cn("size-3.5", levelInfo.textClass)} />
            {levelInfo.pluralLabel}
          </SelectValue>
        </SelectTrigger>
        <SelectContent>
          {ROADMAP_LEVEL_ORDER.map((option) => {
            const info = roadmapLevelMeta(option);
            const OptionIcon = info.Icon;
            return (
              <SelectItem key={option} value={option} data-testid={`roadmap-level-option-${option}`}>
                <OptionIcon className={cn("size-3.5", info.textClass)} />
                {info.pluralLabel}
              </SelectItem>
            );
          })}
        </SelectContent>
      </Select>

      <Separator orientation="vertical" className="h-6 w-px" />

      <div className={GROUP_CLASS} role="group" aria-label="View">
        {views.map((entry) => {
          const meta = roadmapViewMeta(entry);
          const Icon = meta.Icon;
          const testId = `roadmap-view-${entry}`;

          if (entry === view) {
            return (
              <span
                key={entry}
                data-testid={testId}
                aria-current="page"
                className={cn(VIEW_CLASS, "bg-primary text-primary-foreground")}
              >
                <Icon className="size-4" />
                {meta.label}
              </span>
            );
          }

          return (
            <Link
              key={entry}
              // Non-null by construction: `roadmapViewsForLevel` yields only views that resolve,
              // and whether one resolves depends on the ticket type alone, never on the focus.
              to={roadmapDestination(entry, level, focus)!}
              data-testid={testId}
              className={cn(VIEW_CLASS, "text-muted-foreground hover:text-foreground")}
            >
              <Icon className="size-4" />
              {meta.label}
            </Link>
          );
        })}
      </div>

      <Separator orientation="vertical" className="h-6 w-px" />

      {view === "graph" ? (
        <span
          data-testid="roadmap-graph-action"
          aria-current="page"
          className={cn(ACTION_CLASS, "border-transparent bg-primary text-primary-foreground")}
        >
          <GraphIcon className="size-4" />
          {graphMeta.label}
        </span>
      ) : graphUrl ? (
        <Link to={graphUrl} data-testid="roadmap-graph-action" className={ACTION_CLASS}>
          <GraphIcon className="size-4" />
          {graphMeta.label}
        </Link>
      ) : (
        <button
          type="button"
          disabled
          data-testid="roadmap-graph-action"
          title={GRAPH_DISABLED_HINT}
          className={cn(ACTION_CLASS, "cursor-not-allowed opacity-50")}
        >
          <GraphIcon className="size-4" />
          {graphMeta.label}
        </button>
      )}
    </div>
  );
}
