import { useEffect, useRef, useState } from "react";
import { NavLink } from "react-router";
import {
  Play,
  ClipboardCheck,
  Map,
  BarChart3,
  LogOut,
  GitBranch,
  ChevronsUpDown,
  Check,
  BookOpen,
  Bot,
} from "lucide-react";
import { usePendingGatesCount } from "@/hooks/usePendingGates";
import { usePendingGatesSubscription } from "@/hooks/usePendingGatesSubscription";
import { useAuth } from "@/components/AuthProvider";
import { useExtensions } from "@/ExtensionsContext";
import { Badge } from "@/components/ui/badge";
import Logo from "@/components/Logo";
import { isAuthEnabled, login } from "@/lib/oidc";
import type { NavItem } from "@/extensions";
import type { OrgRef } from "@/lib/types";

// Core nav items only. Injected extensions contribute their own nav items via
// AppExtensions.navItems — core does not name or gate them.
const coreNavItems: NavItem[] = [
  { to: "/analytics", label: "Analytics", icon: BarChart3, shortcutHint: "g n" },
  { to: "/roadmap", label: "Roadmap", icon: Map, shortcutHint: "g m" },
  { to: "/approvals", label: "Approvals", icon: ClipboardCheck, showBadge: true, shortcutHint: "g a" },
  { to: "/runs", label: "Runs", icon: Play, shortcutHint: "g r" },
  { to: "/git-repos", label: "Software Projects", icon: GitBranch, shortcutHint: "g g" },
  { to: "/docs", label: "Documentation", icon: BookOpen, shortcutHint: "g d" },
  { to: "/autopilot", label: "Autopilot", icon: Bot, shortcutHint: "g p" },
];

/**
 * Silent-reauth helper for switching the active organization. Mirrors
 * `OrgPicker`'s handler so both paths have identical fallback behaviour when
 * the SSO session has expired. TODO(multi-org): factor into a shared helper
 * once a third caller appears.
 */
function switchActiveOrg(slug: string): void {
  void login({
    scope: `openid organization:${slug}`,
    redirectUri: window.location.href,
    silent: true,
  });
}

interface WorkspaceSwitcherProps {
  activeSlug: string;
  memberships: OrgRef[];
}

function WorkspaceSwitcher({ activeSlug, memberships }: WorkspaceSwitcherProps) {
  const [open, setOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);

  // Close on outside click / escape.
  useEffect(() => {
    if (!open) return;
    const onPointerDown = (e: PointerEvent) => {
      if (!containerRef.current?.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") setOpen(false);
    };
    window.addEventListener("pointerdown", onPointerDown);
    window.addEventListener("keydown", onKeyDown);
    return () => {
      window.removeEventListener("pointerdown", onPointerDown);
      window.removeEventListener("keydown", onKeyDown);
    };
  }, [open]);

  return (
    <div ref={containerRef} className="relative mt-0.5">
      <button
        type="button"
        data-testid="sidebar-org-switcher-trigger"
        onClick={() => setOpen((v) => !v)}
        aria-haspopup="menu"
        aria-expanded={open}
        title={`Active organization: ${activeSlug} — click to switch`}
        className="flex w-full items-center justify-between gap-1 rounded px-0 py-0.5 font-mono text-xs text-sidebar-foreground/60 hover:text-sidebar-foreground"
      >
        <span data-testid="sidebar-active-org" className="truncate">
          {activeSlug}
        </span>
        <ChevronsUpDown className="h-3 w-3 shrink-0 opacity-60" aria-hidden />
      </button>
      {open && (
        <div
          role="menu"
          data-testid="sidebar-org-switcher-menu"
          className="absolute left-0 right-0 z-50 mt-1 overflow-hidden rounded-md border bg-popover p-1 text-popover-foreground shadow-md"
        >
          {memberships.map((m) => {
            const isActive = m.slug === activeSlug;
            return (
              <button
                key={m.id}
                type="button"
                role="menuitemradio"
                aria-checked={isActive}
                data-testid={`sidebar-org-switcher-item-${m.slug}`}
                onClick={() => {
                  setOpen(false);
                  if (!isActive) {
                    switchActiveOrg(m.slug);
                  }
                }}
                className="flex w-full items-center gap-2 rounded px-2 py-1.5 text-left text-sm hover:bg-accent hover:text-accent-foreground"
              >
                <span className="flex size-4 shrink-0 items-center justify-center">
                  {isActive && <Check className="h-3.5 w-3.5" aria-hidden />}
                </span>
                <span className="flex min-w-0 flex-col">
                  <span className="truncate font-medium">{m.displayName}</span>
                  <span className="truncate font-mono text-xs text-muted-foreground">{m.slug}</span>
                </span>
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
}

/**
 * Renders a small kbd-block hint for a shortcut sequence (e.g. "g r").
 * Splits the hint on whitespace and renders one <kbd> per token.
 */
function ShortcutHintKbd({ hint }: { hint: string }) {
  return (
    <span className="ml-auto hidden items-center gap-0.5 opacity-0 transition-opacity group-hover:opacity-100 md:flex md:opacity-40">
      {hint.split(" ").map((k, i) => (
        <kbd
          key={i}
          className="rounded border border-sidebar-foreground/20 bg-sidebar-accent/50 px-1 py-0.5 font-mono text-[10px] text-sidebar-foreground/60"
        >
          {k}
        </kbd>
      ))}
    </span>
  );
}

interface SidebarProps {
  onNavigate?: () => void;
}

export default function Sidebar({ onNavigate }: SidebarProps) {
  usePendingGatesSubscription();
  const { data: countData } = usePendingGatesCount();
  const pendingCount = countData?.count ?? 0;
  const {
    authenticated,
    username,
    logout,
    actingAsPlatformAdmin,
    organizationId,
    organizationSlug,
    memberships,
    role,
  } = useAuth();

  const { navItems: injectedNavItems, showSaaSNudge } = useExtensions();
  // Resolve each item's target (a string, or a function of runtime context for per-org links),
  // apply the platform-admin filter to injected items too, and drop items whose target is unset.
  const navCtx = { organizationId };
  const visibleItems = [...coreNavItems, ...(injectedNavItems ?? [])]
    .filter((item) => !item.platformAdminOnly || actingAsPlatformAdmin)
    .map((item) => ({ item, href: typeof item.to === "function" ? item.to(navCtx) : item.to }))
    .filter((entry): entry is { item: NavItem; href: string } => entry.href != null);
  const showSwitcher = !!organizationSlug && memberships.length > 1;

  return (
    <aside className="flex h-full w-full flex-col border-r border-surface-glass-border bg-surface-glass p-4 backdrop-blur-md">
      <div className="mb-8">
        <div className="flex items-center gap-2">
          <Logo size={22} />
          <h1 className="text-lg font-semibold tracking-tight">ChorusKube</h1>
        </div>
        {organizationSlug && !showSwitcher && (
          <p
            data-testid="sidebar-active-org"
            className="mt-0.5 font-mono text-xs text-sidebar-foreground/60 truncate"
            title={`Active organization: ${organizationSlug}`}
          >
            {organizationSlug}
          </p>
        )}
        {showSwitcher && organizationSlug && (
          <WorkspaceSwitcher activeSlug={organizationSlug} memberships={memberships} />
        )}
      </div>
      <nav className="flex flex-col gap-1">
        {visibleItems.map(({ item: { label, icon: Icon, showBadge, shortcutHint, testId }, href }) => (
          <NavLink
            key={href}
            to={href}
            data-testid={testId ?? `nav-${label.toLowerCase()}`}
            onClick={() => onNavigate?.()}
            className={({ isActive }) =>
              `flex items-center gap-3 rounded-md px-3 py-2.5 md:py-2 text-sm font-medium transition-colors ${
                isActive
                  ? "bg-sidebar-accent text-sidebar-accent-foreground"
                  : "text-sidebar-foreground hover:bg-sidebar-accent/50"
              }`
            }
          >
            <Icon className="h-4 w-4" />
            {label}
            {showBadge && pendingCount > 0 && (
              <span data-testid="nav-approvals-badge" className="ml-auto inline-flex h-5 min-w-5 items-center justify-center rounded-full bg-status-warning px-1.5 text-xs font-semibold text-background">
                {pendingCount}
              </span>
            )}
            {shortcutHint && !(showBadge && pendingCount > 0) && (
              <ShortcutHintKbd hint={shortcutHint} />
            )}
          </NavLink>
        ))}
      </nav>
      {authenticated && username && (
        <div className="mt-auto border-t pt-4">
          <div className="flex items-center justify-between px-3">
            <div className="flex items-center gap-2 min-w-0">
              <span className="truncate text-sm text-sidebar-foreground/70">{username}</span>
              {isAuthEnabled() && role && (
                <Badge
                  data-testid="sidebar-role-badge"
                  variant={role === "org-admin" ? "default" : role === "operator" ? "secondary" : "outline"}
                  className="shrink-0"
                >
                  {role === "org-admin" ? "Admin" : role.charAt(0).toUpperCase() + role.slice(1)}
                </Badge>
              )}
            </div>
            <button
              onClick={logout}
              className="shrink-0 rounded p-1 text-sidebar-foreground/50 hover:bg-sidebar-accent/50 hover:text-sidebar-foreground"
              title="Logout"
            >
              <LogOut className="h-4 w-4" />
            </button>
          </div>
        </div>
      )}
      {(showSaaSNudge ?? true) && (
        <div className={`${authenticated && username ? "" : "mt-auto "}border-t pt-4`}>
          <a
            data-testid="sidebar-saas-nudge"
            href="https://choruskube.com/?utm_source=oss&utm_medium=webui&utm_campaign=sidebar-nudge"
            target="_blank"
            rel="noopener noreferrer"
            className="block text-center text-xs text-sidebar-foreground/70 hover:text-sidebar-foreground"
          >
            Need multi-user + K8s? Try ChorusKube Cloud →
          </a>
        </div>
      )}
    </aside>
  );
}
