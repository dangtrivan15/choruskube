import { useState } from "react";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import RunListTable from "@/components/runs/RunListTable";
import StartRunDialog from "@/components/runs/StartRunDialog";
import Authorized from "@/components/Authorized";
import PageHeader from "@/components/layout/PageHeader";
import PageShell from "@/components/layout/PageShell";

const STATUS_OPTIONS = [
  { value: "all", label: "All statuses" },
  { value: "running", label: "Running" },
  { value: "awaiting_human", label: "Awaiting Human" },
  { value: "awaiting_retry", label: "Awaiting Retry" },
  { value: "completed", label: "Completed" },
  { value: "failed", label: "Failed" },
] as const;

export default function RunListPage() {
  const [statusFilter, setStatusFilter] = useState<string>("all");

  const activeStatus = statusFilter === "all" ? undefined : statusFilter;

  return (
    <PageShell>
      <PageHeader title="Runs" data-testid="run-list-heading">
        <Select value={statusFilter} onValueChange={(v) => setStatusFilter(v ?? "all")}>
          <SelectTrigger data-testid="run-list-status-filter">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {STATUS_OPTIONS.map((opt) => (
              <SelectItem key={opt.value} value={opt.value}>
                {opt.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <Authorized require="canOperate">
          <StartRunDialog />
        </Authorized>
      </PageHeader>

      <RunListTable status={activeStatus} />
    </PageShell>
  );
}
