import { login } from "@/lib/oidc";
import type { OrgRef } from "@/lib/types";

/**
 * Rendered when the JWT carries no active organization but the user is a
 * member of 2+ organizations. Picking a card triggers a silent re-auth with
 * `scope=openid organization:<slug>` so the OIDC provider can issue a new token with the
 * requested active org. Falls back to an interactive login when the silent
 * re-auth fails (e.g., the SSO session has expired).
 */
export function OrgPicker({ memberships }: { memberships: OrgRef[] }) {
  const pick = (m: OrgRef) => {
    void login({
      scope: `openid organization:${m.slug}`,
      redirectUri: window.location.href,
      silent: true,
    });
  };

  return (
    <div className="flex min-h-screen items-center justify-center bg-background p-4">
      <div className="w-full max-w-md rounded-2xl border bg-card p-8 shadow-md">
        <h2 className="text-xl font-bold">Choose your workspace</h2>
        <p className="mt-1 text-sm text-muted-foreground">
          You&apos;re a member of more than one organization.
        </p>
        <ul className="mt-6 flex flex-col gap-2">
          {memberships.map((m) => (
            <li key={m.id}>
              <button
                type="button"
                onClick={() => pick(m)}
                data-testid={`org-picker-${m.slug}`}
                className="flex w-full items-center gap-3 rounded-xl border p-3 text-left transition-colors hover:bg-accent"
              >
                <div className="flex size-8 items-center justify-center rounded-lg bg-primary/20 font-semibold">
                  {(m.displayName || m.slug).charAt(0).toUpperCase()}
                </div>
                <div className="flex flex-col">
                  <span className="font-medium">{m.displayName}</span>
                  <span className="font-mono text-xs text-muted-foreground">{m.slug}</span>
                </div>
              </button>
            </li>
          ))}
        </ul>
      </div>
    </div>
  );
}
