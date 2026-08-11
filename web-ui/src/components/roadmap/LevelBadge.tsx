import { roadmapLevelMeta, type RoadmapLevel } from "@/lib/roadmapLevel";
import { cn } from "@/lib/utils";

interface Props {
  level: RoadmapLevel;
  className?: string;
}

/**
 * "Kind" eyebrow chip — level icon + label ("Epic"/"Story"/"Task") in the
 * level's accent color. The single rendering of `roadmapLevel.ts`'s per-level
 * identity (Decision 2), reused on all three detail pages and their board
 * cards so Epic/Story/Task read as distinct and consistent everywhere they
 * appear. Chip form factor matches the existing `epic-software-project-chip`
 * styling (EpicDetailPage.tsx).
 */
export default function LevelBadge({ level, className }: Props) {
  const meta = roadmapLevelMeta(level);
  const Icon = meta.Icon;

  return (
    <span
      data-testid={`level-badge-${level}`}
      className={cn(
        "inline-flex w-fit items-center gap-1.5 rounded-md border px-2 py-1 text-xs font-medium",
        meta.textClass,
        meta.bgClass,
        meta.borderClass,
        className,
      )}
    >
      <Icon className="size-3.5 shrink-0" />
      {meta.label}
    </span>
  );
}
