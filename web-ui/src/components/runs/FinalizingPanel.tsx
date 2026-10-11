import StatusCallout from "@/components/ui/StatusCallout";
import type { GateStampResponse } from "@/lib/types";

/** A gate whose approval is being finalized in the background, by whichever steps the gate declares. */
export default function FinalizingPanel({ stamp }: { stamp: GateStampResponse | null }) {
  return (
    <StatusCallout tone="info" data-testid="gate-finalizing">
      Finalizing the approval in the background: re-checking, merging and writing the roadmap as this gate requires.
      {stamp && stamp.recheckRounds > 0 ? ` Re-check round ${stamp.recheckRounds} of ${stamp.maxRecheckRounds}.` : ""}
    </StatusCallout>
  );
}
