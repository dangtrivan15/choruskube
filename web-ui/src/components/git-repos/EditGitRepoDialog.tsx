import { useState, useEffect } from "react";
import { useUpdateGitRepo } from "@/hooks/useGitRepos";
import type { GitRepoResponse } from "@/lib/types";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";

interface Props {
  gitRepo: GitRepoResponse | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

export default function EditGitRepoDialog({ gitRepo, open, onOpenChange }: Props) {
  const [url, setUrl] = useState("");
  const [defaultBranch, setDefaultBranch] = useState("main");
  const [testCommand, setTestCommand] = useState("");
  const [agentImage, setAgentImage] = useState("");
  const [enableDocker, setEnableDocker] = useState(false);

  const updateGitRepo = useUpdateGitRepo();

  useEffect(() => {
    if (gitRepo) {
      setUrl(gitRepo.url);
      setDefaultBranch(gitRepo.defaultBranch);
      setTestCommand(gitRepo.testCommand ?? "");
      setAgentImage(gitRepo.agentImage ?? "");
      setEnableDocker(gitRepo.enableDocker);
    }
  }, [gitRepo]);

  function handleSave() {
    if (!gitRepo || !url.trim()) return;

    updateGitRepo.mutate(
      {
        id: gitRepo.id,
        body: {
          url: url.trim(),
          defaultBranch: defaultBranch.trim() || "main",
          testCommand: testCommand.trim() || undefined,
          agentImage: agentImage.trim() || undefined,
          enableDocker,
        },
      },
      { onSuccess: () => onOpenChange(false) }
    );
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent size="md">
        <DialogHeader>
          <DialogTitle>Edit Git Repo</DialogTitle>
          <DialogDescription>Update repository configuration.</DialogDescription>
        </DialogHeader>

        <div className="min-h-0 flex-1 overflow-y-auto py-2 flex flex-col gap-4 -mx-4 px-4">
          <div className="flex flex-col gap-1">
            <label htmlFor="edit-repo-url" className="text-sm font-medium">
              Repository URL <span className="text-destructive">*</span>
            </label>
            <Input
              id="edit-repo-url"
              value={url}
              onChange={(e) => setUrl(e.target.value)}
            />
          </div>

          <div className="flex flex-col gap-1">
            <label htmlFor="edit-repo-branch" className="text-sm font-medium">
              Default Branch
            </label>
            <Input
              id="edit-repo-branch"
              value={defaultBranch}
              onChange={(e) => setDefaultBranch(e.target.value)}
            />
          </div>

          <div className="flex flex-col gap-1">
            <label htmlFor="edit-repo-test" className="text-sm font-medium">
              Test Command
            </label>
            <Input
              id="edit-repo-test"
              value={testCommand}
              onChange={(e) => setTestCommand(e.target.value)}
            />
          </div>

          <div className="flex flex-col gap-1">
            <label htmlFor="edit-repo-image" className="text-sm font-medium">
              Agent Image
            </label>
            <Input
              id="edit-repo-image"
              value={agentImage}
              onChange={(e) => setAgentImage(e.target.value)}
            />
          </div>

          <div className="flex items-center gap-2">
            <input
              id="edit-repo-docker"
              type="checkbox"
              checked={enableDocker}
              onChange={(e) => setEnableDocker(e.target.checked)}
              className="size-4 rounded border"
            />
            <label htmlFor="edit-repo-docker" className="text-sm font-medium">
              Enable Docker-in-Docker
            </label>
          </div>
        </div>

        <DialogFooter>
          {updateGitRepo.isError && (
            <p className="text-sm text-destructive mr-auto">
              Failed to update git repo.
            </p>
          )}
          <Button
            onClick={handleSave}
            disabled={!url.trim() || updateGitRepo.isPending}
          >
            {updateGitRepo.isPending ? "Saving..." : "Save"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
