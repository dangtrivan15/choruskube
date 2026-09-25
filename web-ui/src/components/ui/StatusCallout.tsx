import type { ReactNode } from "react";
import { Info, CheckCircle, AlertTriangle, XCircle, PauseCircle, type LucideIcon } from "lucide-react";
import { cn } from "@/lib/utils";
import { STATUS_TONE_CLASSES, type StatusTone } from "@/lib/statusColors";

const DEFAULT_TONE_ICON: Record<StatusTone, LucideIcon> = {
  info: Info,
  success: CheckCircle,
  warning: AlertTriangle,
  error: XCircle,
  accent: PauseCircle,
  neutral: Info,
};

interface StatusCalloutProps {
  tone: StatusTone;
  title?: string;
  icon?: LucideIcon;
  role?: "alert" | "status";
  className?: string;
  children?: ReactNode;
  "data-testid"?: string;
}

/**
 * Tinted, bordered callout block. Body text always renders in the
 * foreground color — the tone lives in the tint, border and icon — so a
 * full sentence stays readable instead of tone-on-tone.
 */
export default function StatusCallout({
  tone,
  title,
  icon,
  role,
  className,
  children,
  "data-testid": testId,
}: StatusCalloutProps) {
  const Icon = icon ?? DEFAULT_TONE_ICON[tone];
  return (
    <div
      data-tone={tone}
      data-testid={testId}
      role={role}
      className={cn(
        "flex items-start gap-2 rounded-md border p-3 text-sm",
        STATUS_TONE_CLASSES[tone].callout,
        className
      )}
    >
      <Icon aria-hidden="true" className={cn("mt-0.5 size-4 shrink-0", STATUS_TONE_CLASSES[tone].text)} />
      <div className="min-w-0 flex-1">
        {title && <p className="font-medium">{title}</p>}
        {children}
      </div>
    </div>
  );
}
