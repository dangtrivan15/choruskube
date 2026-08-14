import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { useMilestones } from "@/hooks/useMilestones";

/** Sentinel value for the "None" option — Base UI `Select` items need a non-empty string value,
 * so `null` (unassigned) is represented as this string and translated back to `null` in
 * `onChange`. Never collides with a real Milestone id (a UUID). */
const NONE_VALUE = "__none__";

interface MilestoneSelectProps {
  /** The currently assigned Milestone id, or `null` for unassigned. */
  value: string | null;
  onChange: (milestoneId: string | null) => void;
  /** Scopes the option list to Milestones belonging to this software project (Decision 3: a
   * Milestone and its Epics must share a project). */
  softwareProjectId: string | undefined;
  size?: "sm" | "default";
  disabled?: boolean;
  testId?: string;
}

/**
 * Project-scoped Milestone picker, including a "None" option to clear the assignment — the
 * Milestone equivalent of `PrioritySelect`/`SoftwareProjectSelect`. Used on the create/edit-Epic
 * dialogs and the inline Epic detail page selector (Decision 4).
 */
export default function MilestoneSelect({
  value,
  onChange,
  softwareProjectId,
  size = "default",
  disabled,
  testId,
}: MilestoneSelectProps) {
  const { data } = useMilestones(softwareProjectId);
  const milestones = data?.content ?? [];
  const selected = milestones.find((m) => m.id === value);

  return (
    <Select
      value={value ?? NONE_VALUE}
      onValueChange={(v) => onChange(v && v !== NONE_VALUE ? v : null)}
      disabled={disabled || !softwareProjectId}
    >
      <SelectTrigger
        data-testid={testId ?? "milestone-select"}
        aria-label="Milestone"
        size={size}
        className="w-full"
      >
        <SelectValue placeholder="None">{selected ? selected.name : "None"}</SelectValue>
      </SelectTrigger>
      <SelectContent>
        <SelectItem value={NONE_VALUE} data-testid="milestone-option-none">
          None
        </SelectItem>
        {milestones.map((m) => (
          <SelectItem key={m.id} value={m.id} data-testid={`milestone-option-${m.id}`}>
            {m.name}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  );
}
