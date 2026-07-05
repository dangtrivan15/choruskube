import { type ReactNode } from "react";
import { Navigate } from "react-router";
import { isAuthEnabled } from "@/lib/oidc";
import { useAuth } from "@/components/AuthProvider";

interface RequireRoleProps {
  role: string;
  children: ReactNode;
  fallback?: string;
}

/**
 * Guards child routes behind a role check.
 *
 * Two modes:
 * - `role="platformAdmin"` reads `useAuth().actingAsPlatformAdmin`, i.e. platform-admin
 *   identity AND a `system` working org. Matches the backend, which resolves
 *   `@orgSecurity.isPlatformAdmin()` against the JWT's active org; a user who switched
 *   workspace to a non-system org gets redirected here rather than 403'd after render.
 * - Any other value is treated as an organization role and matched against
 *   `useAuth().roles`.
 *
 * When auth is disabled (dev mode), auth-role checks are bypassed.
 * For platformAdmin checks we still consult useAuth() — AuthProvider's
 * dev-mode default puts the user in the system org, so dev sees everything.
 */
export default function RequireRole({ role, children, fallback = "/" }: RequireRoleProps) {
  const { roles, authenticated, actingAsPlatformAdmin } = useAuth();

  if (role === "platformAdmin") {
    return actingAsPlatformAdmin ? <>{children}</> : <Navigate to={fallback} replace />;
  }

  // Mirror backend: bypass auth role checks when auth is disabled
  if (!isAuthEnabled()) {
    return <>{children}</>;
  }

  if (!authenticated || !roles.includes(role)) {
    return <Navigate to={fallback} replace />;
  }

  return <>{children}</>;
}
