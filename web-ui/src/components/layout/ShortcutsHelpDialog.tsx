import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
} from "@/components/ui/dialog";
import { groupCommands, type Command } from "@/lib/commands";
import { isMac } from "@/hooks/useKeyboardShortcuts";
import { useVisibleCommands } from "@/hooks/useVisibleCommands";

// ---------------------------------------------------------------------------
// Props
// ---------------------------------------------------------------------------

interface ShortcutsHelpDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export default function ShortcutsHelpDialog({
  open,
  onOpenChange,
}: ShortcutsHelpDialogProps) {
  const visible = useVisibleCommands();
  const commandsWithShortcuts = visible.filter((cmd) => cmd.shortcut);
  const groups = groupCommands(commandsWithShortcuts);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent size="md">
        <DialogHeader>
          <DialogTitle>Keyboard Shortcuts</DialogTitle>
          <DialogDescription>
            Use these shortcuts to navigate quickly.
          </DialogDescription>
        </DialogHeader>

        <div className="flex flex-col gap-4">
          {groups.map((group) => (
            <div key={group.category}>
              <h3 className="mb-2 text-xs font-medium uppercase tracking-wider text-muted-foreground">
                {group.label}
              </h3>
              <div className="flex flex-col gap-1">
                {group.commands.map((cmd) => (
                  <ShortcutRow key={cmd.id} command={cmd} />
                ))}
              </div>
            </div>
          ))}
        </div>
      </DialogContent>
    </Dialog>
  );
}

// ---------------------------------------------------------------------------
// Row
// ---------------------------------------------------------------------------

function ShortcutRow({ command }: { command: Command }) {
  if (!command.shortcut) return null;

  const display = isMac()
    ? command.shortcut.replace("Ctrl", "Cmd")
    : command.shortcut;

  const parts =
    display.split("+").length > 1
      ? display.split("+")
      : display.split(" ");

  return (
    <div className="flex items-center justify-between py-1">
      <span className="text-sm">{command.label}</span>
      <span className="flex items-center gap-1">
        {parts.map((part, i) => (
          <kbd
            key={i}
            className="rounded border bg-muted px-1.5 py-0.5 font-mono text-xs text-muted-foreground"
          >
            {part.trim()}
          </kbd>
        ))}
      </span>
    </div>
  );
}
