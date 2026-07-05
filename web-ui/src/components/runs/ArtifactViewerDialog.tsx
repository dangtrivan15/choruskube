import { useState } from "react";
import { FileText, Loader2, AlertCircle, ImageOff } from "lucide-react";
import MarkdownViewer from "@/components/ui/MarkdownViewer";
import { useArtifactContent } from "@/hooks/useArtifacts";
import { artifactUrl } from "@/lib/api";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
} from "@/components/ui/dialog";
import type { ArtifactEntry } from "@/lib/types";

interface ArtifactViewerDialogProps {
  runId: string;
  execId: string;
  artifacts: ArtifactEntry[];
  selectedFile: string | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onFileChange: (filename: string) => void;
}

const IMAGE_EXTENSIONS = new Set([
  ".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp", ".ico", ".svg",
]);

const BINARY_EXTENSIONS = new Set([
  ".pdf", ".zip", ".gz", ".tar", ".7z", ".rar",
  ".exe", ".dll", ".so", ".dylib",
  ".wasm", ".bin",
]);

function isImageFile(filename: string): boolean {
  const lower = filename.toLowerCase();
  return [...IMAGE_EXTENSIONS].some((ext) => lower.endsWith(ext));
}

function isBinaryFile(filename: string): boolean {
  const lower = filename.toLowerCase();
  return (
    [...BINARY_EXTENSIONS].some((ext) => lower.endsWith(ext)) ||
    isImageFile(lower)
  );
}

export default function ArtifactViewerDialog({
  runId,
  execId,
  artifacts,
  selectedFile,
  open,
  onOpenChange,
  onFileChange,
}: ArtifactViewerDialogProps) {
  const { data: content, isLoading, isError } = useArtifactContent(
    runId,
    execId,
    selectedFile && !isBinaryFile(selectedFile) ? selectedFile : null
  );

  const [imgError, setImgError] = useState<string | null>(null);

  const renderContent = () => {
    if (!selectedFile) return null;

    // Image files → inline <img> preview
    if (isImageFile(selectedFile)) {
      const src = artifactUrl(runId, execId, selectedFile);
      if (imgError === selectedFile) {
        return (
          <div className="flex flex-col items-center justify-center gap-2 rounded-md border bg-muted/30 p-8 text-sm text-muted-foreground">
            <ImageOff className="h-8 w-8" />
            <p>Failed to load image</p>
            <p className="text-xs">{selectedFile}</p>
          </div>
        );
      }
      return (
        <div className="flex items-center justify-center p-4">
          <img
            src={src}
            alt={selectedFile}
            className="max-h-[60vh] max-w-full rounded border object-contain"
            onError={() => setImgError(selectedFile)}
          />
        </div>
      );
    }

    // Non-image binary files → placeholder
    if (isBinaryFile(selectedFile)) {
      return (
        <div className="flex flex-col items-center justify-center gap-2 rounded-md border bg-muted/30 p-8 text-sm text-muted-foreground">
          <FileText className="h-8 w-8" />
          <p>Binary file &mdash; preview not available</p>
          <p className="text-xs">{selectedFile}</p>
        </div>
      );
    }

    // Loading / error states for text content
    if (isLoading) {
      return (
        <div className="flex items-center gap-2 rounded-md border bg-muted/30 p-3 text-xs text-muted-foreground">
          <Loader2 className="h-3 w-3 animate-spin" />
          Loading...
        </div>
      );
    }

    if (isError) {
      return (
        <div className="flex items-center gap-2 rounded-md border border-destructive/30 bg-destructive/5 p-3 text-xs text-destructive">
          <AlertCircle className="h-3 w-3" />
          Failed to load file content.
        </div>
      );
    }

    // Markdown files
    if (selectedFile.endsWith(".md") && content) {
      return (
        <MarkdownViewer
          content={content}
          maxHeight=""
          artifactContext={{ runId, execId }}
        />
      );
    }

    // Plain text fallback
    return (
      <pre className="overflow-auto rounded-md border bg-muted/30 p-3 text-xs whitespace-pre-wrap">
        {content}
      </pre>
    );
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent size="3xl" data-testid="artifact-viewer-dialog">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <FileText className="h-4 w-4" />
            {selectedFile ?? "Artifact Viewer"}
          </DialogTitle>
          <DialogDescription>
            Viewing artifact file contents
          </DialogDescription>
        </DialogHeader>

        {/* File switcher pills */}
        {artifacts.length > 1 && (
          <div className="flex flex-wrap gap-1.5">
            {artifacts.map((artifact) => (
              <button
                key={artifact.name}
                type="button"
                onClick={() => onFileChange(artifact.name)}
                className={`rounded-full px-3 py-1 text-xs font-medium transition-colors ${
                  selectedFile === artifact.name
                    ? "bg-primary text-primary-foreground"
                    : "bg-muted text-muted-foreground hover:bg-muted/80 hover:text-foreground"
                }`}
              >
                {artifact.name}
              </button>
            ))}
          </div>
        )}

        {/* Content area */}
        <div className="min-h-0 flex-1 overflow-auto">
          {renderContent()}
        </div>
      </DialogContent>
    </Dialog>
  );
}
