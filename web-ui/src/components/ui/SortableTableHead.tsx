import { ChevronUp, ChevronDown } from "lucide-react";
import type { SortParam } from "@/lib/types";
import { cn } from "@/lib/utils";

interface SortableTableHeadProps {
  label: string;
  field: string;
  currentSort: SortParam | null;
  onSort: (sort: SortParam | null) => void;
  className?: string;
}

export default function SortableTableHead({
  label,
  field,
  currentSort,
  onSort,
  className,
}: SortableTableHeadProps) {
  const isActive = currentSort?.field === field;
  const direction = isActive ? currentSort.direction : null;

  function handleClick() {
    if (!isActive) {
      onSort({ field, direction: "asc" });
    } else if (direction === "asc") {
      onSort({ field, direction: "desc" });
    } else {
      onSort(null);
    }
  }

  return (
    <th
      data-slot="table-head"
      className={cn(
        "h-10 px-2 text-left align-middle font-medium whitespace-nowrap text-foreground cursor-pointer select-none hover:bg-muted/50 transition-colors",
        className,
      )}
      onClick={handleClick}
    >
      <span className="inline-flex items-center gap-1">
        {label}
        {isActive && direction === "asc" && (
          <ChevronUp className="size-3.5 text-foreground" />
        )}
        {isActive && direction === "desc" && (
          <ChevronDown className="size-3.5 text-foreground" />
        )}
        {!isActive && (
          <span className="size-3.5 opacity-0 group-hover:opacity-30">
            <ChevronUp className="size-3.5" />
          </span>
        )}
      </span>
    </th>
  );
}
