import { useState } from "react";
import { Link } from "react-router";
import { formatDistanceToNow } from "date-fns";
import { useAllStories } from "@/hooks/useStories";
import { useRoadmapSubscription } from "@/hooks/useRoadmapSubscription";
import type { PaginationParams } from "@/lib/types";
import { Skeleton } from "@/components/ui/skeleton";
import TruncatedText from "@/components/ui/TruncatedText";
import Pagination from "@/components/ui/Pagination";
import PriorityBadge from "@/components/roadmap/PriorityBadge";
import ReadinessBadge from "@/components/roadmap/ReadinessBadge";
import StageBadge from "@/components/roadmap/StageBadge";
import RoadmapViewControls from "@/components/roadmap/RoadmapViewControls";
import PageHeader from "@/components/layout/PageHeader";

const PAGE_SIZE = 20;

/**
 * Story list — every Story in the org, the Story-level counterpart of `RoadmapPage`'s Epic list
 * and the flat reading of the same items the Story Board arranges by stage. Backed by the same
 * org-wide `GET /stories` listing the board already uses, so this page adds a view, not an
 * endpoint.
 *
 * There is no "Ready to start" filter here, unlike the Epic list: `useAllStories` has no readiness
 * parameter, and readiness is computed per Epic (`EpicService`'s per-Epic Story listing), so this
 * listing has nothing to filter on. `ReadinessBadge` is still rendered for the same reason
 * `StoryBoardCard` renders it — one row shape for Stories wherever they appear, and it stays
 * silent until the listing carries a value.
 */
export default function StoryListPage() {
  const [page, setPage] = useState(0);
  const pagination: PaginationParams = { page, size: PAGE_SIZE };
  const { data: pageData, isLoading } = useAllStories(undefined, pagination);
  const stories = pageData?.content;
  useRoadmapSubscription();

  return (
    <div className="flex h-full min-w-0 flex-col p-4 md:p-6">
      <PageHeader title="Stories" data-testid="story-list-heading">
        <RoadmapViewControls level="story" view="list" />
      </PageHeader>

      <div data-testid="story-list" className="mt-4 flex-1 overflow-y-auto">
        {isLoading &&
          Array.from({ length: 4 }).map((_, i) => (
            <div key={i} className="p-3 border-b">
              <Skeleton className="h-4 w-3/4 mb-2" />
              <Skeleton className="h-3 w-1/3" />
            </div>
          ))}
        {stories?.map((story) => (
          <div
            key={story.id}
            data-testid="story-item"
            className="flex items-center justify-between gap-4 p-3 border-b transition-colors hover:bg-muted/50"
          >
            <Link
              to={`/roadmap/epics/${story.epicId}/stories/${story.id}`}
              className="min-w-0 flex-1"
            >
              <TruncatedText as="div" className="font-medium text-sm">
                {story.title}
              </TruncatedText>
              <div className="mt-1 flex flex-wrap items-center gap-2">
                <StageBadge stage={story.stage} data-testid="story-item-stage" />
                <PriorityBadge
                  priority={story.priority}
                  size="compact"
                  data-testid="story-item-priority-badge"
                />
                <ReadinessBadge readiness={story.readiness} data-testid="story-item-readiness-badge" />
                <span data-testid="story-item-progress" className="text-xs text-muted-foreground">
                  {story.progress.doneTasks}/{story.progress.totalTasks} tasks done
                </span>
                <span className="text-xs text-muted-foreground">
                  {formatDistanceToNow(new Date(story.createdAt), { addSuffix: true })}
                </span>
              </div>
            </Link>
          </div>
        ))}
        {stories && stories.length === 0 && (
          <div data-testid="story-list-empty" className="p-6 text-center text-muted-foreground text-sm">
            No stories yet. Open an Epic to create one.
          </div>
        )}
      </div>

      {pageData && (
        <div className="border-t p-2">
          <Pagination page={pageData.number} totalPages={pageData.totalPages} onPageChange={setPage} />
        </div>
      )}
    </div>
  );
}
