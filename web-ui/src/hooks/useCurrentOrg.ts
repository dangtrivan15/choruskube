import { useAuth } from "@/components/AuthProvider";
import { isAuthEnabled } from "@/lib/oidc";
import { SYSTEM_ORG_ID } from "@/lib/constants";

export function useCurrentOrg(): string {
  const { organizationId } = useAuth();
  if (!isAuthEnabled()) return SYSTEM_ORG_ID;
  if (!organizationId) {
    throw new Error(
      "Organization context unavailable. Please log out and log in again.",
    );
  }
  return organizationId;
}
