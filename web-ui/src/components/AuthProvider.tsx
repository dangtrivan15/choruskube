import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from "react";
import { initAuth, isAuthEnabled, getToken, getClaims, refreshToken, login, logout } from "@/lib/oidc";
import { api, ApiError } from "@/lib/api";
import type { OrgRef, UserInfoResponse } from "@/lib/types";
import { SYSTEM_ORG_ID, SYSTEM_ORG_SLUG } from "@/lib/constants";
import { OrgPicker } from "@/components/OrgPicker";
import { useImpersonation } from "@/lib/impersonation";

export interface AuthContextType {
  authenticated: boolean;
  token: string | undefined;
  username: string | undefined;
  roles: string[];
  userId: string | null;
  activeOrg: OrgRef | null;
  memberships: OrgRef[];
  // TODO(multi-org): remove after callers migrate to activeOrg
  organizationId: string | undefined;
  // TODO(multi-org): remove after callers migrate to activeOrg
  organizationSlug: string | undefined;
  role: string | null;
  platformAdmin: boolean;
  /**
   * True when platform-admin powers are active *right now* — i.e. the user has the
   * platform-admin identity AND is not currently impersonating another org via "Manage as".
   *
   * `platformAdmin` alone is an identity fact (realm `org-admin` role + system-org membership)
   * and is static per user. But admin-only UI (Organizations list, delete/manage actions,
   * the `/admin/*` route guards) must disappear when:
   *   1. the platform admin has switched workspace to a non-system org — backend resolves
   *      `isPlatformAdmin()` against the JWT's active org, so calls 403 otherwise; or
   *   2. the platform admin is in a "Manage as" impersonation session — the UI
   *      presents as a member of the impersonated org to mirror how org-scoped API calls
   *      already behave (impersonation header rewrites tenant context server-side).
   *
   * Both conditions are folded into `activeOrg.slug !== SYSTEM_ORG_SLUG` below: during
   * impersonation we overwrite `activeOrg` with the impersonated org, so this single
   * check covers workspace-switch and manage-as uniformly.
   */
  actingAsPlatformAdmin: boolean;
  onboardingCompleted: boolean;
  logout: () => void;
}

const AuthContext = createContext<AuthContextType>({
  authenticated: false,
  token: undefined,
  username: undefined,
  roles: [],
  userId: null,
  activeOrg: null,
  memberships: [],
  organizationId: undefined,
  organizationSlug: undefined,
  role: null,
  platformAdmin: false,
  actingAsPlatformAdmin: false,
  onboardingCompleted: true,
  logout: () => {},
});

export function useAuth() {
  return useContext(AuthContext);
}

const DEV_MODE_ME: UserInfoResponse = {
  userId: null,
  activeOrg: {
    id: SYSTEM_ORG_ID,
    slug: SYSTEM_ORG_SLUG,
    displayName: "System",
  },
  memberships: [
    {
      id: SYSTEM_ORG_ID,
      slug: SYSTEM_ORG_SLUG,
      displayName: "System",
    },
  ],
  role: "org-admin",
  platformAdmin: true,
  onboardingCompleted: true,
};

/** Minimum interval between window-focus-driven /me refetches (ms). */
const ME_REFETCH_DEBOUNCE_MS = 30_000;

export function AuthProvider({ children }: { children: ReactNode }) {
  const [ready, setReady] = useState(false);
  const [authed, setAuthed] = useState(false);
  const [me, setMe] = useState<UserInfoResponse | null>(null);
  const [authError, setAuthError] = useState<string | null>(null);
  const lastMeFetchRef = useRef<number>(0);
  const impersonation = useImpersonation();

  // Refetch /me from the backend. Debounced so tabbing back quickly doesn't
  // spam the server. Safe to call from effects: failures are logged but do not
  // clobber the already-loaded state.
  const refetchMe = useCallback(async () => {
    const now = Date.now();
    if (now - lastMeFetchRef.current < ME_REFETCH_DEBOUNCE_MS) return;
    lastMeFetchRef.current = now;
    try {
      const data = await api.get<UserInfoResponse>("/me");
      setMe(data);
    } catch (err) {
      console.warn("Failed to refetch /me on focus:", err);
    }
  }, []);

  useEffect(() => {
    if (!isAuthEnabled()) {
      // Dev mode: backend would return platform-admin defaults via /me.
      // Skip the network call and use the same shape locally.
      setMe(DEV_MODE_ME);
      setReady(true);
      return;
    }

    initAuth()
      .then(async (authenticated) => {
        if (!authenticated) {
          await login();
          return;
        }
        setAuthed(true);
        try {
          const data = await api.get<UserInfoResponse>("/me");
          setMe(data);
          lastMeFetchRef.current = Date.now();
        } catch (err) {
          console.error("Failed to fetch /me:", err);
          setAuthError(
            err instanceof ApiError
              ? `Authentication failed (${err.status}): ${err.message}`
              : "Failed to load user profile. Please try again.",
          );
        }
        setReady(true);
        // Refresh token periodically; on failure, re-trigger interactive login.
        const interval = setInterval(() => {
          refreshToken(30).catch(() => login());
        }, 60_000);
        return () => clearInterval(interval);
      })
      .catch((err) => {
        console.error("Auth init failed:", err);
      });
  }, []);

  // Refetch /me when the user returns to the tab (e.g., after a silent re-auth
  // "switch org" redirect flow completes in a background tab). Debounced.
  // TODO(multi-org): if we promote /me to a TanStack query, switch via silent
  // re-auth should also call queryClient.invalidateQueries() so pre-switch
  // cached data doesn't flash.
  useEffect(() => {
    if (!isAuthEnabled()) return;
    const onFocus = () => {
      void refetchMe();
    };
    window.addEventListener("focus", onFocus);
    return () => window.removeEventListener("focus", onFocus);
  }, [refetchMe]);

  if (!ready) {
    return (
      <div className="flex h-screen items-center justify-center">
        <p className="text-muted-foreground">Authenticating...</p>
      </div>
    );
  }

  if (authError) {
    return (
      <div className="flex h-screen flex-col items-center justify-center gap-4">
        <p className="text-destructive font-medium">Authentication Error</p>
        <p className="text-muted-foreground text-sm">{authError}</p>
        <div className="flex gap-2">
          <button
            className="rounded bg-primary px-4 py-2 text-sm text-primary-foreground"
            onClick={() => {
              setAuthError(null);
              window.location.reload();
            }}
          >
            Retry
          </button>
          <button
            className="rounded border px-4 py-2 text-sm"
            onClick={() => { void logout(); }}
          >
            Logout
          </button>
        </div>
      </div>
    );
  }

  // Multi-org: user is a member of 1+ orgs but JWT has no active org claim
  // yet (e.g., they just logged in without an `organization:<slug>` scope).
  // Show the workspace picker, which triggers a silent re-auth on click.
  // Zero-membership users fall through to the existing onboarding path
  // handled downstream of the provider.
  if (authed && me && me.activeOrg == null && me.memberships.length >= 1) {
    return <OrgPicker memberships={me.memberships} />;
  }

  const jwtActiveOrg = me?.activeOrg ?? null;
  const jwtMemberships = me?.memberships ?? [];
  const platformAdmin = me?.platformAdmin ?? false;

  // "Manage as" impersonation takes precedence over the JWT's active org. See the
  // `actingAsPlatformAdmin` docstring above for the rationale. Impersonation
  // sessionStorage carries only id+slug, so we synthesize displayName from slug.
  const impersonatedOrg: OrgRef | null = impersonation
    ? { id: impersonation.orgId, slug: impersonation.orgSlug, displayName: impersonation.orgSlug }
    : null;
  const activeOrg = impersonatedOrg ?? jwtActiveOrg;
  const memberships = impersonatedOrg ? [impersonatedOrg] : jwtMemberships;
  const actingAsPlatformAdmin = platformAdmin && activeOrg?.slug === SYSTEM_ORG_SLUG;

  const claims = getClaims();
  const value: AuthContextType = authed
    ? {
        authenticated: true,
        token: getToken(),
        username: claims?.username,
        roles: claims?.roles ?? [],
        userId: me?.userId ?? null,
        activeOrg,
        memberships,
        organizationId: activeOrg?.id,
        organizationSlug: activeOrg?.slug,
        role: me?.role ?? null,
        platformAdmin,
        actingAsPlatformAdmin,
        onboardingCompleted: me?.onboardingCompleted ?? true,
        logout: () => { void logout(); },
      }
    : {
        authenticated: false,
        token: undefined,
        username: undefined,
        roles: [],
        userId: me?.userId ?? null,
        activeOrg,
        memberships,
        organizationId: activeOrg?.id,
        organizationSlug: activeOrg?.slug,
        role: me?.role ?? null,
        platformAdmin,
        actingAsPlatformAdmin,
        onboardingCompleted: me?.onboardingCompleted ?? true,
        logout: () => {},
      };

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
