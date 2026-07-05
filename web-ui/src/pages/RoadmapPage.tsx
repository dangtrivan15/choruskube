import { useState } from "react";
import { Link } from "react-router";
import { formatDistanceToNow } from "date-fns";
import { Plus, Pencil, Trash2, Play, ExternalLink, CheckCircle2, ArrowLeft, GitBranch, Layers } from "lucide-react";
import { useMobileBreakpoint } from "@/hooks/useMobileBreakpoint";
import Authorized from "@/components/Authorized";
import {
  useFeatureProposals,
  useDeleteFeatureProposal,
  useStartFeatureProposal,
  useRollOutFeatureProposal,
} from "@/hooks/useFeatureProposals";
import { useFeatureProposalSubscription } from "@/hooks/useFeatureProposalSubscription";
import type { FeatureProposalResponse, SortParam, PaginationParams } from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import MarkdownViewer from "@/components/ui/MarkdownViewer";
import TruncatedText from "@/components/ui/TruncatedText";
import Pagination from "@/components/ui/Pagination";
import SortDropdown from "@/components/ui/SortDropdown";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import CreateProposalDialog from "@/components/roadmap/CreateProposalDialog";
import EditProposalDialog from "@/components/roadmap/EditProposalDialog";
import PullRequestLinks from "@/components/runs/PullRequestLinks";
import { useRun } from "@/hooks/useRuns";
import PageHeader from "@/components/layout/PageHeader";

const SORT_OPTIONS = [
  { label: "Newest first", field: "createdAt", direction: "desc" as const },
  { label: "Oldest first", field: "createdAt", direction: "asc" as const },
  { label: "Title A-Z", field: "title", direction: "asc" as const },
  { label: "Title Z-A", field: "title", direction: "desc" as const },
];

function statusBadge(status: string) {
  switch (status) {
    case "backlog":
      return <Badge variant="outline">backlog</Badge>;
    case "in_progress":
      return <Badge variant="secondary">in progress</Badge>;
    case "rolled_out":
      return <Badge variant="default">rolled out</Badge>;
    default:
      return <Badge variant="outline">{status}</Badge>;
  }
}

export default function RoadmapPage() {
  const [page, setPage] = useState(0);
  const [sort, setSort] = useState<SortParam | null>(null);
  const isMobile = useMobileBreakpoint();

  const pagination: PaginationParams = { page, size: 20, sort };
  const { data: pageData, isLoading } = useFeatureProposals(undefined, undefined, pagination);
  const proposals = pageData?.content;
  useFeatureProposalSubscription();
  const deleteProposal = useDeleteFeatureProposal();
  const startProposal = useStartFeatureProposal();
  const rollOutProposal = useRollOutFeatureProposal();

  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [editingProposal, setEditingProposal] = useState<FeatureProposalResponse | null>(null);
  const [deletingProposal, setDeletingProposal] = useState<FeatureProposalResponse | null>(null);
  const [startingProposal, setStartingProposal] = useState<FeatureProposalResponse | null>(null);

  const selected = proposals?.find((p) => p.id === selectedId) ?? null;
  // Fetch the linked run when present so the detail view can render PR links.
  const runQuery = useRun(selected?.workflowRunId ?? "");
  const runPullRequests = runQuery.data?.pullRequests ?? [];

  // Auto-select first proposal if none selected (desktop only)
  if (!isMobile && !selected && proposals && proposals.length > 0 && !selectedId) {
    setSelectedId(proposals[0].id);
  }

  function handleDelete() {
    if (!deletingProposal) return;
    deleteProposal.mutate(deletingProposal.id, {
      onSuccess: () => {
        setDeletingProposal(null);
        if (selectedId === deletingProposal.id) setSelectedId(null);
      },
    });
  }

  function handleStart() {
    if (!startingProposal) return;
    startProposal.mutate(startingProposal.id, {
      onSuccess: () => setStartingProposal(null),
    });
  }

  function handleRollOut() {
    if (!selected) return;
    rollOutProposal.mutate(selected.id);
  }

  const canRestart =
    selected?.status === "in_progress" &&
    selected.workflowRunStatus != null &&
    (selected.workflowRunStatus === "failed" || selected.workflowRunStatus === "cancelled");

  const canRollOut =
    selected?.status === "in_progress" &&
    selected.workflowRunStatus === "completed";

  // On mobile, show detail or list (not both)
  const showMobileDetail = isMobile && selected !== null;

  // Shared detail view component
  const detailView = selected && (
    <div className="w-full min-w-0 space-y-6 max-w-2xl">
      {/* Back button on mobile */}
      {isMobile && (
        <button
          data-testid="proposal-back-button"
          onClick={() => setSelectedId(null)}
          className="flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground"
        >
          <ArrowLeft className="size-4" />
          Back
        </button>
      )}

      {/* Header */}
      <div className="flex min-w-0 items-start justify-between gap-4">
        <div className="flex min-w-0 flex-col gap-2">
          <h2 data-testid="proposal-detail-title" className="text-xl font-semibold break-words">{selected.title}</h2>
          <div className="flex flex-wrap items-center gap-2">
            <span data-testid="proposal-detail-status">{statusBadge(selected.status)}</span>
            <span className="text-sm text-muted-foreground">
              {formatDistanceToNow(new Date(selected.createdAt), {
                addSuffix: true,
              })}
            </span>
          </div>
          {(() => {
            const sp = selected.softwareProject;
            const Icon = sp.type === "repo_group" ? Layers : GitBranch;
            const projectLabel = sp.name;
            return (
              <div
                data-testid="proposal-software-project"
                className="flex flex-wrap items-center gap-2"
              >
                <span
                  data-testid="proposal-software-project-chip"
                  title={projectLabel}
                  className="inline-flex min-w-0 max-w-full items-center gap-1.5 rounded-md border bg-muted/40 px-2 py-1 text-xs font-medium"
                >
                  <Icon className="size-3.5 shrink-0" />
                  <span className="min-w-0 flex-1 truncate">{projectLabel}</span>
                </span>
                {(selected.repos ?? []).map((r) => (
                  <a
                    key={r.id}
                    data-testid="proposal-repo-pill"
                    href={r.url}
                    target="_blank"
                    rel="noopener noreferrer"
                    title={r.name}
                    className="inline-block max-w-full truncate rounded-full border px-2 py-0.5 text-xs text-muted-foreground hover:text-foreground hover:bg-muted/50"
                  >
                    {r.name}
                  </a>
                ))}
              </div>
            );
          })()}
          {selected.workflowRunId && runPullRequests.length > 0 && (
            <PullRequestLinks pullRequests={runPullRequests} />
          )}
        </div>
      </div>

      {/* Description */}
      <div data-testid="proposal-detail-description">
        <h3 className="text-sm font-medium text-muted-foreground mb-2">
          Description
        </h3>
        <MarkdownViewer content={selected.description} maxHeight="max-h-72" />
      </div>

      {/* Motivation */}
      {selected.motivation && (
        <div>
          <h3 className="text-sm font-medium text-muted-foreground mb-2">
            Motivation
          </h3>
          <MarkdownViewer content={selected.motivation} maxHeight="max-h-48" />
        </div>
      )}

      {/* Workflow Run link */}
      {selected.workflowRunId && (
        <div>
          <h3 className="text-sm font-medium text-muted-foreground mb-2">
            Workflow Run
          </h3>
          <Link
            to={`/runs/${selected.workflowRunId}`}
            className="inline-flex items-center gap-1.5 text-sm text-primary hover:underline"
          >
            <ExternalLink className="size-3.5" />
            View Run
            {selected.workflowRunStatus && (
              <Badge variant="outline" className="ml-1">
                {selected.workflowRunStatus}
              </Badge>
            )}
          </Link>
        </div>
      )}

      {/* Actions */}
      <div className="flex flex-wrap gap-2 pt-2 border-t">
        {selected.status === "backlog" && (
          <>
            <Authorized require="canAdmin">
              <Button
                data-testid="proposal-edit-button"
                variant="ghost"
                size="sm"
                onClick={() => setEditingProposal(selected)}
              >
                <Pencil className="size-4" />
                Edit
              </Button>
            </Authorized>
            <Authorized require="canOperate">
              <Button
                data-testid="proposal-start-button"
                size="sm"
                onClick={() => setStartingProposal(selected)}
              >
                <Play className="size-4" />
                Start
              </Button>
            </Authorized>
            <Authorized require="canAdmin">
              <Button
                data-testid="proposal-delete-button"
                variant="ghost"
                size="sm"
                onClick={() => setDeletingProposal(selected)}
              >
                <Trash2 className="size-4" />
                Delete
              </Button>
            </Authorized>
          </>
        )}
        {selected.status === "in_progress" && (
          <Authorized require="canOperate">
            {canRestart && (
              <Button
                data-testid="proposal-restart-button"
                size="sm"
                onClick={() => setStartingProposal(selected)}
              >
                <Play className="size-4" />
                Restart
              </Button>
            )}
            <Button
              data-testid="proposal-rollout-button"
              size="sm"
              onClick={handleRollOut}
              disabled={!canRollOut || rollOutProposal.isPending}
            >
              <CheckCircle2 className="size-4" />
              {rollOutProposal.isPending ? "Rolling out..." : "Roll Out"}
            </Button>
          </Authorized>
        )}
      </div>
      {rollOutProposal.isError && (
        <p className="text-sm text-destructive">Failed to roll out.</p>
      )}
    </div>
  );

  return (
    <div className="flex h-full min-w-0 flex-col md:flex-row">
      {/* Left panel: proposal list (hidden on mobile when detail is shown) */}
      {!showMobileDetail && (
        <div className="w-full min-w-0 shrink-0 border-r flex flex-col md:w-80">
          <div className="p-4 border-b">
            <PageHeader title="Roadmap" data-testid="roadmap-heading">
              <SortDropdown options={SORT_OPTIONS} currentSort={sort} onSort={setSort} />
              <Authorized require="canOperate">
                <Button data-testid="new-proposal-button" size="sm" onClick={() => setCreateOpen(true)}>
                  <Plus className="size-4" />
                  New
                </Button>
              </Authorized>
            </PageHeader>
          </div>
          <div data-testid="proposal-list" className="flex-1 overflow-y-auto">
            {isLoading &&
              Array.from({ length: 4 }).map((_, i) => (
                <div key={i} className="p-3 border-b">
                  <Skeleton className="h-4 w-3/4 mb-2" />
                  <Skeleton className="h-3 w-1/3" />
                </div>
              ))}
            {proposals?.map((p) => (
              <button
                key={p.id}
                data-testid="proposal-item"
                onClick={() => setSelectedId(p.id)}
                className={`w-full text-left p-3 border-b transition-colors hover:bg-muted/50 ${
                  selectedId === p.id ? "bg-muted" : ""
                }`}
              >
                <TruncatedText as="div" className="font-medium text-sm">{p.title}</TruncatedText>
                <div className="mt-1">{statusBadge(p.status)}</div>
              </button>
            ))}
            {proposals && proposals.length === 0 && (
              <div className="p-6 text-center text-muted-foreground text-sm">
                No proposals yet. Click &ldquo;New&rdquo; to create one.
              </div>
            )}
          </div>
          {pageData && (
            <div className="border-t p-2">
              <Pagination
                page={pageData.number}
                totalPages={pageData.totalPages}
                onPageChange={setPage}
              />
            </div>
          )}
        </div>
      )}

      {/* Right panel / mobile detail view */}
      {isMobile ? (
        showMobileDetail && (
          <div className="flex-1 min-w-0 overflow-y-auto p-4">
            {detailView}
          </div>
        )
      ) : (
        <div className="flex-1 min-w-0 overflow-y-auto p-6">
          {!selected && !isLoading && (
            <div className="flex items-center justify-center h-full text-muted-foreground">
              Select a proposal to view details
            </div>
          )}
          {detailView}
        </div>
      )}

      {/* Dialogs */}
      <CreateProposalDialog open={createOpen} onOpenChange={setCreateOpen} />

      <EditProposalDialog
        proposal={editingProposal}
        open={editingProposal !== null}
        onOpenChange={(open) => {
          if (!open) setEditingProposal(null);
        }}
      />

      {/* Delete confirmation */}
      <Dialog
        open={deletingProposal !== null}
        onOpenChange={(open) => {
          if (!open) {
            setDeletingProposal(null);
            deleteProposal.reset();
          }
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Delete Proposal</DialogTitle>
            <DialogDescription>
              Are you sure you want to delete &ldquo;{deletingProposal?.title}&rdquo;?
              This action cannot be undone.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            {deleteProposal.isError && (
              <p className="text-sm text-destructive mr-auto">Failed to delete.</p>
            )}
            <Button
              variant="ghost"
              onClick={() => {
                setDeletingProposal(null);
                deleteProposal.reset();
              }}
            >
              Cancel
            </Button>
            <Button
              data-testid="delete-proposal-confirm"
              variant="destructive"
              onClick={handleDelete}
              disabled={deleteProposal.isPending}
            >
              {deleteProposal.isPending ? "Deleting..." : "Delete"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Start confirmation */}
      <Dialog
        open={startingProposal !== null}
        onOpenChange={(open) => {
          if (!open) {
            setStartingProposal(null);
            startProposal.reset();
          }
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Start Feature Development</DialogTitle>
            <DialogDescription>
              This will create a workflow run for &ldquo;{startingProposal?.title}&rdquo;
              using the selected repository. Continue?
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            {startProposal.isError && (
              <p className="text-sm text-destructive mr-auto">
                Failed to start. Check that the repository is valid.
              </p>
            )}
            <Button
              variant="ghost"
              onClick={() => {
                setStartingProposal(null);
                startProposal.reset();
              }}
            >
              Cancel
            </Button>
            <Button data-testid="start-proposal-confirm" onClick={handleStart} disabled={startProposal.isPending}>
              {startProposal.isPending ? "Starting..." : "Start Run"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
