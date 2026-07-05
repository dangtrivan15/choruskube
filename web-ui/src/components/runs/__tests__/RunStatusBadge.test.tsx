import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import RunStatusBadge from "../RunStatusBadge";

describe("RunStatusBadge", () => {
  it("renders the status text with underscores replaced by spaces", () => {
    render(<RunStatusBadge status="awaiting_human" />);
    expect(screen.getByText("awaiting human")).toBeInTheDocument();
  });

  it("replaces all underscores, not just the first", () => {
    render(<RunStatusBadge status="some_multi_word_status" />);
    expect(screen.getByText("some multi word status")).toBeInTheDocument();
  });

  it("renders known statuses with appropriate styling", () => {
    const { container } = render(<RunStatusBadge status="completed" />);
    const badge = container.querySelector("[data-slot='badge']") ?? container.firstElementChild;
    expect(badge).toHaveTextContent("completed");
  });

  it("renders running status", () => {
    render(<RunStatusBadge status="running" />);
    expect(screen.getByText("running")).toBeInTheDocument();
  });

  it("renders pending status", () => {
    render(<RunStatusBadge status="pending" />);
    expect(screen.getByText("pending")).toBeInTheDocument();
  });

  it("renders failed status", () => {
    render(<RunStatusBadge status="failed" />);
    expect(screen.getByText("failed")).toBeInTheDocument();
  });

  it("renders cancelled status", () => {
    render(<RunStatusBadge status="cancelled" />);
    expect(screen.getByText("cancelled")).toBeInTheDocument();
  });

  it("renders paused status", () => {
    render(<RunStatusBadge status="paused" />);
    expect(screen.getByText("paused")).toBeInTheDocument();
  });

  it("renders awaiting_retry status", () => {
    render(<RunStatusBadge status="awaiting_retry" />);
    expect(screen.getByText("awaiting retry")).toBeInTheDocument();
  });

  it("handles unknown status gracefully", () => {
    render(<RunStatusBadge status="unknown_status" />);
    expect(screen.getByText("unknown status")).toBeInTheDocument();
  });
});
