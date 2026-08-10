import { Link } from "react-router";
import { Network, LayoutGrid, GanttChart, type LucideIcon } from "lucide-react";
import { buildFocusedUrl, type RoadmapFocus, type RoadmapView } from "@/lib/roadmapFocus";
import { cn } from "@/lib/utils";

interface Props {
  activeView: RoadmapView;
  focusedEpicId?: string;
  focusedStoryId?: string;
}

const ENTRIES: { view: RoadmapView; label: string; icon: LucideIcon; testId: string }[] = [
  { view: "graph", label: "Graph", icon: Network, testId: "roadmap-view-switcher-graph" },
  { view: "board", label: "Board", icon: LayoutGrid, testId: "roadmap-view-switcher-board" },
  { view: "timeline", label: "Timeline", icon: GanttChart, testId: "roadmap-view-switcher-timeline" },
];

const ENTRY_CLASS =
  "inline-flex h-8 items-center gap-1.5 rounded-lg border border-transparent px-2.5 text-sm font-medium text-muted-foreground hover:bg-muted hover:text-foreground";

/**
 * Shared Graph / Board / Timeline switcher (Decision 2), rendered in all three Roadmap views'
 * headers alongside — not replacing — each page's own navigation links. Every entry's target URL
 * is computed from `buildFocusedUrl`, so all three pages agree byte-for-byte on how a focus id
 * becomes a target URL instead of each hand-rolling its own translation.
 *
 * `activeView`'s own entry renders as a non-link current-state indicator rather than a link back to
 * the page you're already on (Decision 2's "byte-for-byte agreement" doesn't extend to linking to
 * yourself). The Graph entry renders as a disabled button with an explanatory tooltip whenever
 * `buildFocusedUrl` returns `null` for it — i.e. nothing is focused yet (Decision 3) — becoming a
 * live link the instant an Epic or Story is focused.
 */
export default function RoadmapViewSwitcher({ activeView, focusedEpicId, focusedStoryId }: Props) {
  const focus: RoadmapFocus = { epicId: focusedEpicId, storyId: focusedStoryId };

  return (
    <div className="flex items-center gap-1" data-testid="roadmap-view-switcher">
      {ENTRIES.map(({ view, label, icon: Icon, testId }) => {
        if (view === activeView) {
          return (
            <span
              key={view}
              data-testid={testId}
              aria-current="page"
              className={cn(ENTRY_CLASS, "bg-muted text-foreground hover:bg-muted")}
            >
              <Icon className="size-4" />
              {label}
            </span>
          );
        }

        const url = buildFocusedUrl(view, focus);
        if (!url) {
          return (
            <button
              key={view}
              type="button"
              disabled
              data-testid={testId}
              title="Focus an Epic or Story to open its graph"
              className={cn(ENTRY_CLASS, "cursor-not-allowed opacity-50")}
            >
              <Icon className="size-4" />
              {label}
            </button>
          );
        }

        return (
          <Link key={view} to={url} data-testid={testId} className={ENTRY_CLASS}>
            <Icon className="size-4" />
            {label}
          </Link>
        );
      })}
    </div>
  );
}
