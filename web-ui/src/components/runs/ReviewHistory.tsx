import { useState } from "react";
import { format } from "date-fns";
import { ChevronDown, ChevronRight, Loader2 } from "lucide-react";
import { useReviewHistory } from "@/hooks/useRuns";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";
import ReviewDetailDialog, { decisionBadgeClass } from "./ReviewDetailDialog";
import type { ReviewHistoryResponse } from "@/lib/types";

/** Character threshold above which we show a "View full review" button. */
const CONTENT_TRUNCATION_THRESHOLD = 150;

/**
 * Extract displayable content from a review entry.
 * After V10, feedback was folded into `result`, so that's our content source.
 */
function getReviewContent(review: ReviewHistoryResponse): string | null {
  return review.result || null;
}

/**
 * Short badge label: use nodeLabel from the API, fall back to reviewerType.
 * Decision is a routing signal — shown separately as a colored badge.
 */
function getNodeLabel(review: ReviewHistoryResponse): string {
  return review.nodeLabel || review.reviewerType;
}

/** Whether the decision is meaningful to display (not null/empty/no_decision). */
function hasDisplayableDecision(review: ReviewHistoryResponse): boolean {
  return !!review.decision && review.decision !== "no_decision";
}

interface ReviewHistoryProps {
  runId: string;
  loopGroup: string | null;
}

export default function ReviewHistory({ runId, loopGroup }: ReviewHistoryProps) {
  const [isExpanded, setIsExpanded] = useState(true);
  const [selectedReview, setSelectedReview] =
    useState<ReviewHistoryResponse | null>(null);
  const { data: history, isLoading } = useReviewHistory(runId, loopGroup);

  if (!loopGroup) {
    return null;
  }

  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-4 text-muted-foreground">
        <Loader2 className="mr-2 h-4 w-4 animate-spin" />
        Loading review history...
      </div>
    );
  }

  if (!history || history.length === 0) {
    return (
      <p className="py-2 text-sm text-muted-foreground">
        No prior reviews for this loop group.
      </p>
    );
  }

  return (
    <div className="space-y-1">
      <button
        type="button"
        onClick={() => setIsExpanded(!isExpanded)}
        className="flex w-full items-center gap-1.5 text-sm font-medium text-foreground hover:text-foreground/80"
      >
        {isExpanded ? (
          <ChevronDown className="h-4 w-4" />
        ) : (
          <ChevronRight className="h-4 w-4" />
        )}
        Review History ({history.length})
      </button>

      {isExpanded && (
        <div className="relative ml-2 overflow-x-hidden border-l pl-4 pt-1">
          {history.map((review) => {
            const nodeLabel = getNodeLabel(review);
            const content = getReviewContent(review);
            const showDecision = hasDisplayableDecision(review);

            return (
              <div key={review.id} className="relative mb-3 last:mb-0">
                {/* Timeline dot — colored by status */}
                <div
                  className={cn(
                    "absolute -left-[21px] top-1.5 h-2 w-2 rounded-full border-2 border-background",
                    review.status === "completed"
                      ? "bg-status-success"
                      : review.status === "failed"
                        ? "bg-status-error"
                        : "bg-muted-foreground"
                  )}
                />

                <div className="space-y-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-xs font-medium text-muted-foreground">
                      Iteration {review.iteration}
                    </span>
                    {/* Node label badge (always gray for identification) */}
                    <Badge className="bg-status-neutral/15 text-status-neutral">
                      {nodeLabel}
                    </Badge>
                    {/* Decision badge (colored — only when meaningful) */}
                    {showDecision && (
                      <Badge className={cn(decisionBadgeClass(review.decision))}>
                        {review.decision}
                      </Badge>
                    )}
                    <span className="text-xs text-muted-foreground">
                      {format(new Date(review.timestamp), "MMM d, HH:mm")}
                    </span>
                  </div>

                  {/* Content preview */}
                  {content && (
                    <p className="line-clamp-3 break-words text-sm text-muted-foreground">
                      {content}
                    </p>
                  )}
                  {content && content.length > CONTENT_TRUNCATION_THRESHOLD && (
                    <button
                      type="button"
                      onClick={() => setSelectedReview(review)}
                      className="text-xs font-medium text-primary hover:text-primary/80"
                    >
                      View full review
                    </button>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      )}

      <ReviewDetailDialog
        review={selectedReview}
        open={selectedReview !== null}
        onOpenChange={(open) => {
          if (!open) setSelectedReview(null);
        }}
      />
    </div>
  );
}
