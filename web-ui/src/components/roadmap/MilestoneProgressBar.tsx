import type { MilestoneProgress } from "@/lib/types";
import { milestoneProgressSegments } from "@/lib/milestoneProgress";

interface MilestoneProgressBarProps {
  progress: MilestoneProgress;
  "data-testid"?: string;
}

/**
 * Stacked done / in-progress / not-started bar for a Milestone's descendant-Task rollup — the
 * Milestone-tier counterpart to the plain `doneTasks/totalTasks` text already shown for Epics/
 * Stories elsewhere. Reuses the same semantic status tokens `statusColorTokens` maps run statuses
 * onto (`status-success` for done, `status-info` for in-progress, `status-neutral` for not
 * started) so the bar reads consistently with the rest of the roadmap UI. Renders an all-empty
 * track when `progress.totalTasks === 0` — `milestoneProgressSegments` returns all-zero widths
 * rather than `NaN` for that case.
 */
export default function MilestoneProgressBar({
  progress,
  "data-testid": dataTestId,
}: MilestoneProgressBarProps) {
  const segments = milestoneProgressSegments(progress);
  return (
    <div
      data-testid={dataTestId}
      title={`${progress.doneTasks}/${progress.totalTasks} tasks done`}
      className="flex h-2 w-full min-w-16 overflow-hidden rounded-full bg-status-neutral/15"
    >
      <div className="h-full bg-status-success" style={{ width: `${segments.done}%` }} />
      <div className="h-full bg-status-info" style={{ width: `${segments.inProgress}%` }} />
      <div className="h-full bg-status-neutral" style={{ width: `${segments.notStarted}%` }} />
    </div>
  );
}
