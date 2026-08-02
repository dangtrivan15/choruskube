import { Toggle as TogglePrimitive } from "@base-ui/react/toggle";
import { CircleCheck } from "lucide-react";
import { cn } from "@/lib/utils";

interface RoadmapReadyToggleProps {
  checked: boolean;
  onChange: (v: boolean) => void;
}

/**
 * Shared "Ready to start" filter toggle for the four roadmap views (Roadmap
 * list, Roadmap board, Epic detail, Story detail). Styled as a toolbar chip
 * consistent with `SortDropdown`'s surrounding toolbar treatment.
 */
export default function RoadmapReadyToggle({ checked, onChange }: RoadmapReadyToggleProps) {
  return (
    <TogglePrimitive
      data-testid="ready-to-start-toggle"
      aria-label="Ready to start"
      pressed={checked}
      onPressedChange={onChange}
      className={cn(
        "inline-flex h-8 shrink-0 items-center gap-1.5 rounded-lg border border-border bg-background px-2.5 text-sm font-medium text-muted-foreground transition-all outline-none select-none hover:bg-muted hover:text-foreground focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50",
        "data-[pressed]:border-transparent data-[pressed]:bg-primary data-[pressed]:text-primary-foreground"
      )}
    >
      <CircleCheck className="size-4" />
      Ready to start
    </TogglePrimitive>
  );
}
