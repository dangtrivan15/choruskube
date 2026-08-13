import { format, parseISO } from "date-fns";
import { CalendarDays } from "lucide-react";
import { Input } from "@/components/ui/input";
import { cn } from "@/lib/utils";

interface TargetDateFieldProps {
  /** ISO-8601 date string (`"YYYY-MM-DD"`) or `null` for "no target". */
  value: string | null;
  /** Required in edit mode (the default); unused in `readOnly` mode. */
  onChange?: (value: string | null) => void;
  /** Disable the input while a set/clear mutation is in flight. */
  disabled?: boolean;
  /**
   * `true` renders the formatted display chip (badge-row placement, mirroring
   * `PriorityBadge`); `false`/omitted (the default) renders the editable native
   * date input (`Authorized` row placement, mirroring `PrioritySelect`). A
   * single component with two explicit modes, rather than a disabled input
   * standing in for the display, keeps both detail pages DRY without
   * conflating "not editable here" with "can't be edited at all".
   */
  readOnly?: boolean;
  testId?: string;
}

/**
 * Shared target-date control — set/clear an Epic or Story's optional due date.
 * Uses a native `<input type="date">` for entry (Decision 4): a date-only value
 * maps 1:1 to the native control with no conversion, and there is no existing
 * date-picker component/dependency to reuse. Display parses with `parseISO`
 * (never `new Date("YYYY-MM-DD")`, which parses as UTC and can drift the shown
 * day by one depending on the viewer's timezone).
 */
export default function TargetDateField({
  value,
  onChange,
  disabled,
  readOnly = false,
  testId,
}: TargetDateFieldProps) {
  if (readOnly) {
    return (
      <span
        data-testid={testId}
        className={cn(
          "inline-flex w-fit items-center gap-1 rounded-full border px-2 py-0.5 text-xs font-medium",
          "border-border bg-muted/40 text-muted-foreground"
        )}
      >
        <CalendarDays className="size-3" />
        {value ? format(parseISO(value), "MMM d, yyyy") : "No target date"}
      </span>
    );
  }

  return (
    <Input
      type="date"
      size="sm"
      className="w-auto"
      data-testid={testId}
      value={value ?? ""}
      disabled={disabled}
      onChange={(e) => onChange?.(e.target.value === "" ? null : e.target.value)}
    />
  );
}
