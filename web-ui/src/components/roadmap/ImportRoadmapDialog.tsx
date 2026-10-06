import { useRef, useState } from "react";
import { FileUp } from "lucide-react";
import { useImportRoadmap } from "@/hooks/useRoadmapImport";
import SoftwareProjectSelect from "@/components/software-projects/SoftwareProjectSelect";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { ApiError, apiErrorMessage } from "@/lib/api";
import type { RoadmapImportResponse } from "@/lib/types";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";

interface Props {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

const EXAMPLE = `{
  "milestones": [{ "key": "q4", "name": "Q4 release" }],
  "epics": [{
    "key": "checkout", "title": "Checkout", "description": "…", "priority": "High", "milestone": "q4",
    "stories": [{ "title": "Cart", "description": "…",
      "tasks": [{ "key": "cart-api", "title": "Cart API", "description": "…" }] }]
  }],
  "dependencies": [{ "blocking": "cart-api", "blocked": "checkout" }]
}`;

type Parsed = { ok: true; document: Record<string, unknown> } | { ok: false; error: string };

function parseDocument(text: string): Parsed {
  let value: unknown;
  try {
    value = JSON.parse(text);
  } catch (e) {
    return { ok: false, error: `Invalid JSON: ${e instanceof Error ? e.message : String(e)}` };
  }
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    return { ok: false, error: "The document must be a JSON object." };
  }
  return { ok: true, document: value as Record<string, unknown> };
}

/** The `errors[]` of a rejected document (a 400 `ValidationResponse`), or null for any other failure. */
function rejectionErrors(err: unknown): string[] | null {
  if (!(err instanceof ApiError) || err.status !== 400) return null;
  const body = err.body;
  if (!body || typeof body !== "object" || !("errors" in body)) return null;
  const errors = (body as { errors?: unknown }).errors;
  return Array.isArray(errors) && errors.every((e) => typeof e === "string") ? errors : null;
}

interface EpicPreview {
  label: string;
  existing: boolean;
  stories: number;
  tasks: number;
}

function asArray(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}

function field(value: unknown, name: string): unknown {
  return value && typeof value === "object" ? (value as Record<string, unknown>)[name] : undefined;
}

/** Only called once the server accepted the document, so missing fields just render as blanks. */
function previewEpics(document: Record<string, unknown>): EpicPreview[] {
  return asArray(document.epics).map((epic) => {
    const existingId = field(epic, "existingId");
    const stories = asArray(field(epic, "stories"));
    const title = field(epic, "title");
    return {
      label: typeof existingId === "string" ? `Existing epic ${existingId}` : String(title ?? ""),
      existing: typeof existingId === "string",
      stories: stories.length,
      tasks: stories.reduce<number>((n, story) => n + asArray(field(story, "tasks")).length, 0),
    };
  });
}

function plural(n: number, singular: string, pluralForm = `${singular}s`): string {
  return `${n} ${n === 1 ? singular : pluralForm}`;
}

/**
 * Imports a roadmap document — the same JSON an agent installs with `propose-roadmap` — into one
 * software project. Import stays disabled until the exact text and project on screen have passed
 * a dry run, so what the preview shows is what gets created.
 */
export default function ImportRoadmapDialog({ open, onOpenChange }: Props) {
  const [text, setText] = useState("");
  const [softwareProjectId, setSoftwareProjectId] = useState("");
  const [preview, setPreview] = useState<RoadmapImportResponse | null>(null);
  const [errors, setErrors] = useState<string[] | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  const importRoadmap = useImportRoadmap();

  const parsed = text.trim() ? parseDocument(text) : null;

  function clearOutcome() {
    setPreview(null);
    setErrors(null);
    setFailure(null);
  }

  function handleTextChange(next: string) {
    setText(next);
    clearOutcome();
  }

  function handleProjectChange(id: string) {
    setSoftwareProjectId(id);
    clearOutcome();
  }

  async function handleFile(file: File | undefined) {
    if (!file) return;
    handleTextChange(await file.text());
  }

  function submit(dryRun: boolean) {
    if (!parsed?.ok || !softwareProjectId) return;
    clearOutcome();
    importRoadmap.mutate(
      { softwareProjectId, document: parsed.document, dryRun },
      {
        onSuccess: (result) => {
          if (result.dryRun) {
            setPreview(result);
          } else {
            handleOpenChange(false);
          }
        },
        onError: (err) => {
          const rejected = rejectionErrors(err);
          if (rejected) {
            setErrors(rejected);
          } else {
            setFailure(apiErrorMessage(err, dryRun ? "Failed to validate the document" : "Failed to import"));
          }
        },
      }
    );
  }

  function handleOpenChange(isOpen: boolean) {
    onOpenChange(isOpen);
    if (!isOpen) {
      setText("");
      setSoftwareProjectId("");
      clearOutcome();
      importRoadmap.reset();
    }
  }

  const canSubmit = !!parsed?.ok && !!softwareProjectId && !importRoadmap.isPending;
  const pendingDryRun = importRoadmap.isPending ? importRoadmap.variables?.dryRun : undefined;

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent size="2xl" data-testid="import-roadmap-dialog">
        <DialogHeader>
          <DialogTitle>Import roadmap JSON</DialogTitle>
          <DialogDescription>
            Paste or load a roadmap document — the same format agents propose. Validate it first;
            the import creates everything in one go, or nothing if any part fails.
          </DialogDescription>
        </DialogHeader>

        <div className="min-h-0 flex-1 overflow-y-auto py-2 flex flex-col gap-4 -mx-4 px-4">
          <div className="flex flex-col gap-2">
            <label className="text-sm font-medium">
              Software Project <span className="text-destructive">*</span>
            </label>
            <SoftwareProjectSelect
              value={softwareProjectId}
              onChange={handleProjectChange}
              testId="import-roadmap-project"
            />
          </div>

          <div className="flex flex-col gap-1">
            <div className="flex items-center justify-between">
              <label htmlFor="import-roadmap-json" className="text-sm font-medium">
                Document <span className="text-destructive">*</span>
              </label>
              <Button
                variant="ghost"
                size="sm"
                data-testid="import-roadmap-load-file"
                onClick={() => fileInput.current?.click()}
              >
                <FileUp className="size-4" />
                Load file
              </Button>
              <input
                ref={fileInput}
                type="file"
                accept=".json,application/json"
                className="hidden"
                data-testid="import-roadmap-file"
                onChange={(e) => {
                  void handleFile(e.target.files?.[0]);
                  e.target.value = "";
                }}
              />
            </div>
            <Textarea
              id="import-roadmap-json"
              data-testid="import-roadmap-json"
              value={text}
              onChange={(e) => handleTextChange(e.target.value)}
              placeholder={EXAMPLE}
              rows={12}
              className="font-mono text-xs"
              spellCheck={false}
            />
            {parsed && !parsed.ok && (
              <p data-testid="import-roadmap-parse-error" className="text-sm text-destructive">
                {parsed.error}
              </p>
            )}
          </div>

          {errors && (
            <div data-testid="import-roadmap-errors" className="rounded-lg border border-destructive/40 p-3">
              <p className="text-sm font-medium text-destructive">
                {plural(errors.length, "problem")} — nothing was imported
              </p>
              <ul className="mt-2 list-disc pl-5 text-sm">
                {errors.map((error, i) => (
                  <li key={i} className="break-words">
                    {error}
                  </li>
                ))}
              </ul>
            </div>
          )}

          {preview && parsed?.ok && (
            <div data-testid="import-roadmap-preview" className="rounded-lg border p-3">
              <p className="text-sm font-medium">
                Ready to import: {plural(preview.newEpics, "new epic")},{" "}
                {plural(preview.newStories, "story", "stories")}, {plural(preview.newTasks, "task")},{" "}
                {plural(preview.milestones, "milestone")},{" "}
                {plural(preview.dependencies, "dependency", "dependencies")}
                {preview.existingItems > 0 && ` — attaching to ${plural(preview.existingItems, "existing item")}`}
              </p>
              <ul className="mt-2 flex flex-col gap-1 text-sm">
                {previewEpics(parsed.document).map((epic, i) => (
                  <li key={i} className="flex items-baseline gap-2">
                    <span className={epic.existing ? "text-muted-foreground" : "font-medium"}>{epic.label}</span>
                    <span className="text-xs text-muted-foreground">
                      {plural(epic.stories, "story", "stories")}, {plural(epic.tasks, "task")}
                    </span>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>

        <DialogFooter>
          {failure && <p className="text-sm text-destructive mr-auto">{failure}</p>}
          <Button
            variant="outline"
            data-testid="import-roadmap-validate"
            onClick={() => submit(true)}
            disabled={!canSubmit}
          >
            {pendingDryRun === true ? "Validating..." : "Validate"}
          </Button>
          <Button data-testid="import-roadmap-submit" onClick={() => submit(false)} disabled={!canSubmit || !preview}>
            {pendingDryRun === false ? "Importing..." : "Import"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
