import { Badge } from "@/components/ui/badge";
import type { CredentialHealthStatus } from "@/lib/types";

const statusConfig: Record<CredentialHealthStatus, { label: string; className: string }> = {
  VALID: {
    label: "Valid",
    className: "bg-status-success/15 text-status-success border-status-success/20",
  },
  EXPIRED: {
    label: "Expired",
    className: "bg-status-error/15 text-status-error border-status-error/20",
  },
  INSUFFICIENT_PERMISSIONS: {
    label: "Insufficient Permissions",
    className: "bg-status-error/15 text-status-error border-status-error/20",
  },
  UNREACHABLE: {
    label: "Unreachable",
    className: "bg-status-warning/15 text-status-warning border-status-warning/20",
  },
};

export default function CredentialHealthBadge({
  status,
}: {
  status: CredentialHealthStatus | null | undefined;
}) {
  if (!status) return null;
  const config = statusConfig[status];
  if (!config) return null;
  return (
    <Badge variant="outline" className={config.className}>
      {config.label}
    </Badge>
  );
}
