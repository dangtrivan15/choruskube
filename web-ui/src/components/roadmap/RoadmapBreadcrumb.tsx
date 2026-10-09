import { Link } from "react-router";
import { ChevronRight } from "lucide-react";
import TruncatedText from "@/components/ui/TruncatedText";
import { roadmapLevelMeta } from "@/lib/roadmapLevel";
import type { RunTaskSummary } from "@/lib/types";
import { cn } from "@/lib/utils";

type BreadcrumbTask = Pick<
  RunTaskSummary,
  "id" | "title" | "epicId" | "epicTitle" | "storyId" | "storyTitle"
>;

interface RoadmapBreadcrumbProps {
  task: BreadcrumbTask;
  variant: "inline" | "stacked";
  className?: string;
}

interface Segment {
  level: "epic" | "story" | "task";
  testId: string;
  href: string;
  title: string;
}

function buildSegments(task: BreadcrumbTask): Segment[] {
  const segments: Segment[] = [];
  if (task.epicId) {
    segments.push({
      level: "epic",
      testId: "roadmap-breadcrumb-epic",
      href: `/roadmap/epics/${task.epicId}`,
      title: task.epicTitle ?? "",
    });
    if (task.storyId) {
      segments.push({
        level: "story",
        testId: "roadmap-breadcrumb-story",
        href: `/roadmap/epics/${task.epicId}/stories/${task.storyId}`,
        title: task.storyTitle ?? "",
      });
    }
  }
  segments.push({
    level: "task",
    testId: "roadmap-breadcrumb-task",
    href: `/tasks/${task.id}`,
    title: task.title,
  });
  return segments;
}

/**
 * Epic › Story › Task chain, shared by the run summary strip/sheet and (via its
 * stacked variant) anywhere the full chain needs to wrap instead of truncate.
 * Missing levels degrade gracefully: no story renders Epic › Task, no epic
 * renders Task alone.
 */
export default function RoadmapBreadcrumb({ task, variant, className }: RoadmapBreadcrumbProps) {
  const segments = buildSegments(task);

  if (variant === "stacked") {
    return (
      <dl
        aria-label="Roadmap"
        data-testid="roadmap-breadcrumb"
        data-variant="stacked"
        className={cn("space-y-2", className)}
      >
        {segments.map((segment) => {
          const meta = roadmapLevelMeta(segment.level);
          return (
            // `dt`/`dd` must be direct children of this wrapper for the term/value pairing to hold.
            <div key={segment.level} className="min-w-0">
              <dt className="flex items-center gap-1.5 text-xs font-medium uppercase tracking-wide text-muted-foreground">
                <meta.Icon className={cn("size-3.5 shrink-0", meta.textClass)} aria-hidden="true" />
                {meta.label}
              </dt>
              <dd className="mt-0.5">
                <Link
                  to={segment.href}
                  data-testid={segment.testId}
                  className="break-words font-medium text-primary hover:underline"
                >
                  {segment.title}
                </Link>
              </dd>
            </div>
          );
        })}
      </dl>
    );
  }

  return (
    <nav aria-label="Roadmap" data-testid="roadmap-breadcrumb" data-variant="inline" className={className}>
      <ol className="flex min-w-0 items-center gap-1">
        {segments.map((segment, idx) => {
          const meta = roadmapLevelMeta(segment.level);
          const isTask = segment.level === "task";
          return (
            <li
              key={segment.level}
              className={cn(
                "flex min-w-0 items-center gap-1",
                isTask ? "shrink-[0.25]" : "max-w-48 shrink",
              )}
            >
              {idx > 0 && (
                <ChevronRight className="size-3 shrink-0 text-muted-foreground" aria-hidden="true" />
              )}
              <meta.Icon className={cn("size-3.5 shrink-0", meta.textClass)} aria-hidden="true" />
              <TruncatedText
                render={<Link to={segment.href} data-testid={segment.testId} />}
                tooltip={`${meta.label} · ${segment.title}`}
                className="min-w-0 font-medium text-primary hover:underline"
              >
                {segment.title}
              </TruncatedText>
            </li>
          );
        })}
      </ol>
    </nav>
  );
}
