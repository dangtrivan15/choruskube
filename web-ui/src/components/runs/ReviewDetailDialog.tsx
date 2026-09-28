import { format } from "date-fns";
import { MessageSquareText } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
} from "@/components/ui/dialog";
import { cn } from "@/lib/utils";
import MarkdownViewer from "@/components/ui/MarkdownViewer";
import type { ReviewHistoryResponse } from "@/lib/types";
import { STATUS_TONE_CLASSES } from "@/lib/statusColors";

/**
 * Returns the badge recipe for a review decision.
 *
 * - approved  -> success
 * - rejected  -> warning (normal workflow event, matches awaiting_human palette)
 * - otherwise -> neutral (unknown / fallback)
 */
export function decisionBadgeClass(decision: string): string {
  switch (decision) {
    case "approved":
      return STATUS_TONE_CLASSES.success.badge;
    case "rejected":
      return STATUS_TONE_CLASSES.warning.badge;
    default:
      return STATUS_TONE_CLASSES.neutral.badge;
  }
}

interface ReviewDetailDialogProps {
  review: ReviewHistoryResponse | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

export default function ReviewDetailDialog({
  review,
  open,
  onOpenChange,
}: ReviewDetailDialogProps) {
  if (!review) return null;

  const nodeLabel = review.nodeLabel || review.reviewerType;
  const showDecision = review.decision && review.decision !== "no_decision";

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent size="lg">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <MessageSquareText className="h-4 w-4" />
            Review — Iteration {review.iteration}
          </DialogTitle>
          <DialogDescription>
            <span className="flex flex-wrap items-center gap-2">
              <Badge className={STATUS_TONE_CLASSES.neutral.badge}>
                {nodeLabel}
              </Badge>
              {showDecision && (
                <Badge className={cn(decisionBadgeClass(review.decision))}>
                  {review.decision}
                </Badge>
              )}
              <span>
                {format(new Date(review.timestamp), "MMM d, yyyy 'at' HH:mm")}
              </span>
            </span>
          </DialogDescription>
        </DialogHeader>

        <div className="min-h-0 flex-1 overflow-auto">
          {review.result ? (
            <MarkdownViewer content={review.result} maxHeight="max-h-[60vh]" />
          ) : (
            <p className="py-4 text-center text-sm text-muted-foreground">
              No content available for this review.
            </p>
          )}
        </div>
      </DialogContent>
    </Dialog>
  );
}
