import StatusCallout from "@/components/ui/StatusCallout";
import type { GateStampResponse } from "@/lib/types";

interface HeldApprovalNoticeProps {
  stamp: GateStampResponse | null;
}

/** Shown in a gate's panel: a held approval waiting to re-apply, or why one was not applied. */
export default function HeldApprovalNotice({ stamp }: HeldApprovalNoticeProps) {
  if (!stamp) return null;
  if (stamp.status === "pending") {
    return (
      <StatusCallout tone="info" data-testid="gate-held-notice">
        Your approval is held while this run re-checks against current main (round {stamp.recheckRounds} of{" "}
        {stamp.maxRecheckRounds}). It applies automatically when the re-check passes; deciding here replaces it.
      </StatusCallout>
    );
  }
  if (stamp.status === "voided" && !stamp.supersededByReviewer) {
    return (
      <StatusCallout tone="warning" data-testid="gate-held-voided-notice">
        A held approval was not applied: {stamp.voidReason} Decide again.
      </StatusCallout>
    );
  }
  return null;
}
