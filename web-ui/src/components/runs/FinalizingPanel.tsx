import StatusCallout from "@/components/ui/StatusCallout";
import type { GateStampResponse } from "@/lib/types";

/** A gate whose approval is being finalized: checks against main and merges run in the background. */
export default function FinalizingPanel({ stamp }: { stamp: GateStampResponse | null }) {
  return (
    <StatusCallout tone="info" data-testid="gate-finalizing">
      Finalizing the approval: checking against current main and merging the pull requests.
      {stamp && stamp.recheckRounds > 0 ? ` Re-check round ${stamp.recheckRounds} of ${stamp.maxRecheckRounds}.` : ""}
    </StatusCallout>
  );
}
