import { Badge } from "@/components/ui/badge";
import type { ProvisioningStatus } from "@/lib/types";

const statusConfig: Record<
  ProvisioningStatus,
  { label: string; className: string }
> = {
  pending: {
    label: "Pending",
    className: "bg-status-neutral/15 text-status-neutral border-status-neutral/20",
  },
  provisioning: {
    label: "Provisioning",
    className: "bg-status-info/15 text-status-info border-status-info/20",
  },
  ready: {
    label: "Ready",
    className: "bg-status-success/15 text-status-success border-status-success/20",
  },
  failed: {
    label: "Failed",
    className: "bg-status-error/15 text-status-error border-status-error/20",
  },
};

export default function ProvisioningBadge({ status }: { status: ProvisioningStatus }) {
  const config = statusConfig[status] ?? statusConfig.pending;
  return (
    <Badge variant="outline" className={config.className}>
      {config.label}
    </Badge>
  );
}
