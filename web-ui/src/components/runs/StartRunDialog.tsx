import { useState, useRef } from "react";
import FileUploadZone from "./FileUploadZone";
import { Play } from "lucide-react";
import { useTemplates } from "@/hooks/useTemplates";
import { useGitRepos } from "@/hooks/useGitRepos";
import { useStartRun } from "@/hooks/useRuns";
import { inferRunName } from "@/lib/inferRunName";
import { repoDisplayName } from "@/lib/utils";
import SoftwareProjectSelect from "@/components/software-projects/SoftwareProjectSelect";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";

function GitRepoSelect({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  const { data: reposPage } = useGitRepos({ size: 100 });
  const repos = reposPage?.content;
  const selected = repos?.find((r) => r.id === value);
  return (
    <Select value={value} onValueChange={(v) => onChange(v ?? "")}>
      <SelectTrigger className="w-full">
        <SelectValue placeholder="Select a repository...">
          {selected ? repoDisplayName(selected.url) : undefined}
        </SelectValue>
      </SelectTrigger>
      <SelectContent>
        {repos?.map((r) => (
          <SelectItem key={r.id} value={r.id}>
            {repoDisplayName(r.url)}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  );
}

export default function StartRunDialog() {
  const [open, setOpen] = useState(false);
  const [selectedTemplateId, setSelectedTemplateId] = useState<string>("");
  const [inputValues, setInputValues] = useState<Record<string, unknown>>({});
  const [runName, setRunName] = useState("");
  const [inputFiles, setInputFiles] = useState<File[]>([]);
  const nameManuallyEdited = useRef(false);

  const { data: templatesPage, isLoading: templatesLoading } = useTemplates(true, undefined, { size: 100 });
  const templates = templatesPage?.content;
  const startRun = useStartRun();

  const selectedTemplate = templates?.find((t) => t.id === selectedTemplateId);
  const schema = selectedTemplate?.inputSchema ?? [];

  const hasRequiredMissing = schema.some((field) => {
    const val = inputValues[field.name];
    return field.required && (!val || !(val as string).trim?.());
  });

  function handleTemplateChange(v: string | null) {
    setSelectedTemplateId(v ?? "");
    const template = templates?.find((t) => t.id === v);
    const defaults: Record<string, unknown> = {};
    for (const field of template?.inputSchema ?? []) {
      if (field.default !== undefined && field.default !== null) {
        defaults[field.name] = field.default;
      }
    }
    setInputValues(defaults);
    nameManuallyEdited.current = false;

    // Auto-infer name from defaults
    const newSchema = template?.inputSchema ?? [];
    setRunName(inferRunName(newSchema, defaults as Record<string, string>));
  }

  function handleInputChange(name: string, value: unknown) {
    const nextValues = { ...inputValues, [name]: value };
    setInputValues(nextValues);

    // Auto-infer name only if user hasn't manually edited it
    if (!nameManuallyEdited.current) {
      setRunName(inferRunName(schema, nextValues as Record<string, string>));
    }
  }

  function handleNameChange(value: string) {
    nameManuallyEdited.current = true;
    setRunName(value);
  }

  function handleStart() {
    if (!selectedTemplateId) return;
    const trimmedName = runName.trim();
    startRun.mutate(
      {
        graphTemplateId: selectedTemplateId,
        inputs: inputValues,
        name: trimmedName || undefined,
        inputFiles,
      },
      {
        onSuccess: () => {
          setOpen(false);
          setSelectedTemplateId("");
          setInputValues({});
          setRunName("");
          nameManuallyEdited.current = false;
        },
      }
    );
  }

  function handleOpenChange(isOpen: boolean) {
    setOpen(isOpen);
    if (!isOpen) {
      setSelectedTemplateId("");
      setInputValues({});
      setRunName("");
      nameManuallyEdited.current = false;
    }
  }

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogTrigger render={<Button data-testid="start-run-button" />}>
        <Play className="size-4" />
        Start Run
      </DialogTrigger>
      <DialogContent size="md">
        <DialogHeader>
          <DialogTitle>Start a New Run</DialogTitle>
          <DialogDescription>
            Select a graph template to start a new orchestration run.
          </DialogDescription>
        </DialogHeader>

        <div className="min-h-0 flex-1 overflow-y-auto py-2 flex flex-col gap-4 -mx-4 px-4">
          <Select
            value={selectedTemplateId}
            onValueChange={handleTemplateChange}
          >
            <SelectTrigger data-testid="start-run-template-select" className="w-full">
              <SelectValue placeholder="Select a template...">
                {selectedTemplate
                  ? `${selectedTemplate.name} v${selectedTemplate.version}`
                  : undefined}
              </SelectValue>
            </SelectTrigger>
            <SelectContent>
              {templatesLoading && (
                <SelectItem value="" disabled>
                  Loading templates...
                </SelectItem>
              )}
              {templates?.map((t) => (
                <SelectItem key={t.id} value={t.id}>
                  {t.name} <span className="text-muted-foreground">v{t.version}</span>
                </SelectItem>
              ))}
              {templates && templates.length === 0 && (
                <SelectItem value="" disabled>
                  No templates available
                </SelectItem>
              )}
            </SelectContent>
          </Select>

          {selectedTemplateId && (
            <div className="flex flex-col gap-1">
              <label htmlFor="run-name" className="text-sm font-medium">
                Run name
              </label>
              <Input
                id="run-name"
                data-testid="start-run-name-input"
                type="text"
                value={runName}
                onChange={(e) => handleNameChange(e.target.value)}
                placeholder="Auto-generated from inputs"
                maxLength={30}
              />
            </div>
          )}

          {schema.length > 0 && (
            <div className="flex flex-col gap-3">
              {schema.map((field) => (
                <div key={field.name} className="flex flex-col gap-1">
                  <label htmlFor={`input-${field.name}`} className="text-sm font-medium">
                    {field.label}
                    {field.required && <span className="text-destructive ml-1">*</span>}
                  </label>
                  {field.type === "software_project_id" ? (
                    <SoftwareProjectSelect
                      value={(inputValues[field.name] as string) || ""}
                      onChange={(v) => handleInputChange(field.name, v)}
                      testId="start-run-software-project-select"
                    />
                  ) : field.type === "git_repo" ? (
                    <GitRepoSelect
                      value={(inputValues[field.name] as string) || ""}
                      onChange={(v) => handleInputChange(field.name, v)}
                    />
                  ) : field.type === "textarea" ? (
                    <Textarea
                      id={`input-${field.name}`}
                      value={(inputValues[field.name] as string) ?? ""}
                      onChange={(e) => handleInputChange(field.name, e.target.value)}
                      className="max-h-48"
                    />
                  ) : (
                    <Input
                      id={`input-${field.name}`}
                      type={field.type === "number" ? "number" : "text"}
                      value={(inputValues[field.name] as string) ?? ""}
                      onChange={(e) => handleInputChange(field.name, e.target.value)}
                    />
                  )}
                </div>
              ))}
            </div>
          )}

          <FileUploadZone onFilesChange={setInputFiles} disabled={startRun.isPending} />
        </div>

        <DialogFooter>
          <Button
            data-testid="start-run-submit"
            onClick={handleStart}
            disabled={!selectedTemplateId || hasRequiredMissing || startRun.isPending}
          >
            {startRun.isPending ? "Starting..." : "Start"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
