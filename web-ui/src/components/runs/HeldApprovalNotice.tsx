import StatusCallout from "@/components/ui/StatusCallout";
import type { GateStampResponse } from "@/lib/types";

interface HeldApprovalNoticeProps {
  stamp: GateStampResponse | null;
  /** The gate execution on screen: a voided stamp is news only at the execution it was voided at. */
  nodeExecId: string;
}

/** Shown in a gate's panel: a held approval waiting to re-apply, or why one was not applied. */
export default function HeldApprovalNotice({ stamp, nodeExecId }: HeldApprovalNoticeProps) {
  if (!stamp) return null;
  if (stamp.status === "pending") {
    return (
      <StatusCallout tone="info" data-testid="gate-held-notice">
        Your approval is held while this run re-checks against current main (round {stamp.recheckRounds} of{" "}
        {stamp.maxRecheckRounds}). It applies automatically when the re-check passes; deciding here replaces it.
      </StatusCallout>
    );
  }
  if (stamp.status === "voided" && !stamp.supersededByReviewer && stamp.parkedNodeExecutionId === nodeExecId) {
    return (
      <StatusCallout tone="warning" data-testid="gate-held-voided-notice">
        A held approval was not applied: {stamp.voidReason ?? "No reason was recorded."} Decide again.
      </StatusCallout>
    );
  }
  return null;
}
