import type { ComponentType, ReactNode } from "react";
import type { RouteObject } from "react-router";
import type { LucideIcon } from "lucide-react";
import type { Command } from "@/lib/commands";
import type { ImpersonationExtension } from "@/lib/impersonation";

/**
 * Runtime, auth-derived context handed to extension callbacks at call time. Extensions are
 * injected once at bootstrap (static), but some affordances (e.g. a per-org link) need the
 * current org. Callbacks receive this so the static injection can produce a runtime value.
 */
export interface NavContext {
  organizationId?: string;
  actingAsPlatformAdmin?: boolean;
}

/** Context for an injected keyboard sequence: navigation plus runtime auth state. */
export interface ShortcutContext extends NavContext {
  navigate: (path: string) => void;
}

/** A sidebar navigation entry. `to` may be a function of runtime context for per-org links. */
export interface NavItem {
  to: string | ((ctx: NavContext) => string | undefined);
  label: string;
  icon: LucideIcon;
  showBadge?: boolean;
  shortcutHint?: string;
  platformAdminOnly?: boolean;
  /** Stable e2e selector. Defaults to `nav-{label.toLowerCase()}`; set it to keep a hyphenated
   *  id for multi-word labels (e.g. "My Organization" → `nav-my-organization`). */
  testId?: string;
}

/**
 * Everything an entrypoint can contribute to the app. The OSS entrypoint passes {}; a downstream
 * extension entrypoint passes its routes, nav, commands, impersonation, and keyboard sequences. Core
 * reads these via ExtensionsContext (React) and a module setter (impersonation, non-React) and
 * never references the extension source itself.
 */
export interface AppExtensions {
  /** Extra routes appended to the core route table (e.g. injected admin pages). */
  routes?: RouteObject[];
  /** Extra sidebar nav items, rendered after the core items (platformAdminOnly still applies). */
  navItems?: NavItem[];
  /** Extra command-palette commands, merged into the core command list. */
  commands?: Command[];
  /** Injected impersonation store + banner. Installed at module scope for the non-React fetch layer. */
  impersonation?: ImpersonationExtension;
  /** Extra two-key sequences, e.g. `{ "go": ctx => ... }`, merged into the keyboard hook. */
  keyboardSequences?: Record<string, (ctx: ShortcutContext) => void>;
  /**
   * Controls the OSS→Cloud upsell nudge in the sidebar footer. Defaults to shown (the OSS
   * entrypoint passes {} and the nudge renders); an entrypoint where the nudge is redundant sets
   * this `false` to suppress it. Core never references the downstream product — it only reads the flag.
   */
  showSaaSNudge?: boolean;
  /**
   * Wrapper components nested around the whole app tree at bootstrap, outermost-first. Lets an
   * extension inject its own React context providers without core importing the extension source.
   * Each receives `{ children }`.
   *
   * IMPORTANT: these providers render OUTSIDE `AuthProvider`, so `useAuth()` always returns the
   * default context (unauthenticated). Use `authedProviders` instead for providers that need the
   * active org or any other auth-derived state.
   */
  appProviders?: ComponentType<{ children: ReactNode }>[];
  /**
   * Wrapper components nested INSIDE `AuthProvider` (and therefore inside `QueryClientProvider`),
   * outermost-first. Use this slot for providers that call `useAuth()` to read the active org or
   * other auth-derived state — e.g. an org-scoped feed-topic resolver that re-scopes STOMP topics
   * per org. Each receives `{ children }`.
   *
   * Distinct from `appProviders`, which wraps the whole app OUTSIDE auth and cannot read auth state.
   */
  authedProviders?: ComponentType<{ children: ReactNode }>[];
}
