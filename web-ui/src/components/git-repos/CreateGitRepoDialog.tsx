import { useState } from "react";
import { useCreateGitRepo } from "@/hooks/useGitRepos";
import { useAuth } from "@/components/AuthProvider";
import { useGitHubCredential } from "@/hooks/useGitHubCredential";
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
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

export default function CreateGitRepoDialog({ open, onOpenChange }: Props) {
  const [url, setUrl] = useState("");
  const [defaultBranch, setDefaultBranch] = useState("main");
  const [testCommand, setTestCommand] = useState("");
  const [agentImage, setAgentImage] = useState("");
  const [enableDocker, setEnableDocker] = useState(false);

  const { organizationId } = useAuth();
  const { data: gitHubCredential } = useGitHubCredential(organizationId ?? "");
  const showCredentialBanner = organizationId && gitHubCredential === null;

  const createGitRepo = useCreateGitRepo();

  function handleCreate() {
    if (!url.trim()) return;

    createGitRepo.mutate(
      {
        url: url.trim(),
        defaultBranch: defaultBranch.trim() || "main",
        testCommand: testCommand.trim() || undefined,
        agentImage: agentImage.trim() || undefined,
        enableDocker,
      },
      {
        onSuccess: () => {
          onOpenChange(false);
          resetForm();
        },
      }
    );
  }

  function resetForm() {
    setUrl("");
    setDefaultBranch("main");
    setTestCommand("");
    setAgentImage("");
    setEnableDocker(false);
    createGitRepo.reset();
  }

  function handleOpenChange(isOpen: boolean) {
    onOpenChange(isOpen);
    if (!isOpen) resetForm();
  }

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent size="md">
        <DialogHeader>
          <DialogTitle>New Git Repo</DialogTitle>
          <DialogDescription>
            Register a repository with its build and test configuration.
          </DialogDescription>
        </DialogHeader>

        <div className="min-h-0 flex-1 overflow-y-auto py-2 flex flex-col gap-4 -mx-4 px-4">
          {showCredentialBanner && (
            <div
              role="alert"
              className="rounded-md border border-status-info/20 bg-status-info/10 p-3 text-sm text-status-info"
            >
              No GitHub credential is configured. Repositories may not be accessible. Configure
              one in Org Settings → Integrations.
            </div>
          )}

          <div className="flex flex-col gap-1">
            <label htmlFor="repo-url" className="text-sm font-medium">
              Repository URL <span className="text-destructive">*</span>
            </label>
            <Input
              id="repo-url"
              value={url}
              onChange={(e) => setUrl(e.target.value)}
              placeholder="https://github.com/org/repo"
            />
          </div>

          <div className="flex flex-col gap-1">
            <label htmlFor="repo-branch" className="text-sm font-medium">
              Default Branch
            </label>
            <Input
              id="repo-branch"
              value={defaultBranch}
              onChange={(e) => setDefaultBranch(e.target.value)}
              placeholder="main"
            />
          </div>

          <div className="flex flex-col gap-1">
            <label htmlFor="repo-test-command" className="text-sm font-medium">
              Test Command
            </label>
            <Input
              id="repo-test-command"
              value={testCommand}
              onChange={(e) => setTestCommand(e.target.value)}
              placeholder="npm test"
            />
          </div>

          <div className="flex flex-col gap-1">
            <label htmlFor="repo-image" className="text-sm font-medium">
              Agent Image
            </label>
            <Input
              id="repo-image"
              value={agentImage}
              onChange={(e) => setAgentImage(e.target.value)}
              placeholder="agent:latest"
            />
          </div>

          <div className="flex items-center gap-2">
            <input
              id="repo-docker"
              type="checkbox"
              checked={enableDocker}
              onChange={(e) => setEnableDocker(e.target.checked)}
              className="size-4 rounded border"
            />
            <label htmlFor="repo-docker" className="text-sm font-medium">
              Enable Docker-in-Docker
            </label>
          </div>

        </div>

        <DialogFooter>
          {createGitRepo.isError && (
            <p className="text-sm text-destructive mr-auto">
              Failed to create git repo. It may already exist.
            </p>
          )}
          <Button
            onClick={handleCreate}
            disabled={!url.trim() || createGitRepo.isPending}
          >
            {createGitRepo.isPending ? "Creating..." : "Create"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
