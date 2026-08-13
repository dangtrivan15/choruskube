import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { priorityMeta, PRIORITY_ORDER } from "@/lib/priorityMeta";
import { cn } from "@/lib/utils";
import type { Priority } from "@/lib/types";

interface PrioritySelectProps {
  value: Priority;
  onChange: (value: Priority) => void;
  /** Passed through to the trigger's `size` (matches the `select.tsx` primitive). */
  size?: "sm" | "default";
  /** Disable while a re-prioritize mutation is in flight. */
  disabled?: boolean;
  testId?: string;
}

/**
 * High/Medium/Low priority picker — reuses the shared `select.tsx` primitive
 * (Base UI). Used both in the create dialogs (setting the initial priority) and
 * inline on the Epic/Story detail pages (re-prioritizing via the PATCH
 * priority endpoint). Each option renders its `priorityMeta` icon + label so
 * the accent colour matches `PriorityBadge` everywhere the value is shown.
 */
export default function PrioritySelect({
  value,
  onChange,
  size = "default",
  disabled,
  testId,
}: PrioritySelectProps) {
  return (
    <Select
      value={value}
      onValueChange={(v) => {
        if (v) onChange(v as Priority);
      }}
      disabled={disabled}
    >
      <SelectTrigger
        data-testid={testId}
        aria-label="Priority"
        size={size}
        className="w-auto"
      >
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        {PRIORITY_ORDER.map((level) => {
          const meta = priorityMeta(level)!;
          const Icon = meta.Icon;
          return (
            <SelectItem key={level} value={level} data-testid={`priority-option-${level}`}>
              <Icon className={cn("size-3.5", meta.textClass)} />
              {meta.label}
            </SelectItem>
          );
        })}
      </SelectContent>
    </Select>
  );
}
