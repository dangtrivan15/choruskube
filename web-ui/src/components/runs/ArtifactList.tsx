import { useState } from "react";
import { ChevronDown, ChevronRight, FileText, Loader2 } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import TruncatedText from "@/components/ui/TruncatedText";
import { useArtifactsForGroups } from "@/hooks/useArtifacts";
import ArtifactViewerDialog from "./ArtifactViewerDialog";
import type { ResolvedArtifactGroup, ArtifactEntry } from "@/lib/types";
import { formatSize } from "@/lib/utils";

interface ArtifactListProps {
  runId: string;
  groups: ResolvedArtifactGroup[];
}

interface FlatArtifact {
  nodeLabel: string;
  execId: string;
  name: string;
  size: number;
}

export default function ArtifactList({ runId, groups }: ArtifactListProps) {
  const [expanded, setExpanded] = useState(true);
  const [selected, setSelected] = useState<{ execId: string; filename: string } | null>(null);
  const [dialogOpen, setDialogOpen] = useState(false);

  const resolvedGroups = groups.filter((g) => g.nodeExecutionId != null);
  const queries = useArtifactsForGroups(runId, resolvedGroups);

  const isAnyLoading = queries.some((q) => q.isLoading);

  if (isAnyLoading) {
    return (
      <div className="flex items-center gap-2 text-xs text-muted-foreground">
        <Loader2 className="h-3 w-3 animate-spin" />
        Loading artifacts...
      </div>
    );
  }

  const flatArtifacts: FlatArtifact[] = [];
  resolvedGroups.forEach((g, i) => {
    const entries: ArtifactEntry[] = queries[i]?.data ?? [];
    const filtered =
      g.artifacts.length > 0
        ? entries.filter((a) => g.artifacts.some((declared) => declared.name === a.name))
        : entries;
    filtered.forEach((a) => {
      flatArtifacts.push({
        nodeLabel: g.nodeLabel,
        execId: g.nodeExecutionId!,
        name: a.name,
        size: a.size,
      });
    });
  });

  if (flatArtifacts.length === 0) return null;

  const dialogArtifacts: ArtifactEntry[] = selected
    ? flatArtifacts
        .filter((a) => a.execId === selected.execId)
        .map((a) => ({ name: a.name, size: a.size, lastModified: "" })) // lastModified not rendered by dialog
    : [];

  return (
    <div data-testid="artifact-list" className="space-y-2">
      <button
        type="button"
        onClick={() => setExpanded(!expanded)}
        className="flex w-full items-center gap-1.5 text-xs font-medium uppercase tracking-wide text-muted-foreground hover:text-foreground/80"
      >
        {expanded ? (
          <ChevronDown className="h-4 w-4" />
        ) : (
          <ChevronRight className="h-4 w-4" />
        )}
        Artifacts
        <Badge variant="secondary" className="ml-1 text-xs">
          {flatArtifacts.length}
        </Badge>
      </button>

      {expanded && (
        <ul data-testid="artifact-list-items" className="space-y-1">
          {flatArtifacts.map((artifact, idx) => (
            <li key={`${artifact.execId}-${artifact.name}-${idx}`}>
              <button
                type="button"
                onClick={() => {
                  setSelected({ execId: artifact.execId, filename: artifact.name });
                  setDialogOpen(true);
                }}
                className="flex w-full items-center gap-2 rounded-md border px-3 py-2 text-sm transition-colors hover:bg-muted/50"
              >
                <FileText className="h-4 w-4 shrink-0 text-muted-foreground" />
                <TruncatedText>{artifact.nodeLabel}/{artifact.name}</TruncatedText>
                <span className="ml-auto text-xs text-muted-foreground">
                  {formatSize(artifact.size)}
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}

      <ArtifactViewerDialog
        runId={runId}
        execId={selected?.execId ?? ""}
        artifacts={dialogArtifacts}
        selectedFile={selected?.filename ?? null}
        open={dialogOpen}
        onOpenChange={(open) => {
          setDialogOpen(open);
          if (!open) setSelected(null);
        }}
        onFileChange={(filename) => {
          if (selected) setSelected({ ...selected, filename });
        }}
      />
    </div>
  );
}
