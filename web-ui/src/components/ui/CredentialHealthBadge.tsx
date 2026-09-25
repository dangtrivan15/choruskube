import { Badge } from "@/components/ui/badge";
import { STATUS_TONE_CLASSES, credentialHealthTone } from "@/lib/statusColors";
import type { CredentialHealthStatus } from "@/lib/types";

const LABELS: Record<CredentialHealthStatus, string> = {
  VALID: "Valid",
  EXPIRED: "Expired",
  INSUFFICIENT_PERMISSIONS: "Insufficient Permissions",
  UNREACHABLE: "Unreachable",
};

export default function CredentialHealthBadge({
  status,
}: {
  status: CredentialHealthStatus | null | undefined;
}) {
  if (!status) return null;
  const label = LABELS[status];
  if (!label) return null;
  return (
    <Badge variant="outline" className={STATUS_TONE_CLASSES[credentialHealthTone(status)].badge}>
      {label}
    </Badge>
  );
}
