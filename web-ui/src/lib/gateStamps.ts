import type { GateStampResponse, SignalResponse } from "@/lib/types";

/** The toast for a submitted decision: `finalizing` means the workflow is still doing the gate's work. */
export function decisionToastMessage(response: SignalResponse | undefined): string {
  if (response?.outcome === "finalizing") return "Finalizing your approval…";
  return "Decision submitted";
}

/** The newest stamp for a gate, if any. Relies on the endpoint listing stamps oldest first. */
export function latestStampFor(stamps: GateStampResponse[] | undefined, gateLabel: string): GateStampResponse | null {
  const forGate = (stamps ?? []).filter((s) => s.gateLabel === gateLabel);
  return forGate.length ? forGate[forGate.length - 1] : null;
}

const ENDED_RUN_STATUSES = new Set(["completed", "failed", "cancelled"]);

/**
 * The pending stamp anywhere in the run — what the run summary chip shows. None once the run has
 * ended: the server voids it when the run ends, and an ended run gets no further event to refresh on.
 */
export function pendingStamp(
  stamps: GateStampResponse[] | undefined,
  runStatus?: string,
): GateStampResponse | null {
  if (runStatus && ENDED_RUN_STATUSES.has(runStatus)) return null;
  return (stamps ?? []).find((s) => s.status === "pending") ?? null;
}
