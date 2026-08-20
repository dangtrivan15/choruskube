import type { MilestoneProgress } from "@/lib/types";

/** Percentage widths (0-100) for the three segments of a stacked Milestone progress bar. */
export interface MilestoneProgressSegments {
  done: number;
  inProgress: number;
  notStarted: number;
}

/**
 * Converts a Milestone's task-count `Progress` buckets into percentage widths for
 * `MilestoneProgressBar`. A Milestone with no descendant Tasks at all (`totalTasks === 0`)
 * returns all-zero segments rather than dividing by zero, which would otherwise produce `NaN`
 * and break the rendered `width` style.
 */
export function milestoneProgressSegments(progress: MilestoneProgress): MilestoneProgressSegments {
  const { totalTasks, doneTasks, inProgressTasks, notStartedTasks } = progress;
  if (totalTasks === 0) {
    return { done: 0, inProgress: 0, notStarted: 0 };
  }
  return {
    done: (doneTasks / totalTasks) * 100,
    inProgress: (inProgressTasks / totalTasks) * 100,
    notStarted: (notStartedTasks / totalTasks) * 100,
  };
}
