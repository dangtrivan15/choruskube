import { ArrowUpDown } from "lucide-react";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "./select";
import type { SortParam } from "@/lib/types";

interface SortOption {
  label: string;
  field: string;
  direction: "asc" | "desc";
}

interface SortDropdownProps {
  options: SortOption[];
  currentSort: SortParam | null;
  onSort: (sort: SortParam | null) => void;
}

function sortKey(sort: SortParam): string {
  return `${sort.field},${sort.direction}`;
}

export default function SortDropdown({
  options,
  currentSort,
  onSort,
}: SortDropdownProps) {
  const value = currentSort ? sortKey(currentSort) : "default";

  function handleChange(val: string | null) {
    if (!val || val === "default") {
      onSort(null);
      return;
    }
    const [field, direction] = val.split(",");
    onSort({ field, direction: direction as "asc" | "desc" });
  }

  return (
    <Select value={value} onValueChange={handleChange}>
      {/* Labelled and test-id'd because the trigger's only content is `SelectValue`, which Base UI
          leaves empty until the popup has mounted — so it has no accessible name of its own, and a
          toolbar with a second Select next to it has nothing to tell them apart by. */}
      <SelectTrigger data-testid="sort-dropdown" aria-label="Sort" className="w-auto gap-1.5">
        <ArrowUpDown className="size-3.5" />
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        <SelectItem value="default">Default sort</SelectItem>
        {options.map((opt) => (
          <SelectItem key={sortKey(opt)} value={sortKey(opt)}>
            {opt.label}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  );
}
