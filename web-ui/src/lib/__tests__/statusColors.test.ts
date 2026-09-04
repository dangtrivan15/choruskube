import { describe, it, expect } from "vitest";
import { statusBadgeClass, statusColorTokens } from "../statusColors";

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
