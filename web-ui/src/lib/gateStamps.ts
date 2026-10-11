import type { GateStampResponse, SignalResponse } from "@/lib/types";

/** The toast for a submitted decision: a held approval says why it is not applied yet. */
export function decisionToastMessage(response: SignalResponse | undefined): string {
  if (response?.outcome !== "held_for_recheck") return "Decision submitted";
  const repos = response.staleRepos.map((r) => r.repo).join(", ");
  return `Approval held: main moved on ${repos || "this run's repos"} since it was tested. Re-checking against current main; it proceeds automatically when that passes.`;
}

/** The newest stamp for a gate, if any. Relies on the endpoint listing stamps oldest first. */
export function latestStampFor(stamps: GateStampResponse[] | undefined, gateLabel: string): GateStampResponse | null {
  const forGate = (stamps ?? []).filter((s) => s.gateLabel === gateLabel);
  return forGate.length ? forGate[forGate.length - 1] : null;
}

/** The pending stamp anywhere in the run — what the run summary chip shows. */
export function pendingStamp(stamps: GateStampResponse[] | undefined): GateStampResponse | null {
  return (stamps ?? []).find((s) => s.status === "pending") ?? null;
}
