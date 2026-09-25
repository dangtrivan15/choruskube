import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/__tests__/test-utils";
import ExecutionLogs from "../ExecutionLogs";

vi.mock("@/hooks/useRuns", () => ({
  useNodeLogs: vi.fn(),
}));

import { useNodeLogs } from "@/hooks/useRuns";

const mockUseNodeLogs = useNodeLogs as ReturnType<typeof vi.fn>;

describe("ExecutionLogs", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("shows loading state", () => {
    mockUseNodeLogs.mockReturnValue({ data: undefined, isLoading: true });
    renderWithProviders(
      <ExecutionLogs runId="r1" nodeExecId="e1" isActive={true} />
    );

    expect(screen.getByText("Loading logs...")).toBeInTheDocument();
  });

  it("shows empty state when no logs", () => {
    mockUseNodeLogs.mockReturnValue({ data: [], isLoading: false });
    renderWithProviders(
      <ExecutionLogs runId="r1" nodeExecId="e1" isActive={true} />
    );

    expect(screen.getByText("No logs available.")).toBeInTheDocument();
  });

  it("shows empty state when logs is undefined", () => {
    mockUseNodeLogs.mockReturnValue({ data: undefined, isLoading: false });
    renderWithProviders(
      <ExecutionLogs runId="r1" nodeExecId="e1" isActive={false} />
    );

    expect(screen.getByText("No logs available.")).toBeInTheDocument();
  });

  it("renders log entries with timestamp, level, and message", () => {
    const logs = [
      {
        id: "log-1",
        level: "info",
        message: "Starting execution",
        timestamp: "2026-03-01T10:30:45.123Z",
      },
      {
        id: "log-2",
        level: "warn",
        message: "Retrying request",
        timestamp: "2026-03-01T10:30:46.456Z",
      },
      {
        id: "log-3",
        level: "error",
        message: "Failed to connect",
        timestamp: "2026-03-01T10:30:47.789Z",
      },
    ];
    mockUseNodeLogs.mockReturnValue({ data: logs, isLoading: false });
    renderWithProviders(
      <ExecutionLogs runId="r1" nodeExecId="e1" isActive={true} />
    );

    // Messages
    expect(screen.getByText("Starting execution")).toBeInTheDocument();
    expect(screen.getByText("Retrying request")).toBeInTheDocument();
    expect(screen.getByText("Failed to connect")).toBeInTheDocument();

    // Levels
    expect(screen.getByText("info")).toBeInTheDocument();
    expect(screen.getByText("warn")).toBeInTheDocument();
    expect(screen.getByText("error")).toBeInTheDocument();
  });

  it("renders per-level border, label color/weight and icon for info/warn/error", () => {
    const logs = [
      { id: "log-1", level: "info", message: "Starting execution", timestamp: "2026-03-01T10:30:45.123Z" },
      { id: "log-2", level: "warn", message: "Retrying request", timestamp: "2026-03-01T10:30:46.456Z" },
      { id: "log-3", level: "error", message: "Failed to connect", timestamp: "2026-03-01T10:30:47.789Z" },
    ];
    mockUseNodeLogs.mockReturnValue({ data: logs, isLoading: false });
    renderWithProviders(
      <ExecutionLogs runId="r1" nodeExecId="e1" isActive={true} />
    );

    const rows = screen.getAllByTestId("log-row");
    expect(rows).toHaveLength(3);

    const expectations: Record<string, { border: string; text: string; weight?: string }> = {
      info: { border: "border-status-info", text: "text-status-info" },
      warn: { border: "border-status-warning", text: "text-status-warning", weight: "font-medium" },
      error: { border: "border-status-error", text: "text-status-error", weight: "font-semibold" },
    };

    for (const row of rows) {
      const level = row.getAttribute("data-level")!;
      const { border, text, weight } = expectations[level];
      expect(row).toHaveClass(border);

      const label = row.querySelector('[data-testid="log-level"]')!;
      expect(label).toHaveClass(text);
      if (weight) expect(label).toHaveClass(weight);

      expect(row.querySelector("svg[aria-hidden]")).not.toBeNull();
    }
  });

  it("falls back to the neutral treatment for an unrecognized level, without crashing", () => {
    const logs = [
      { id: "log-1", level: "debug", message: "Trace point hit", timestamp: "2026-03-01T10:30:45.123Z" },
    ];
    mockUseNodeLogs.mockReturnValue({ data: logs, isLoading: false });
    renderWithProviders(
      <ExecutionLogs runId="r1" nodeExecId="e1" isActive={true} />
    );

    const row = screen.getByTestId("log-row");
    expect(row).toHaveAttribute("data-level", "debug");
    expect(row).toHaveClass("border-status-neutral");
    expect(screen.getByTestId("log-level")).toHaveClass("text-status-neutral");
    expect(screen.getByText("Trace point hit")).toBeInTheDocument();
  });

  it("keeps the message in the default foreground, never a severity color", () => {
    const logs = [
      { id: "log-1", level: "error", message: "Failed to connect", timestamp: "2026-03-01T10:30:47.789Z" },
    ];
    mockUseNodeLogs.mockReturnValue({ data: logs, isLoading: false });
    renderWithProviders(
      <ExecutionLogs runId="r1" nodeExecId="e1" isActive={true} />
    );

    const message = screen.getByText("Failed to connect");
    expect(message.className).not.toMatch(/text-status-/);
  });

  it("passes correct params to useNodeLogs", () => {
    mockUseNodeLogs.mockReturnValue({ data: [], isLoading: false });
    renderWithProviders(
      <ExecutionLogs runId="run-abc" nodeExecId="exec-def" isActive={true} />
    );

    expect(mockUseNodeLogs).toHaveBeenCalledWith("run-abc", "exec-def", true);
  });

  it("passes isActive=false to useNodeLogs", () => {
    mockUseNodeLogs.mockReturnValue({ data: undefined, isLoading: false });
    renderWithProviders(
      <ExecutionLogs runId="run-abc" nodeExecId="exec-def" isActive={false} />
    );

    expect(mockUseNodeLogs).toHaveBeenCalledWith("run-abc", "exec-def", false);
  });
});
