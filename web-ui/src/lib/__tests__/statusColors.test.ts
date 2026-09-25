import { describe, it, expect } from "vitest";
import {
  statusBadgeClass,
  statusColorTokens,
  statusTone,
  provisioningTone,
  credentialHealthTone,
  STATUS_TONE_CLASSES,
  type StatusTone,
} from "../statusColors";

describe("statusBadgeClass", () => {
  it("returns success classes for completed", () => {
    expect(statusBadgeClass("completed")).toBe(
      "bg-status-success/15 text-status-success border-status-success/20"
    );
  });

  it("returns error classes for failed", () => {
    expect(statusBadgeClass("failed")).toBe(
      "bg-status-error/15 text-status-error border-status-error/20"
    );
  });

  it("returns info classes for running", () => {
    expect(statusBadgeClass("running")).toBe(
      "bg-status-info/15 text-status-info border-status-info/20"
    );
  });

  it("returns warning classes for awaiting_human", () => {
    expect(statusBadgeClass("awaiting_human")).toBe(
      "bg-status-warning/15 text-status-warning border-status-warning/20"
    );
  });

  it("returns warning classes for awaiting_retry", () => {
    expect(statusBadgeClass("awaiting_retry")).toBe(
      "bg-status-warning/15 text-status-warning border-status-warning/20"
    );
  });

  it("returns accent classes for paused", () => {
    expect(statusBadgeClass("paused")).toBe(
      "bg-status-accent/15 text-status-accent border-status-accent/20"
    );
  });

  it("returns neutral classes for cancelled (no line-through — that is a component concern)", () => {
    expect(statusBadgeClass("cancelled")).toBe(
      "bg-status-neutral/15 text-status-neutral border-status-neutral/20"
    );
  });

  it("returns neutral classes for pending", () => {
    expect(statusBadgeClass("pending")).toBe(
      "bg-status-neutral/15 text-status-neutral border-status-neutral/20"
    );
  });

  it("returns neutral fallback for unknown statuses", () => {
    expect(statusBadgeClass("some_future_status")).toBe(
      "bg-status-neutral/15 text-status-neutral border-status-neutral/20"
    );
  });
});

describe("statusColorTokens", () => {
  it("returns success tokens for completed", () => {
    const tokens = statusColorTokens("completed");
    expect(tokens.bg).toBe("bg-status-success");
    expect(tokens.border).toBe("border-status-success");
    expect(tokens.text).toBe("text-status-success");
  });

  it("returns error tokens for failed", () => {
    const tokens = statusColorTokens("failed");
    expect(tokens.bg).toBe("bg-status-error");
    expect(tokens.border).toBe("border-status-error");
    expect(tokens.text).toBe("text-status-error");
  });

  it("returns info tokens for running", () => {
    const tokens = statusColorTokens("running");
    expect(tokens.bg).toBe("bg-status-info");
    expect(tokens.border).toBe("border-status-info");
    expect(tokens.text).toBe("text-status-info");
  });

  it("returns warning tokens for awaiting_human", () => {
    const tokens = statusColorTokens("awaiting_human");
    expect(tokens.bg).toBe("bg-status-warning");
    expect(tokens.border).toBe("border-status-warning");
    expect(tokens.text).toBe("text-status-warning");
  });

  it("returns warning tokens for awaiting_retry", () => {
    const tokens = statusColorTokens("awaiting_retry");
    expect(tokens.bg).toBe("bg-status-warning");
    expect(tokens.border).toBe("border-status-warning");
    expect(tokens.text).toBe("text-status-warning");
  });

  it("returns accent tokens for paused", () => {
    const tokens = statusColorTokens("paused");
    expect(tokens.bg).toBe("bg-status-accent");
    expect(tokens.border).toBe("border-status-accent");
    expect(tokens.text).toBe("text-status-accent");
  });

  it("returns neutral tokens for cancelled", () => {
    const tokens = statusColorTokens("cancelled");
    expect(tokens.bg).toBe("bg-status-neutral");
    expect(tokens.border).toBe("border-status-neutral");
    expect(tokens.text).toBe("text-status-neutral");
  });

  it("returns neutral tokens for pending", () => {
    const tokens = statusColorTokens("pending");
    expect(tokens.bg).toBe("bg-status-neutral");
    expect(tokens.border).toBe("border-status-neutral");
    expect(tokens.text).toBe("text-status-neutral");
  });

  it("returns neutral fallback for unknown statuses", () => {
    const tokens = statusColorTokens("anything_unknown");
    expect(tokens.bg).toBe("bg-status-neutral");
    expect(tokens.border).toBe("border-status-neutral");
    expect(tokens.text).toBe("text-status-neutral");
  });
});

describe("five-state distinctness and full coverage", () => {
  // The five states a run/node can be in that a viewer must be able to tell
  // apart at a glance; each must resolve to a different --status-* token
  // family so no two ever collapse onto the same color.
  const FIVE_NAMED_STATES = ["completed", "failed", "running", "awaiting_human", "pending"];

  it("each of the five named states resolves to a different token", () => {
    const tokens = FIVE_NAMED_STATES.map((status) => statusColorTokens(status).text);
    expect(new Set(tokens).size).toBe(FIVE_NAMED_STATES.length);
  });

  it("no two of the five named states share a badge class", () => {
    const classes = FIVE_NAMED_STATES.map((status) => statusBadgeClass(status));
    expect(new Set(classes).size).toBe(FIVE_NAMED_STATES.length);
  });

  it("every known run/node status string maps to a token — no silent collision via the neutral fallback", () => {
    const known: Record<string, string> = {
      completed: "success",
      failed: "error",
      running: "info",
      awaiting_human: "warning",
      awaiting_retry: "warning",
      paused: "accent",
      cancelled: "neutral",
      pending: "neutral",
    };
    for (const [status, token] of Object.entries(known)) {
      expect(statusColorTokens(status).text).toBe(`text-status-${token}`);
    }
  });

  it("an unrecognized status resolves to the documented neutral fallback, not a wrong state's color", () => {
    expect(statusColorTokens("some_future_status").text).toBe("text-status-neutral");
    expect(statusBadgeClass("some_future_status")).toContain("status-neutral");
  });
});

describe("STATUS_TONE_CLASSES", () => {
  const TONES: StatusTone[] = ["success", "error", "info", "warning", "accent", "neutral"];
  const OTHER_TONES: Record<StatusTone, StatusTone[]> = {
    success: ["error", "info", "warning", "accent", "neutral"],
    error: ["success", "info", "warning", "accent", "neutral"],
    info: ["success", "error", "warning", "accent", "neutral"],
    warning: ["success", "error", "info", "accent", "neutral"],
    accent: ["success", "error", "info", "warning", "neutral"],
    neutral: ["success", "error", "info", "warning", "accent"],
  };

  it.each(TONES)("%s has all six recipes, each referencing only its own tone", (tone) => {
    const recipes = STATUS_TONE_CLASSES[tone];
    for (const key of ["badge", "bg", "border", "text", "dot", "callout"] as const) {
      expect(recipes[key]).toContain(`status-${tone}`);
      for (const other of OTHER_TONES[tone]) {
        expect(recipes[key]).not.toContain(`status-${other}`);
      }
    }
  });

  it.each(TONES)("%s's callout uses foreground text, never a tone-colored text class", (tone) => {
    const { callout } = STATUS_TONE_CLASSES[tone];
    expect(callout).toContain("text-foreground");
    expect(callout).not.toMatch(/text-status-/);
  });
});

describe("statusTone", () => {
  const CASES: [string, StatusTone][] = [
    ["completed", "success"],
    ["failed", "error"],
    ["running", "info"],
    ["awaiting_human", "warning"],
    ["awaiting_retry", "warning"],
    ["paused", "accent"],
    ["cancelled", "neutral"],
    ["pending", "neutral"],
    ["some_future_status", "neutral"],
  ];

  it.each(CASES)("%s -> %s", (status, tone) => {
    expect(statusTone(status)).toBe(tone);
  });
});

describe("provisioningTone", () => {
  it("maps pending to neutral", () => {
    expect(provisioningTone("pending")).toBe("neutral");
  });
  it("maps provisioning to info", () => {
    expect(provisioningTone("provisioning")).toBe("info");
  });
  it("maps ready to success", () => {
    expect(provisioningTone("ready")).toBe("success");
  });
  it("maps failed to error", () => {
    expect(provisioningTone("failed")).toBe("error");
  });
});

describe("credentialHealthTone", () => {
  it("maps VALID to success", () => {
    expect(credentialHealthTone("VALID")).toBe("success");
  });
  it("maps EXPIRED to error", () => {
    expect(credentialHealthTone("EXPIRED")).toBe("error");
  });
  it("maps INSUFFICIENT_PERMISSIONS to error", () => {
    expect(credentialHealthTone("INSUFFICIENT_PERMISSIONS")).toBe("error");
  });
  it("maps UNREACHABLE to warning", () => {
    expect(credentialHealthTone("UNREACHABLE")).toBe("warning");
  });
});

describe("statusBadgeClass matches the tone table for every run status", () => {
  const STATUSES = [
    "completed",
    "failed",
    "running",
    "awaiting_human",
    "awaiting_retry",
    "paused",
    "cancelled",
    "pending",
    "unknown_status",
  ];

  it.each(STATUSES)("%s", (status) => {
    expect(statusBadgeClass(status)).toBe(STATUS_TONE_CLASSES[statusTone(status)].badge);
  });
});
