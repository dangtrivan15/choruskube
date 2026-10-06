import { ExternalLink, GitPullRequest } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import type { RunPullRequestResponse } from "@/lib/types";
import { pullRequestStatus, type PullRequestStatus } from "@/lib/mergeOnApproval";
import { STATUS_TONE_CLASSES } from "@/lib/statusColors";

const STATUS_LABEL: Record<Exclude<PullRequestStatus, "unknown">, string> = {
  merged: "Merged",
  open: "Open",
  closed: "Closed",
};

const STATUS_BADGE_CLASS: Record<Exclude<PullRequestStatus, "unknown">, string> = {
  merged: STATUS_TONE_CLASSES.success.badge,
  open: STATUS_TONE_CLASSES.info.badge,
  closed: STATUS_TONE_CLASSES.neutral.badge,
};

interface PullRequestLinksProps {
  pullRequests: RunPullRequestResponse[];
}

export default function PullRequestLinks({ pullRequests }: PullRequestLinksProps) {
  if (pullRequests.length === 0) return null;

  return (
    <div
      className="flex flex-col gap-2 border-b px-4 py-3"
      data-testid="pull-request-links"
    >
      <div className="flex items-center gap-2">
        <GitPullRequest
          className="size-4 text-muted-foreground"
          aria-hidden="true"
        />
        <h3 className="text-sm font-medium">Pull Requests</h3>
      </div>
      <div className="flex flex-wrap gap-2">
        {pullRequests.map((pr) => {
          const label = pr.title ?? `PR #${pr.prNumber ?? ""}`;
          const status = pullRequestStatus(pr);
          return (
            <a
              key={pr.id}
              href={pr.prUrl}
              target="_blank"
              rel="noopener noreferrer"
              title={label}
              aria-label={
                status === "unknown"
                  ? `Open ${label} on GitHub`
                  : `Open ${label} on GitHub (${STATUS_LABEL[status].toLowerCase()})`
              }
              data-testid="pull-request-link"
              className="flex w-full min-w-0 max-w-full items-center gap-1.5 rounded-md border px-2.5 py-1.5 text-sm transition-colors hover:bg-muted sm:w-auto sm:max-w-md"
            >
              <Badge
                variant="outline"
                className="max-w-[40%] shrink-0 truncate text-xs"
              >
                {pr.repoName ?? "repo"}
              </Badge>
              <span className="min-w-0 flex-1 truncate">{label}</span>
              {status !== "unknown" && (
                <Badge
                  data-testid="pull-request-state"
                  className={STATUS_BADGE_CLASS[status]}
                >
                  {STATUS_LABEL[status]}
                </Badge>
              )}
              <ExternalLink
                className="size-3 shrink-0 text-muted-foreground"
                aria-hidden="true"
              />
            </a>
          );
        })}
      </div>
      {pullRequests.length > 1 && (
        <p className="text-xs text-muted-foreground">
          {pullRequests.length} companion PRs linked to this run
        </p>
      )}
    </div>
  );
}
