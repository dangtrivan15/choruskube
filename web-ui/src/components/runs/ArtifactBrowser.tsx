import { useState, useEffect } from "react";
import { ChevronDown, ChevronRight, FileText, Loader2 } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import TruncatedText from "@/components/ui/TruncatedText";
import { Separator } from "@/components/ui/separator";
import { useArtifacts } from "@/hooks/useArtifacts";
import ArtifactViewerDialog from "./ArtifactViewerDialog";
import { formatSize } from "@/lib/utils";

interface ArtifactBrowserProps {
  runId: string;
  execId: string;
  filterArtifactNames?: string[];
}

export default function ArtifactBrowser({ runId, execId, filterArtifactNames }: ArtifactBrowserProps) {
  const [expanded, setExpanded] = useState(true);
  const [selectedFile, setSelectedFile] = useState<string | null>(null);
  const [dialogOpen, setDialogOpen] = useState(false);

  const { data: artifacts, isLoading } = useArtifacts(runId, execId);

  // Reset selection when switching nodes
  useEffect(() => {
    setSelectedFile(null);
    setDialogOpen(false);
  }, [execId]);

  function handleFileClick(filename: string) {
    setSelectedFile(filename);
    setDialogOpen(true);
  }

  function handleDialogOpenChange(open: boolean) {
    setDialogOpen(open);
    if (!open) {
      setSelectedFile(null);
    }
  }

  const displayArtifacts =
    filterArtifactNames != null
      ? (artifacts ?? []).filter((a) => filterArtifactNames.includes(a.name))
      : artifacts;

  if (isLoading) {
    return (
      <div className="flex items-center gap-2 text-xs text-muted-foreground">
        <Loader2 className="h-3 w-3 animate-spin" />
        Loading artifacts...
      </div>
    );
  }

  if (!displayArtifacts || displayArtifacts.length === 0) return null;

  return (
    <>
    <Separator />
    <div className="space-y-2" data-testid="artifact-browser">
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
          {displayArtifacts.length}
        </Badge>
      </button>

      {expanded && (
        <ul data-testid="artifact-browser-items" className="space-y-1">
          {displayArtifacts.map((artifact) => (
            <li key={artifact.name}>
              <button
                type="button"
                onClick={() => handleFileClick(artifact.name)}
                className="flex w-full items-center gap-2 rounded-md border px-3 py-2 text-sm transition-colors hover:bg-muted/50"
              >
                <FileText className="h-4 w-4 shrink-0 text-muted-foreground" />
                <TruncatedText>{artifact.name}</TruncatedText>
                <span className="ml-auto text-xs text-muted-foreground">
                  {formatSize(artifact.size)}
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>

    <ArtifactViewerDialog
      runId={runId}
      execId={execId}
      artifacts={displayArtifacts ?? []}
      selectedFile={selectedFile}
      open={dialogOpen}
      onOpenChange={handleDialogOpenChange}
      onFileChange={setSelectedFile}
    />
    </>
  );
}
