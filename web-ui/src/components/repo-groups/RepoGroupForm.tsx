import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import type { RepoGroupRequest } from "@/lib/types";

interface RepoGroupFormProps {
  /** Optional initial values. Useful for an Edit flow (deferred). */
  initial?: Partial<RepoGroupRequest>;
  /** All git repos the user can pick from. */
  availableRepos: { id: string; name: string }[];
  /** Called with the assembled request body on Save. */
  onSubmit: (body: RepoGroupRequest) => void;
  /** Label for the primary action button. Defaults to "Save". */
  submitLabel?: string;
  /** Mirror the mutation's {@code isPending} to drive button copy / disabled state. */
  submitting?: boolean;
  /** Inline error message (e.g. from a failed mutation). */
  error?: string | null;
}

/**
 * Controlled form for creating (and later, editing) a RepoGroup.
 *
 * Member selection order is significant — the position of an id in
 * {@code memberRepoIds} reflects the order the user clicked the checkboxes
 * in, not the order they appear in {@code availableRepos}. Toggling a member
 * off and re-on appends it at the end so re-clicks "move" the position.
 *
 * Empty optional fields ({@code agentImage}, {@code description}) are sent as
 * {@code null}, matching the {@code RepoGroupRequest} optional+nullable shape.
 */
export default function RepoGroupForm({
  initial,
  availableRepos,
  onSubmit,
  submitLabel = "Save",
  submitting = false,
  error = null,
}: RepoGroupFormProps) {
  const [name, setName] = useState(initial?.name ?? "");
  const [agentImage, setAgentImage] = useState(initial?.agentImage ?? "");
  const [description, setDescription] = useState(initial?.description ?? "");
  const [selected, setSelected] = useState<string[]>(initial?.memberRepoIds ?? []);

  const trimmedName = name.trim();
  const canSave = trimmedName.length > 0 && selected.length > 0;

  function toggleMember(id: string) {
    setSelected((prev) =>
      prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id],
    );
  }

  function handleSubmit() {
    if (!canSave) return;
    onSubmit({
      name: trimmedName,
      agentImage: agentImage.trim() ? agentImage.trim() : null,
      description: description.trim() ? description.trim() : null,
      memberRepoIds: selected,
    });
  }

  return (
    <>
      <div className="min-h-0 flex-1 overflow-y-auto py-2 flex flex-col gap-4 -mx-4 px-4">
        <div className="flex flex-col gap-1">
          <label htmlFor="repo-group-name" className="text-sm font-medium">
            Name <span className="text-destructive">*</span>
          </label>
          <Input
            id="repo-group-name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="my-monorepo"
          />
        </div>

        <div className="flex flex-col gap-1">
          <label htmlFor="repo-group-image" className="text-sm font-medium">
            Agent Image
          </label>
          <Input
            id="repo-group-image"
            value={agentImage}
            onChange={(e) => setAgentImage(e.target.value)}
            placeholder="agent:latest"
          />
        </div>

        <div className="flex flex-col gap-1">
          <label htmlFor="repo-group-description" className="text-sm font-medium">
            Description
          </label>
          <Textarea
            id="repo-group-description"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            placeholder="What this group is for"
          />
        </div>

        <div className="flex flex-col gap-2">
          <span className="text-sm font-medium">
            Members <span className="text-destructive">*</span>
          </span>
          {availableRepos.length === 0 ? (
            <p className="text-sm text-muted-foreground">
              No repositories available. Register a Git repo first.
            </p>
          ) : (
            <div className="flex flex-col gap-2">
              {availableRepos.map((r) => {
                const checked = selected.includes(r.id);
                const inputId = `repo-group-member-${r.id}`;
                return (
                  <div key={r.id} className="flex items-center gap-2">
                    <input
                      id={inputId}
                      type="checkbox"
                      className="size-4 rounded border"
                      aria-label={r.name}
                      checked={checked}
                      onChange={() => toggleMember(r.id)}
                    />
                    <label htmlFor={inputId} className="text-sm">
                      {r.name}
                    </label>
                  </div>
                );
              })}
            </div>
          )}
        </div>
      </div>

      <div className="flex items-center justify-end gap-2">
        {error && (
          <p className="text-sm text-destructive mr-auto">{error}</p>
        )}
        <Button onClick={handleSubmit} disabled={!canSave || submitting}>
          {submitting ? "Saving..." : submitLabel}
        </Button>
      </div>
    </>
  );
}
