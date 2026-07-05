import { useState, useRef, useEffect, useCallback, useMemo } from "react";
import { Dialog as DialogPrimitive } from "@base-ui/react/dialog";
import { Search } from "lucide-react";
import { cn } from "@/lib/utils";
import {
  type Command,
  type CommandCategory,
  type CommandGroup,
  buildRunCommands,
  searchCommands,
  groupCommands,
} from "@/lib/commands";
import { isMac } from "@/hooks/useKeyboardShortcuts";
import { useVisibleCommands } from "@/hooks/useVisibleCommands";
import type { RunSummary } from "@/lib/types";

// ---------------------------------------------------------------------------
// Props
// ---------------------------------------------------------------------------

interface CommandPaletteProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onExecute: (command: Command) => void;
  runs?: RunSummary[];
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export default function CommandPalette({
  open,
  onOpenChange,
  onExecute,
  runs,
}: CommandPaletteProps) {
  const [query, setQuery] = useState("");
  const [activeIndex, setActiveIndex] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);
  const listRef = useRef<HTMLDivElement>(null);

  // Build all commands — visible static commands (filtered by auth) + runs.
  const visible = useVisibleCommands();
  const allCommands = useMemo(() => {
    const runCmds = buildRunCommands(runs);
    return [...visible, ...runCmds];
  }, [visible, runs]);

  // Filter
  const filtered = useMemo(
    () => searchCommands(allCommands, query),
    [allCommands, query]
  );

  // Group for display
  const groups = useMemo(() => groupCommands(filtered), [filtered]);

  // Flat list for keyboard navigation
  const flatItems = useMemo(
    () => groups.flatMap((g) => g.commands),
    [groups]
  );

  // Pre-compute start index for each group (avoids mutable counter in render)
  const groupStartIndices = useMemo(() => {
    const indices = new Map<CommandCategory, number>();
    let cumulative = 0;
    for (const group of groups) {
      indices.set(group.category, cumulative);
      cumulative += group.commands.length;
    }
    return indices;
  }, [groups]);

  // Reset state when dialog opens
  useEffect(() => {
    if (open) {
      setQuery("");
      setActiveIndex(0);
    }
  }, [open]);

  // Focus input when dialog opens
  useEffect(() => {
    if (open) {
      // Small delay to let the dialog render
      requestAnimationFrame(() => {
        inputRef.current?.focus();
      });
    }
  }, [open]);

  // Scroll active item into view
  useEffect(() => {
    if (!listRef.current) return;
    const active = listRef.current.querySelector('[data-active="true"]');
    if (active) {
      active.scrollIntoView?.({ block: "nearest" });
    }
  }, [activeIndex]);

  // Clamp active index when filtered results change
  useEffect(() => {
    setActiveIndex((prev) => Math.min(prev, Math.max(0, flatItems.length - 1)));
  }, [flatItems.length]);

  const executeItem = useCallback(
    (cmd: Command) => {
      onOpenChange(false);
      onExecute(cmd);
    },
    [onOpenChange, onExecute]
  );

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      switch (e.key) {
        case "ArrowDown":
          e.preventDefault();
          setActiveIndex((prev) =>
            prev < flatItems.length - 1 ? prev + 1 : 0
          );
          break;
        case "ArrowUp":
          e.preventDefault();
          setActiveIndex((prev) =>
            prev > 0 ? prev - 1 : flatItems.length - 1
          );
          break;
        case "Home":
          e.preventDefault();
          setActiveIndex(0);
          break;
        case "End":
          e.preventDefault();
          setActiveIndex(Math.max(0, flatItems.length - 1));
          break;
        case "Enter":
          e.preventDefault();
          if (flatItems[activeIndex]) {
            executeItem(flatItems[activeIndex]);
          }
          break;
      }
    },
    [flatItems, activeIndex, executeItem]
  );

  return (
    <DialogPrimitive.Root open={open} onOpenChange={onOpenChange}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Backdrop
          className="fixed inset-0 z-50 bg-black/10 supports-backdrop-filter:backdrop-blur-xs data-open:animate-in data-open:fade-in-0 data-closed:animate-out data-closed:fade-out-0"
        />
        <DialogPrimitive.Popup
          className={cn(
            "fixed top-[20%] left-1/2 z-50 w-full max-w-lg -translate-x-1/2",
            "rounded-xl bg-background text-sm ring-1 ring-foreground/10 shadow-lg",
            "flex flex-col overflow-hidden",
            "data-open:animate-in data-open:fade-in-0 data-open:zoom-in-95",
            "data-closed:animate-out data-closed:fade-out-0 data-closed:zoom-out-95"
          )}
        >
          <DialogPrimitive.Title className="sr-only">
            Command Palette
          </DialogPrimitive.Title>
          <DialogPrimitive.Description className="sr-only">
            Search for commands and navigate the application
          </DialogPrimitive.Description>

          {/* Search input */}
          <div className="flex items-center gap-2 border-b px-3">
            <Search className="h-4 w-4 shrink-0 text-muted-foreground" />
            <input
              ref={inputRef}
              value={query}
              onChange={(e) => {
                setQuery(e.target.value);
                setActiveIndex(0);
              }}
              onKeyDown={handleKeyDown}
              placeholder="Type a command..."
              className="flex-1 bg-transparent py-3 text-sm outline-none placeholder:text-muted-foreground"
              role="combobox"
              aria-expanded={true}
              aria-controls="command-palette-list"
              aria-activedescendant={
                flatItems[activeIndex]
                  ? `cmd-${flatItems[activeIndex].id}`
                  : undefined
              }
              autoComplete="off"
              spellCheck={false}
            />
            <kbd className="hidden text-xs text-muted-foreground sm:inline-block">
              Esc
            </kbd>
          </div>

          {/* Results list */}
          <div
            ref={listRef}
            id="command-palette-list"
            role="listbox"
            className="max-h-72 overflow-y-auto p-1"
          >
            {flatItems.length === 0 ? (
              <div className="py-6 text-center text-muted-foreground">
                No results found.
              </div>
            ) : (
              groups.map((group) => (
                <CommandGroupSection
                  key={group.category}
                  group={group}
                  startIndex={groupStartIndices.get(group.category) ?? 0}
                  activeIndex={activeIndex}
                  onSelect={executeItem}
                  onHover={setActiveIndex}
                />
              ))
            )}
          </div>

          {/* Footer */}
          <div className="flex items-center gap-4 border-t px-3 py-2 text-xs text-muted-foreground">
            <span className="flex items-center gap-1">
              <kbd className="rounded border bg-muted px-1 py-0.5 font-mono text-[10px]">
                {isMac() ? "Cmd" : "Ctrl"}+K
              </kbd>
              to open
            </span>
            <span className="flex items-center gap-1">
              <kbd className="rounded border bg-muted px-1 py-0.5 font-mono text-[10px]">
                &uarr;&darr;
              </kbd>
              navigate
            </span>
            <span className="flex items-center gap-1">
              <kbd className="rounded border bg-muted px-1 py-0.5 font-mono text-[10px]">
                Enter
              </kbd>
              select
            </span>
          </div>
        </DialogPrimitive.Popup>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  );
}

// ---------------------------------------------------------------------------
// Group section
// ---------------------------------------------------------------------------

interface CommandGroupSectionProps {
  group: CommandGroup;
  startIndex: number;
  activeIndex: number;
  onSelect: (cmd: Command) => void;
  onHover: (index: number) => void;
}

function CommandGroupSection({
  group,
  startIndex,
  activeIndex,
  onSelect,
  onHover,
}: CommandGroupSectionProps) {
  return (
    <div>
      <div className="px-2 py-1.5 text-xs font-medium text-muted-foreground">
        {group.label}
      </div>
      {group.commands.map((cmd, i) => {
        const globalIndex = startIndex + i;
        const isActive = globalIndex === activeIndex;
        return (
          <div
            key={cmd.id}
            id={`cmd-${cmd.id}`}
            role="option"
            aria-selected={isActive}
            data-active={isActive}
            className={cn(
              "flex cursor-pointer items-center justify-between rounded-md px-2 py-1.5 text-sm",
              isActive
                ? "bg-accent text-accent-foreground"
                : "text-foreground hover:bg-accent/50"
            )}
            onClick={() => onSelect(cmd)}
            onMouseEnter={() => onHover(globalIndex)}
          >
            <span>{cmd.label}</span>
            {cmd.shortcut && (
              <ShortcutBadge shortcut={cmd.shortcut} />
            )}
          </div>
        );
      })}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Shortcut badge
// ---------------------------------------------------------------------------

function ShortcutBadge({ shortcut }: { shortcut: string }) {
  // Replace Ctrl with Cmd on macOS for display
  const display = isMac() ? shortcut.replace("Ctrl", "Cmd") : shortcut;

  const parts = display.split("+").length > 1
    ? display.split("+")
    : display.split(" ");

  return (
    <span className="flex items-center gap-0.5">
      {parts.map((part, i) => (
        <kbd
          key={i}
          className="rounded border bg-muted px-1.5 py-0.5 font-mono text-[10px] text-muted-foreground"
        >
          {part.trim()}
        </kbd>
      ))}
    </span>
  );
}
