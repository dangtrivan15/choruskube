import { Rocket } from "lucide-react";
import Authorized from "@/components/Authorized";
import { Button } from "@/components/ui/button";
import type { EpicStage, WorkItemProgress } from "@/lib/types";

/**
 * Whether an item's Tasks are all finished while its stage still says otherwise — the one state
 * that used to be invisible: the detail page said "done" (a derived word), the board said
 * "Backlog" (the stored lane), and neither offered the move that would reconcile them.
 */
export function readyToRollOut(stage: string, progress: WorkItemProgress): boolean {
  return stage !== "rolled_out" && progress.totalTasks > 0 && progress.doneTasks === progress.totalTasks;
}

interface RollOutPromptProps {
  stage: EpicStage;
  progress: WorkItemProgress;
  pending?: boolean;
  onRollOut: () => void;
  testId?: string;
}

/**
 * Prompts the deliberate "this shipped" move, and nothing more.
 *
 * Stage is human-owned by design — `rolled_out` means shipped, which a Task rollup cannot know
 * (every Task can be code-complete with the change still undeployed). So this is a prompt rather
 * than an automation: it surfaces exactly when the counts say the work is finished and the stage
 * still says it is not, and it asks. Renders nothing otherwise.
 */
export default function RollOutPrompt({ stage, progress, pending, onRollOut, testId }: RollOutPromptProps) {
  if (!readyToRollOut(stage, progress)) return null;

  return (
    <Authorized require="canOperate">
      <div className="flex flex-wrap items-center gap-2" data-testid={testId ?? "roll-out-prompt"}>
        <span className="text-sm text-muted-foreground">
          All {progress.totalTasks} {progress.totalTasks === 1 ? "task is" : "tasks are"} done — not yet rolled out.
        </span>
        <Button
          data-testid={`${testId ?? "roll-out-prompt"}-button`}
          size="sm"
          disabled={pending}
          onClick={onRollOut}
        >
          <Rocket className="size-4" />
          Roll out
        </Button>
      </div>
    </Authorized>
  );
}
