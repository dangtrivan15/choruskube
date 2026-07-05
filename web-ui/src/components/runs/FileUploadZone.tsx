import { useCallback, useRef, useState } from "react";
import { Paperclip, X } from "lucide-react";

interface FileUploadZoneProps {
  onFilesChange: (files: File[]) => void;
  accept?: string;      // e.g. "image/*,.pdf"
  maxFiles?: number;    // default: 5
  maxSizeMB?: number;   // per-file limit in MB, default: 25
  disabled?: boolean;   // disables interaction during submission
}

export default function FileUploadZone({
  onFilesChange,
  accept,
  maxFiles = 5,
  maxSizeMB = 25,
  disabled = false,
}: FileUploadZoneProps) {
  const [files, setFiles] = useState<File[]>([]);
  const [dragOver, setDragOver] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  const addFiles = useCallback((incoming: FileList | null) => {
    if (!incoming) return;
    const valid: File[] = [];
    for (const f of Array.from(incoming)) {
      if (f.size > maxSizeMB * 1024 * 1024) continue;
      if (files.length + valid.length >= maxFiles) break;
      valid.push(f);
    }
    const next = [...files, ...valid];
    setFiles(next);
    onFilesChange(next);
  }, [files, maxFiles, maxSizeMB, onFilesChange]);

  const removeFile = (idx: number) => {
    const next = files.filter((_, i) => i !== idx);
    setFiles(next);
    onFilesChange(next);
  };

  return (
    <div className="space-y-2">
      <div
        data-testid="file-upload-zone"
        onDragOver={(e) => { e.preventDefault(); if (!disabled) setDragOver(true); }}
        onDragLeave={() => setDragOver(false)}
        onDrop={(e) => {
          e.preventDefault();
          setDragOver(false);
          if (!disabled) addFiles(e.dataTransfer.files);
        }}
        onClick={() => !disabled && inputRef.current?.click()}
        className={[
          "flex cursor-pointer items-center justify-center rounded-md border-2 border-dashed p-4 text-sm text-muted-foreground transition-colors",
          dragOver ? "border-primary bg-primary/5" : "border-border",
          disabled ? "cursor-not-allowed opacity-50" : "hover:border-primary/60",
        ].join(" ")}
      >
        <Paperclip className="mr-2 h-4 w-4" />
        <span>Attach files — drag & drop or click to browse</span>
        <input
          ref={inputRef}
          type="file"
          multiple
          accept={accept}
          disabled={disabled}
          className="hidden"
          onChange={(e) => addFiles(e.target.files)}
        />
      </div>
      {files.length > 0 && (
        <ul className="space-y-1">
          {files.map((f, i) => (
            <li key={`${f.name}-${f.size}`} className="flex items-center justify-between text-xs">
              <span className="truncate">{f.name}</span>
              <button
                type="button"
                onClick={() => removeFile(i)}
                disabled={disabled}
                aria-label={`Remove ${f.name}`}
                className="ml-2 text-muted-foreground hover:text-foreground"
              >
                <X className="h-3.5 w-3.5" />
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
