import { Bot } from "lucide-react";
import { Badge } from "@/components/ui/badge";

interface AutopilotRunBadgeProps {
  autopilotId: string | null;
}

/**
 * Marks a run the Autopilot started, wherever a run is listed or opened. Without it an
 * unattended start is indistinguishable from a colleague's, which is what makes a run nobody
 * remembers starting read as a fault rather than as the Autopilot working.
 */
export default function AutopilotRunBadge({ autopilotId }: AutopilotRunBadgeProps) {
  if (autopilotId == null) return null;
  return (
    <Badge data-testid="autopilot-run-badge" variant="outline" className="shrink-0 gap-1">
      <Bot className="size-3" />
      Autopilot
    </Badge>
  );
}
