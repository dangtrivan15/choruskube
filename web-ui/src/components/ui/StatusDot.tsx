import { cn } from "@/lib/utils";
import { STATUS_TONE_CLASSES, type StatusTone } from "@/lib/statusColors";

interface StatusDotProps {
  tone: StatusTone;
  className?: string;
  "data-testid"?: string;
}

/** Decorative solid-color status dot. Always pair with adjacent text that states the status. */
export default function StatusDot({ tone, className, "data-testid": testId }: StatusDotProps) {
  return (
    <span
      aria-hidden="true"
      data-tone={tone}
      data-testid={testId}
      className={cn(STATUS_TONE_CLASSES[tone].dot, className)}
    />
  );
}
