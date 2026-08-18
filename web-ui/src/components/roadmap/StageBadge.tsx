import { Badge } from "@/components/ui/badge";
import type { EpicStage } from "@/lib/types";

const LABELS: Record<EpicStage, string> = {
  backlog: "backlog",
  in_progress: "in progress",
  rolled_out: "rolled out",
};

interface StageBadgeProps {
  /**
   * Typed loosely (`string`) rather than `EpicStage` for the same reason as `PriorityBadge`:
   * display-only call sites pass a possibly-stale value straight from cached data, and a stage
   * this build predates (the column is a Postgres enum extended via `ALTER TYPE`) must render
   * as itself rather than crash.
   */
  stage: string;
  className?: string;
  "data-testid"?: string;
}

/**
 * The board lane an Epic or Story sits in — and the only thing the API says about where it sits.
 *
 * There is deliberately no companion "status" badge derived from Task counts. An Epic/Story has
 * three lanes and no `done` one, so a derived "done" would name a state the item cannot be in and
 * would contradict this badge on the same screen. Completion is shown beside it as the task
 * counts, which are facts: an item can read "backlog" here and "4 of 4 tasks done" next to it,
 * because that is precisely the state it is in — finished, not yet shipped.
 */
export default function StageBadge({ stage, className, "data-testid": dataTestId }: StageBadgeProps) {
  const label = LABELS[stage as EpicStage];
  if (!label) {
    return (
      <Badge variant="outline" className={className} data-testid={dataTestId}>
        {stage}
      </Badge>
    );
  }
  return (
    <Badge
      variant={stage === "rolled_out" ? "default" : stage === "in_progress" ? "secondary" : "outline"}
      className={className}
      data-testid={dataTestId}
    >
      {label}
    </Badge>
  );
}
