import StatusCallout from "@/components/ui/StatusCallout";
import type { RunPullRequestResponse } from "@/lib/types";
import { pullRequestStatus, type MergeMethod } from "@/lib/mergeOnApproval";

interface MergeOnApprovalNoticeProps {
  method: MergeMethod;
  pullRequests: RunPullRequestResponse[];
}

const ITEM_TEXT: Record<ReturnType<typeof pullRequestStatus>, string> = {
  merged: "already merged",
  open: "will be merged",
  closed: "closed — will be skipped",
  unknown: "will be merged",
};

/** Info callout on a merge-configured gate, listing what approving will merge. */
export default function MergeOnApprovalNotice({ method, pullRequests }: MergeOnApprovalNoticeProps) {
  if (pullRequests.length === 0) return null;

  return (
    <StatusCallout tone="info" data-testid="merge-on-approval-notice">
      <p>Approving merges this run&apos;s pull requests ({method}) before the run completes.</p>
      <ul className="mt-1 list-disc space-y-0.5 pl-4">
        {pullRequests.map((pr) => (
          <li key={pr.id}>
            {pr.repoName ?? "repo"} #{pr.prNumber ?? "?"} — {ITEM_TEXT[pullRequestStatus(pr)]}
          </li>
        ))}
      </ul>
    </StatusCallout>
  );
}
