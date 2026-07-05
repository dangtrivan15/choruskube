import { useState } from "react";
import { Link, useNavigate } from "react-router";
import { formatDistanceToNow } from "date-fns";
import { useRuns } from "@/hooks/useRuns";
import { useMobileBreakpoint } from "@/hooks/useMobileBreakpoint";
import {
  Table,
  TableHeader,
  TableBody,
  TableHead,
  TableRow,
  TableCell,
} from "@/components/ui/table";
import TruncatedText from "@/components/ui/TruncatedText";
import { Skeleton } from "@/components/ui/skeleton";
import SortableTableHead from "@/components/ui/SortableTableHead";
import Pagination from "@/components/ui/Pagination";
import RunStatusBadge from "./RunStatusBadge";
import ErrorAlert from "@/components/ui/ErrorAlert";
import type { SortParam, PaginationParams } from "@/lib/types";

function SkeletonRows() {
  return (
    <>
      {Array.from({ length: 5 }).map((_, i) => (
        <TableRow key={i}>
          <TableCell><Skeleton className="h-4 w-32" /></TableCell>
          <TableCell><Skeleton className="h-4 w-32" /></TableCell>
          <TableCell><Skeleton className="h-4 w-24" /></TableCell>
          <TableCell><Skeleton className="h-5 w-20" /></TableCell>
          <TableCell><Skeleton className="h-4 w-24" /></TableCell>
        </TableRow>
      ))}
    </>
  );
}

function SkeletonCards() {
  return (
    <div className="space-y-3" data-testid="skeleton-cards">
      {Array.from({ length: 5 }).map((_, i) => (
        <div key={i} className="rounded-lg border p-4">
          <div className="flex items-center justify-between">
            <Skeleton className="h-4 w-32" />
            <Skeleton className="h-5 w-20" />
          </div>
          <Skeleton className="mt-2 h-3 w-24" />
          <Skeleton className="mt-2 h-3 w-20" />
          <Skeleton className="mt-2 h-3 w-20" />
        </div>
      ))}
    </div>
  );
}

interface RunListTableProps {
  status?: string;
}

export default function RunListTable({ status }: RunListTableProps) {
  const [page, setPage] = useState(0);
  const [sort, setSort] = useState<SortParam | null>(null);
  const isMobile = useMobileBreakpoint();
  const navigate = useNavigate();

  const pagination: PaginationParams = { page, size: 20, sort };
  const { data: pageData, isLoading, isError, error } = useRuns(status, undefined, pagination);

  const runs = pageData?.content;

  return (
    <div data-testid="run-list-table">
      {isMobile ? (
        /* ── Mobile card layout ── */
        <div>
          {isLoading && <SkeletonCards />}

          {isError && (
            <ErrorAlert message={`Failed to load runs: ${error?.message ?? "Unknown error"}`} />
          )}

          {runs && runs.length === 0 && (
            <div className="py-12 text-center text-sm text-muted-foreground">
              No runs found.
            </div>
          )}

          {runs && runs.length > 0 && (
            <div className="space-y-3" data-testid="run-card-list">
              {runs.map((run) => (
                <Link
                  key={run.id}
                  to={`/runs/${run.id}`}
                  data-testid="run-card"
                  className="block rounded-lg border bg-card p-4 transition-colors hover:bg-muted/50"
                >
                  <div className="flex items-center justify-between gap-2">
                    <span className="truncate text-sm font-medium">
                      {run.name ?? run.templateName}
                    </span>
                    <RunStatusBadge status={run.status} />
                  </div>
                  <p className="mt-1 truncate text-xs text-muted-foreground">
                    {run.name ? run.templateName : `run-${run.id.slice(0, 8)}`}
                  </p>
                  {run.softwareProject && (
                    <p className="mt-1 truncate text-xs text-muted-foreground">
                      {run.softwareProject.name}
                    </p>
                  )}
                  <p className="mt-1 text-xs text-muted-foreground">
                    {run.startedAt
                      ? formatDistanceToNow(new Date(run.startedAt), { addSuffix: true })
                      : "Not started"}
                  </p>
                </Link>
              ))}
            </div>
          )}
        </div>
      ) : (
        /* ── Desktop table layout ── */
        <Table className="table-fixed">
          <TableHeader>
            <TableRow>
              <SortableTableHead className="w-48" label="Name" field="name" currentSort={sort} onSort={setSort} />
              <TableHead className="w-44">Template</TableHead>
              <TableHead className="w-36">Software Project</TableHead>
              <SortableTableHead className="w-28" label="Status" field="status" currentSort={sort} onSort={setSort} />
              <SortableTableHead className="w-36" label="Started" field="startedAt" currentSort={sort} onSort={setSort} />
            </TableRow>
          </TableHeader>
          <TableBody>
            {isLoading && <SkeletonRows />}

            {isError && (
              <TableRow>
                <TableCell colSpan={5} className="text-center text-destructive">
                  Failed to load runs: {error?.message ?? "Unknown error"}
                </TableCell>
              </TableRow>
            )}

            {runs && runs.length === 0 && (
              <TableRow>
                <TableCell colSpan={5} className="h-24 text-center text-muted-foreground">
                  No runs found.
                </TableCell>
              </TableRow>
            )}

            {runs?.map((run) => (
              <TableRow
                key={run.id}
                data-testid="run-row"
                data-run-id={run.id}
                className="cursor-pointer"
                role="link"
                tabIndex={0}
                onClick={() => navigate(`/runs/${run.id}`)}
                onKeyDown={(e) => {
                  if (e.key === "Enter" || e.key === " ") {
                    e.preventDefault();
                    navigate(`/runs/${run.id}`);
                  }
                }}
              >
                <TableCell className="text-sm">
                  {run.name != null ? (
                    <TruncatedText>{run.name}</TruncatedText>
                  ) : (
                    <span className="text-muted-foreground">-</span>
                  )}
                </TableCell>
                <TableCell className="text-sm">
                  <TruncatedText>{run.templateName}</TruncatedText>
                </TableCell>
                <TableCell className="text-sm">
                  {run.softwareProject
                    ? <TruncatedText>{run.softwareProject.name}</TruncatedText>
                    : <span className="text-muted-foreground">—</span>}
                </TableCell>
                <TableCell>
                  <RunStatusBadge status={run.status} />
                </TableCell>
                <TableCell className="text-sm text-muted-foreground">
                  {run.startedAt
                    ? formatDistanceToNow(new Date(run.startedAt), { addSuffix: true })
                    : "-"}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
      {pageData && (
        <Pagination
          page={pageData.number}
          totalPages={pageData.totalPages}
          onPageChange={setPage}
        />
      )}
    </div>
  );
}
