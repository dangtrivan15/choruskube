import { Badge } from "@/components/ui/badge";
import { STATUS_TONE_CLASSES, provisioningTone } from "@/lib/statusColors";
import type { ProvisioningStatus } from "@/lib/types";

const LABELS: Record<ProvisioningStatus, string> = {
  pending: "Pending",
  provisioning: "Provisioning",
  ready: "Ready",
  failed: "Failed",
};

export default function ProvisioningBadge({ status }: { status: ProvisioningStatus }) {
  const label = LABELS[status] ?? LABELS.pending;
  const tone = provisioningTone(status);
  return (
    <Badge variant="outline" className={STATUS_TONE_CLASSES[tone].badge}>
      {label}
    </Badge>
  );
}
