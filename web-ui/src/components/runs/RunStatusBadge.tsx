import { Badge } from "@/components/ui/badge";
import { statusBadgeClass } from "@/lib/statusColors";

export default function RunStatusBadge({ status }: { status: string }) {
  // line-through is a text-decoration concern, not a color concern
  const classes = `${statusBadgeClass(status)}${status === "cancelled" ? " line-through" : ""}`;
  return (
    <Badge variant="outline" className={classes}>
      {status.replace(/_/g, " ")}
    </Badge>
  );
}
