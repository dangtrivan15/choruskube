import { describe, it, expect, vi, beforeEach } from "vitest";
import type { RunEvent, FeatureProposalEvent } from "@/lib/types";

// Mock sonner
vi.mock("sonner", () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
    warning: vi.fn(),
    info: vi.fn(),
  },
}));

import { toast } from "sonner";
import { mapEventToToast, showEventToast, showMutationToast, clearDedupCache } from "@/lib/toast-messages";

describe("toast-messages", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    clearDedupCache();
  });

  describe("mapEventToToast", () => {
    it("maps run_status_changed -> completed to success toast", () => {
      const event: RunEvent = {
        type: "run_status_changed",
        runId: "r1",
        nodeExecutionId: null,
        status: "completed",
      };
      const config = mapEventToToast(event);
      expect(config).toEqual({
        message: "Run completed successfully",
        variant: "success",
        actionUrl: "/runs/r1",
      });
    });

    it("maps run_status_changed -> failed to error toast", () => {
      const event: RunEvent = {
        type: "run_status_changed",
        runId: "r1",
        nodeExecutionId: null,
        status: "failed",
      };
      const config = mapEventToToast(event);
      expect(config?.variant).toBe("error");
    });

    it("maps run_status_changed -> running to info toast", () => {
      const event: RunEvent = {
        type: "run_status_changed",
        runId: "r1",
        nodeExecutionId: null,
        status: "running",
      };
      const config = mapEventToToast(event);
      expect(config?.variant).toBe("info");
    });

    it("maps run_status_changed -> cancelled to warning toast", () => {
      const event: RunEvent = {
        type: "run_status_changed",
        runId: "r1",
        nodeExecutionId: null,
        status: "cancelled",
      };
      const config = mapEventToToast(event);
      expect(config?.variant).toBe("warning");
    });

    it("maps run_status_changed -> paused to info toast", () => {
      const event: RunEvent = {
        type: "run_status_changed",
        runId: "r1",
        nodeExecutionId: null,
        status: "paused",
      };
      const config = mapEventToToast(event);
      expect(config?.variant).toBe("info");
    });

    it("maps node_status_changed -> awaiting_human to warning toast with /approvals link", () => {
      const event: RunEvent = {
        type: "node_status_changed",
        runId: "r1",
        nodeExecutionId: "ne1",
        status: "awaiting_human",
      };
      const config = mapEventToToast(event);
      expect(config?.variant).toBe("warning");
      expect(config?.message).toContain("awaiting approval");
      expect(config?.actionUrl).toBe("/approvals");
    });

    it("maps node_status_changed -> failed to error toast", () => {
      const event: RunEvent = {
        type: "node_status_changed",
        runId: "r1",
        nodeExecutionId: "ne1",
        status: "failed",
      };
      const config = mapEventToToast(event);
      expect(config?.variant).toBe("error");
    });

    it("returns null for node_status_changed -> running (too noisy)", () => {
      const event: RunEvent = {
        type: "node_status_changed",
        runId: "r1",
        nodeExecutionId: "ne1",
        status: "running",
      };
      expect(mapEventToToast(event)).toBeNull();
    });

    it("returns null for node_logs_updated events", () => {
      const event: RunEvent = {
        type: "node_logs_updated",
        runId: "r1",
        nodeExecutionId: "ne1",
        status: null,
      };
      expect(mapEventToToast(event)).toBeNull();
    });

    // --- FeatureProposalEvent tests (matches backend shape) ---

    it("maps proposal_changed with status backlog to info toast", () => {
      const event: FeatureProposalEvent = {
        type: "proposal_changed",
        proposalId: "p1",
        status: "backlog",
      };
      const config = mapEventToToast(event);
      expect(config).toEqual({
        message: "Proposal updated",
        variant: "info",
        actionUrl: "/roadmap",
      });
    });

    it("maps proposal_changed with status in_progress to info toast", () => {
      const event: FeatureProposalEvent = {
        type: "proposal_changed",
        proposalId: "p1",
        status: "in_progress",
      };
      const config = mapEventToToast(event);
      expect(config).toEqual({
        message: "Proposal started",
        variant: "info",
        actionUrl: "/roadmap",
      });
    });

    it("maps proposal_changed with status rolled_out to success toast", () => {
      const event: FeatureProposalEvent = {
        type: "proposal_changed",
        proposalId: "p1",
        status: "rolled_out",
      };
      const config = mapEventToToast(event);
      expect(config?.variant).toBe("success");
      expect(config?.message).toBe("Proposal rolled out");
    });

    it("maps proposal_changed with status deleted to info toast without actionUrl", () => {
      const event: FeatureProposalEvent = {
        type: "proposal_changed",
        proposalId: "p1",
        status: "deleted",
      };
      const config = mapEventToToast(event);
      expect(config).toEqual({
        message: "Proposal deleted",
        variant: "info",
      });
    });

    it("maps run_status_changed bridge event (completed) to success toast", () => {
      const event: FeatureProposalEvent = {
        type: "run_status_changed",
        proposalId: null,
        status: "completed",
      };
      const config = mapEventToToast(event);
      expect(config).toEqual({
        message: "Linked run completed",
        variant: "success",
        actionUrl: "/roadmap",
      });
    });

    it("maps run_status_changed bridge event (failed) to error toast", () => {
      const event: FeatureProposalEvent = {
        type: "run_status_changed",
        proposalId: null,
        status: "failed",
      };
      const config = mapEventToToast(event);
      expect(config?.variant).toBe("error");
      expect(config?.message).toBe("Linked run failed");
    });

    it("maps run_status_changed bridge event (cancelled) to warning toast", () => {
      const event: FeatureProposalEvent = {
        type: "run_status_changed",
        proposalId: null,
        status: "cancelled",
      };
      const config = mapEventToToast(event);
      expect(config?.variant).toBe("warning");
      expect(config?.message).toBe("Linked run cancelled");
    });

    // --- Pending gates context (receives RunEvent objects) ---

    it("maps awaiting_human RunEvent (pending gates) to warning toast", () => {
      const event: RunEvent = {
        type: "node_status_changed",
        runId: "r1",
        nodeExecutionId: "ne1",
        status: "awaiting_human",
      };
      const config = mapEventToToast(event);
      expect(config?.variant).toBe("warning");
      expect(config?.message).toContain("awaiting approval");
      expect(config?.actionUrl).toBe("/approvals");
    });

    it("maps run cancelled RunEvent (pending gates) to warning toast", () => {
      const event: RunEvent = {
        type: "run_status_changed",
        runId: "r1",
        nodeExecutionId: null,
        status: "cancelled",
      };
      const config = mapEventToToast(event);
      expect(config?.variant).toBe("warning");
    });

    it("returns null for unknown run status", () => {
      const event: RunEvent = {
        type: "run_status_changed",
        runId: "r1",
        nodeExecutionId: null,
        status: "some_unknown_status",
      };
      expect(mapEventToToast(event)).toBeNull();
    });

    it("returns null for unknown proposal status", () => {
      const event: FeatureProposalEvent = {
        type: "proposal_changed",
        proposalId: "p1",
        status: "some_unknown_status",
      };
      expect(mapEventToToast(event)).toBeNull();
    });
  });

  describe("showEventToast", () => {
    it("calls toast.success for completed run with dedup id", () => {
      const event: RunEvent = {
        type: "run_status_changed",
        runId: "r1",
        nodeExecutionId: null,
        status: "completed",
      };
      const entry = showEventToast(event);
      expect(toast.success).toHaveBeenCalledWith(
        "Run completed successfully",
        expect.objectContaining({
          id: "run:run_status_changed:r1::completed",
          duration: 4000,
        }),
      );
      expect(entry).not.toBeNull();
      expect(entry?.variant).toBe("success");
    });

    it("calls toast.error with longer duration and dedup id for failed run", () => {
      const event: RunEvent = {
        type: "run_status_changed",
        runId: "r1",
        nodeExecutionId: null,
        status: "failed",
      };
      showEventToast(event);
      expect(toast.error).toHaveBeenCalledWith(
        "Run failed",
        expect.objectContaining({
          id: "run:run_status_changed:r1::failed",
          duration: 6000,
        }),
      );
    });

    it("deduplicates identical events within the dedup window", () => {
      const event: RunEvent = {
        type: "run_status_changed",
        runId: "r1",
        nodeExecutionId: null,
        status: "completed",
      };
      const first = showEventToast(event);
      const second = showEventToast(event);

      expect(first).not.toBeNull();
      expect(second).toBeNull();
      expect(toast.success).toHaveBeenCalledTimes(1);
    });

    it("returns null for non-toastable events", () => {
      const event: RunEvent = {
        type: "node_logs_updated",
        runId: "r1",
        nodeExecutionId: "ne1",
        status: null,
      };
      const entry = showEventToast(event);
      expect(entry).toBeNull();
    });

    it("shows toast for FeatureProposalEvent proposal_changed with dedup id", () => {
      const event: FeatureProposalEvent = {
        type: "proposal_changed",
        proposalId: "p1",
        status: "rolled_out",
      };
      const entry = showEventToast(event);
      expect(toast.success).toHaveBeenCalledWith(
        "Proposal rolled out",
        expect.objectContaining({
          id: "proposal:proposal_changed:p1:rolled_out",
          duration: 4000,
        }),
      );
      expect(entry).not.toBeNull();
      expect(entry?.variant).toBe("success");
    });

    it("shows toast for FeatureProposalEvent bridge run_status_changed with dedup id", () => {
      const event: FeatureProposalEvent = {
        type: "run_status_changed",
        proposalId: null,
        status: "failed",
      };
      const entry = showEventToast(event);
      expect(toast.error).toHaveBeenCalledWith(
        "Linked run failed",
        expect.objectContaining({
          id: "proposal:run_status_changed::failed",
          duration: 6000,
        }),
      );
      expect(entry).not.toBeNull();
      expect(entry?.variant).toBe("error");
    });
  });

  describe("showMutationToast", () => {
    it("calls toast.success for success variant", () => {
      const entry = showMutationToast("Run started", "success", "/runs/r1");
      expect(toast.success).toHaveBeenCalledWith("Run started", { duration: 4000 });
      expect(entry.message).toBe("Run started");
      expect(entry.variant).toBe("success");
      expect(entry.actionUrl).toBe("/runs/r1");
    });

    it("calls toast.error with 6s duration for error variant", () => {
      const entry = showMutationToast("Failed to start", "error");
      expect(toast.error).toHaveBeenCalledWith("Failed to start", { duration: 6000 });
      expect(entry.variant).toBe("error");
    });

    it("calls toast.warning for warning variant", () => {
      showMutationToast("Run cancelled", "warning");
      expect(toast.warning).toHaveBeenCalledWith("Run cancelled", { duration: 4000 });
    });

    it("calls toast.info for info variant", () => {
      showMutationToast("Node retried", "info");
      expect(toast.info).toHaveBeenCalledWith("Node retried", { duration: 4000 });
    });

    it("generates unique entry ids", () => {
      const a = showMutationToast("A");
      const b = showMutationToast("B");
      expect(a.id).not.toBe(b.id);
    });
  });
});
