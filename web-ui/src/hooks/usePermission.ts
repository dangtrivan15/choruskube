import { useAuth } from "@/components/AuthProvider";
import { isAuthEnabled } from "@/lib/oidc";

export interface Permissions {
  canRead: boolean;
  canOperate: boolean;
  canAdmin: boolean;
  platformAdmin: boolean;
}

/**
 * Returns permission flags derived from the user's organization role.
 *
 * Mirrors the backend's OrganizationSecurityEvaluator hierarchy:
 * - canRead:       viewer | operator | org-admin
 * - canOperate:    operator | org-admin
 * - canAdmin:      org-admin
 * - platformAdmin: org-admin in the system org (cross-org actions)
 *
 * The organization role is named `org-admin` (not `admin`) because `admin` is conventionally
 * reserved as an identity-provider administrator role and would conflict.
 *
 * When auth is disabled (dev mode), all permissions are true.
 */
export function usePermission(): Permissions {
  const { role, platformAdmin } = useAuth();

  // Dev mode: mirror backend's "if (!authEnabled) return true"
  if (!isAuthEnabled()) {
    return { canRead: true, canOperate: true, canAdmin: true, platformAdmin: true };
  }

  const isAdmin = role === "org-admin";
  const isOperator = role === "operator";
  const isViewer = role === "viewer";

  return {
    canRead: isAdmin || isOperator || isViewer,
    canOperate: isAdmin || isOperator,
    canAdmin: isAdmin,
    platformAdmin,
  };
}
