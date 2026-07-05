import { cn } from "@/lib/utils";

interface ResizeHandleProps {
  /** Which side of the panel the handle sits on */
  side: "left" | "right";
  /** Whether the user is currently dragging */
  isDragging: boolean;
  /** Pointer-down handler from useResizable */
  onPointerDown: (e: React.PointerEvent) => void;
}

/**
 * Invisible drag handle (6px wide) that reveals a 2px bar on hover/drag.
 * Placed on the edge of a resizable panel.
 */
export default function ResizeHandle({
  side,
  isDragging,
  onPointerDown,
}: ResizeHandleProps) {
  return (
    <div
      role="separator"
      aria-orientation="vertical"
      aria-label={`Resize ${side} panel`}
      className={cn(
        "relative z-10 w-1.5 shrink-0 cursor-col-resize select-none",
        "group",
      )}
      onPointerDown={onPointerDown}
    >
      {/* Visible indicator bar */}
      <div
        className={cn(
          "absolute inset-y-0 w-0.5",
          side === "right" ? "left-0.5" : "right-0.5",
          isDragging
            ? "bg-ring opacity-100"
            : "bg-border opacity-0 group-hover:opacity-100",
          "transition-opacity duration-150",
        )}
      />
    </div>
  );
}
