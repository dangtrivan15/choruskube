import { priorityMeta, PRIORITY_ORDER } from "@/lib/priorityMeta";
import { cn } from "@/lib/utils";
import type { Priority } from "@/lib/types";

interface PriorityFilterProps {
  /** The active priority filter, or `undefined` for "All" (no filtering). */
  value: Priority | undefined;
  onChange: (value: Priority | undefined) => void;
}

/**
 * Roadmap toolbar "priority" filter — an All / High / Medium / Low segmented
 * control styled as a chip group consistent with `RoadmapReadyToggle` and
 * `SortDropdown`'s toolbar treatment. Selecting a level filters the Epic list
 * to that priority (threaded into `useEpics`' `?priority=` query param);
 * "All" clears the filter (`undefined`).
 */
export default function PriorityFilter({ value, onChange }: PriorityFilterProps) {
  const options: { key: string; label: string; level: Priority | undefined }[] = [
    { key: "all", label: "All", level: undefined },
    ...PRIORITY_ORDER.map((level) => ({
      key: level,
      label: priorityMeta(level)!.label,
      level,
    })),
  ];

  return (
    <div
      data-testid="priority-filter"
      role="group"
      aria-label="Filter by priority"
      className="inline-flex h-8 shrink-0 items-center rounded-lg border border-border bg-background p-0.5 text-sm"
    >
      {options.map((opt) => {
        const active = value === opt.level;
        return (
          <button
            key={opt.key}
            type="button"
            data-testid={`priority-filter-${opt.key}`}
            aria-pressed={active}
            onClick={() => onChange(opt.level)}
            className={cn(
              "inline-flex h-7 items-center rounded-md px-2.5 font-medium transition-colors outline-none select-none focus-visible:ring-3 focus-visible:ring-ring/50",
              active
                ? "bg-primary text-primary-foreground"
                : "text-muted-foreground hover:text-foreground",
            )}
          >
            {opt.label}
          </button>
        );
      })}
    </div>
  );
}
