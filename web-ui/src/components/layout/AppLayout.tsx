import { useState, useCallback, useMemo } from "react";
import { Outlet, useNavigate } from "react-router";
import { useQueryClient } from "@tanstack/react-query";
import Sidebar from "./Sidebar";
import ThemeToggle from "./ThemeToggle";
import ActivityFeedButton from "./ActivityFeedButton";
import ActivityFeedPanel from "./ActivityFeedPanel";
import MobileHeader from "./MobileHeader";
import MobileDrawer from "./MobileDrawer";
import CommandPalette from "./CommandPalette";
import ShortcutsHelpDialog from "./ShortcutsHelpDialog";
import { useResizable } from "@/hooks/useResizable";
import { useTheme } from "@/hooks/useTheme";
import { useMobileBreakpoint } from "@/hooks/useMobileBreakpoint";
import { useSidebarDrawer } from "@/hooks/useSidebarDrawer";
import {
  useKeyboardShortcuts,
  type KeyboardShortcutHandlers,
} from "@/hooks/useKeyboardShortcuts";
import ResizeHandle from "@/components/ui/ResizeHandle";
import { useAuth } from "@/components/AuthProvider";
import { useExtensions } from "@/ExtensionsContext";
import type { Command } from "@/lib/commands";
import type { RunSummary, PageResponse } from "@/lib/types";

export default function AppLayout() {
  const isMobile = useMobileBreakpoint();
  const drawer = useSidebarDrawer();

  const sidebar = useResizable({
    side: "right",
    defaultWidth: 224,
    minWidth: 160,
    maxWidth: 400,
    storageKey: "sidebar-width",
  });

  const navigate = useNavigate();
  const { toggle: toggleTheme } = useTheme();
  const queryClient = useQueryClient();
  const { organizationId, actingAsPlatformAdmin } = useAuth();
  const { keyboardSequences } = useExtensions();

  // Dialog state — mutually exclusive (palette & shortcuts)
  const [paletteOpen, setPaletteOpen] = useState(false);
  const [shortcutsOpen, setShortcutsOpen] = useState(false);
  const [feedOpen, setFeedOpen] = useState(false);

  const openPalette = useCallback(() => {
    setShortcutsOpen(false);
    setPaletteOpen(true);
  }, []);

  const openShortcuts = useCallback(() => {
    setPaletteOpen(false);
    setShortcutsOpen(true);
  }, []);

  const extraSequences = useMemo(() => {
    if (!keyboardSequences) return undefined;
    const ctx = { navigate, organizationId, actingAsPlatformAdmin };
    return Object.fromEntries(
      Object.entries(keyboardSequences).map(([seq, fn]) => [seq, () => fn(ctx)])
    );
  }, [keyboardSequences, navigate, organizationId, actingAsPlatformAdmin]);

  // Keyboard shortcut handlers
  const shortcutHandlers: KeyboardShortcutHandlers = useMemo(
    () => ({
      onCommandPalette: openPalette,
      onNavigate: (path: string) => navigate(path),
      onToggleTheme: toggleTheme,
      onShortcutsHelp: openShortcuts,
      extraSequences,
    }),
    [openPalette, openShortcuts, navigate, toggleTheme, extraSequences]
  );

  useKeyboardShortcuts(shortcutHandlers);

  // Get cached runs for the command palette (no extra fetch).
  // The list query key is ["runs", status, name, pagination] and stores
  // PageResponse<RunSummary>, so we prefix-match and unwrap .content.
  const cachedRuns = useMemo(() => {
    const entries = queryClient.getQueriesData<PageResponse<RunSummary>>({
      queryKey: ["runs"],
    });
    for (const [, data] of entries) {
      if (data && Array.isArray(data.content) && data.content.length > 0) {
        return data.content;
      }
    }
    return undefined;
  }, [queryClient, paletteOpen]); // eslint-disable-line react-hooks/exhaustive-deps -- re-read cache when palette opens

  // Execute a command from the palette
  const handleExecute = useCallback(
    (command: Command) => {
      // Commands carrying an explicit resolvePath (e.g. injected per-org links) win over the
      // nav:X → /X convention, so paths that don't follow it don't fall through and 404.
      if (command.resolvePath) {
        const path = command.resolvePath({ organizationId, actingAsPlatformAdmin });
        if (path) navigate(path);
      } else if (command.id.startsWith("nav:")) {
        // Convention: nav:X → /X  (e.g. nav:runs → /runs)
        navigate(`/${command.id.slice(4)}`);
      } else if (command.id.startsWith("run:")) {
        navigate(`/runs/${command.id.slice(4)}`);
      } else if (command.id === "action:toggle-theme") {
        toggleTheme();
      } else if (command.id === "action:command-palette") {
        openPalette();
      } else if (command.id === "action:shortcuts-help") {
        openShortcuts();
      }
    },
    [navigate, toggleTheme, openPalette, openShortcuts, organizationId, actingAsPlatformAdmin]
  );

  return (
    <div
      className={`flex h-screen flex-col text-foreground md:flex-row${sidebar.isDragging ? " select-none" : ""}`}
    >
      {isMobile ? (
        <>
          <MobileHeader
            onMenuToggle={drawer.toggle}
            onActivityFeedToggle={() => setFeedOpen((o) => !o)}
          />
          <MobileDrawer
            open={drawer.isOpen}
            onOpenChange={(open) => { if (!open) drawer.close(); }}
          >
            <Sidebar onNavigate={drawer.close} />
          </MobileDrawer>
        </>
      ) : (
        <>
          <div style={{ width: sidebar.width }} className="shrink-0">
            <Sidebar />
          </div>
          <ResizeHandle
            side="right"
            isDragging={sidebar.isDragging}
            onPointerDown={sidebar.handlePointerDown}
          />
        </>
      )}
      <div className="relative flex flex-1 flex-col overflow-hidden">
        {!isMobile && (
          <header className="relative flex h-12 items-center justify-end gap-2 border-b px-4">
            <ActivityFeedButton onClick={() => setFeedOpen((o) => !o)} />
            <ThemeToggle />
          </header>
        )}
        <ActivityFeedPanel
          open={feedOpen}
          onClose={() => setFeedOpen(false)}
        />
        <main className="flex-1 overflow-auto p-4 md:p-6">
          <Outlet />
        </main>
      </div>

      <CommandPalette
        open={paletteOpen}
        onOpenChange={setPaletteOpen}
        onExecute={handleExecute}
        runs={cachedRuns}
      />
      <ShortcutsHelpDialog
        open={shortcutsOpen}
        onOpenChange={setShortcutsOpen}
      />
    </div>
  );
}
