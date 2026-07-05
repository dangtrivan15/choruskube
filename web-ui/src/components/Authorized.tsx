import { type ReactNode } from "react";
import { usePermission, type Permissions } from "@/hooks/usePermission";

interface AuthorizedProps {
  /** Permission level required to render children */
  require: keyof Permissions;
  /** Optional fallback when unauthorized (default: render nothing) */
  fallback?: ReactNode;
  children: ReactNode;
}

/**
 * Conditionally renders children based on the user's permission level.
 *
 * Unlike <RequireRole> (which guards routes via redirect), <Authorized>
 * wraps inline UI elements and hides them when unauthorized.
 *
 * Usage:
 *   <Authorized require="canOperate">
 *     <Button>Start Run</Button>
 *   </Authorized>
 */
export default function Authorized({ require, fallback = null, children }: AuthorizedProps) {
  const permissions = usePermission();
  return permissions[require] ? <>{children}</> : <>{fallback}</>;
}
