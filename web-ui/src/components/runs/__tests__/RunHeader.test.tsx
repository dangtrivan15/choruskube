import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import RunHeader from "../RunHeader";
import type { RunResponse } from "@/lib/types";

const mockPauseMutate = vi.fn();
const mockResumeMutate = vi.fn();
const mockCancelMutate = vi.fn();
const mockRenameMutate = vi.fn();

let pauseIsPending = false;
let resumeIsPending = false;
let cancelIsPending = false;

vi.mock("@/hooks/useRuns", () => ({
  usePauseRun: vi.fn(() => ({
    mutate: mockPauseMutate,
    isPending: pauseIsPending,
  })),
  useResumeRun: vi.fn(() => ({
    mutate: mockResumeMutate,
    isPending: resumeIsPending,
  })),
  useCancelRun: vi.fn(() => ({
    mutate: mockCancelMutate,
    isPending: cancelIsPending,
  })),
  useRenameRun: vi.fn(() => ({
    mutate: mockRenameMutate,
    isPending: false,
  })),
}));

function makeRun(overrides: Partial<RunResponse> = {}): RunResponse {
  return {
    id: "abc12345-6789-0000-0000-000000000000",
    graphTemplateId: "template-1",
    templateName: "Code Review Pipeline",
    name: null,
    status: "running",
    externalRunId: "ext-1",
    graphVersion: 1,
    graphSnapshot: null,
    startedAt: null,
    completedAt: null,
    createdAt: "2024-01-01T00:00:00Z",
    nodeExecutions: [],
    pullRequests: [],
    promptText: null,
    featureProposal: null,
    softwareProject: null,
    ...overrides,
  };
}

describe("RunHeader", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    pauseIsPending = false;
    resumeIsPending = false;
    cancelIsPending = false;
  });

  // --- Template name & ID ---

  it("renders template name when no run name is set", () => {
    renderWithProviders(<RunHeader run={makeRun()} />);

    expect(screen.getByText("Code Review Pipeline")).toBeInTheDocument();
  });

  it("renders run name when set", () => {
    renderWithProviders(<RunHeader run={makeRun({ name: "Add dark mode" })} />);

    expect(screen.getByText("Add dark mode")).toBeInTheDocument();
  });

  it("shows template name in subtitle when run name is set", () => {
    renderWithProviders(<RunHeader run={makeRun({ name: "Add dark mode" })} />);

    // Template name should appear in the subtitle with the short ID
    expect(screen.getByText(/Code Review Pipeline/)).toBeInTheDocument();
  });

  it("renders short run ID (first 8 chars)", () => {
    renderWithProviders(<RunHeader run={makeRun()} />);

    expect(screen.getByText("abc12345")).toBeInTheDocument();
  });

  // --- Rename (pencil icon) ---

  it("shows rename button (pencil icon)", () => {
    renderWithProviders(<RunHeader run={makeRun()} />);

    expect(screen.getByLabelText("Rename run")).toBeInTheDocument();
  });

  it("enters editing mode when pencil is clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(<RunHeader run={makeRun({ name: "Old Name" })} />);

    await user.click(screen.getByLabelText("Rename run"));

    const input = screen.getByDisplayValue("Old Name");
    expect(input).toBeInTheDocument();
  });

  it("caps the rename input at 30 characters", async () => {
    const user = userEvent.setup();
    renderWithProviders(<RunHeader run={makeRun({ name: "Old Name" })} />);

    await user.click(screen.getByLabelText("Rename run"));
    const input = screen.getByDisplayValue("Old Name");
    expect(input).toHaveAttribute("maxLength", "30");
  });

  it("calls renameMutation.mutate when pressing Enter", async () => {
    const user = userEvent.setup();
    renderWithProviders(<RunHeader run={makeRun({ name: "Old Name" })} />);

    await user.click(screen.getByLabelText("Rename run"));
    const input = screen.getByDisplayValue("Old Name");
    await user.clear(input);
    await user.type(input, "New Name{Enter}");

    expect(mockRenameMutate).toHaveBeenCalledWith("New Name");
  });

  it("cancels editing when pressing Escape", async () => {
    const user = userEvent.setup();
    renderWithProviders(<RunHeader run={makeRun({ name: "Old Name" })} />);

    await user.click(screen.getByLabelText("Rename run"));
    await user.keyboard("{Escape}");

    // Should return to the display mode
    expect(screen.getByText("Old Name")).toBeInTheDocument();
  });

  // --- Status badge ---

  it("renders the status badge with formatted text", () => {
    renderWithProviders(
      <RunHeader run={makeRun({ status: "awaiting_human" })} />
    );

    expect(screen.getByText("awaiting human")).toBeInTheDocument();
  });

  it("renders running status badge", () => {
    renderWithProviders(<RunHeader run={makeRun({ status: "running" })} />);

    expect(screen.getByText("running")).toBeInTheDocument();
  });

  // --- Pause button (running status) ---

  it("shows Pause and Cancel buttons when running", () => {
    renderWithProviders(<RunHeader run={makeRun({ status: "running" })} />);

    expect(screen.getByText("Pause")).toBeInTheDocument();
    expect(screen.getByText("Cancel")).toBeInTheDocument();
    expect(screen.queryByText("Resume")).not.toBeInTheDocument();
  });

  it("calls pauseMutation.mutate when Pause is clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(<RunHeader run={makeRun({ status: "running" })} />);

    await user.click(screen.getByText("Pause"));

    expect(mockPauseMutate).toHaveBeenCalled();
  });

  // --- Resume button (paused status) ---

  it("shows Resume and Cancel buttons when paused", () => {
    renderWithProviders(<RunHeader run={makeRun({ status: "paused" })} />);

    expect(screen.getByText("Resume")).toBeInTheDocument();
    expect(screen.getByText("Cancel")).toBeInTheDocument();
    expect(screen.queryByText("Pause")).not.toBeInTheDocument();
  });

  it("calls resumeMutation.mutate when Resume is clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(<RunHeader run={makeRun({ status: "paused" })} />);

    await user.click(screen.getByText("Resume"));

    expect(mockResumeMutate).toHaveBeenCalled();
  });

  // --- Cancel button ---

  it("calls cancelMutation.mutate when Cancel is clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(<RunHeader run={makeRun({ status: "running" })} />);

    await user.click(screen.getByText("Cancel"));

    expect(mockCancelMutate).toHaveBeenCalled();
  });

  // --- Terminal states ---

  it("hides all action buttons for completed status", () => {
    renderWithProviders(<RunHeader run={makeRun({ status: "completed" })} />);

    expect(screen.queryByText("Pause")).not.toBeInTheDocument();
    expect(screen.queryByText("Resume")).not.toBeInTheDocument();
    expect(screen.queryByText("Cancel")).not.toBeInTheDocument();
  });

  it("hides all action buttons for failed status", () => {
    renderWithProviders(<RunHeader run={makeRun({ status: "failed" })} />);

    expect(screen.queryByText("Pause")).not.toBeInTheDocument();
    expect(screen.queryByText("Resume")).not.toBeInTheDocument();
    expect(screen.queryByText("Cancel")).not.toBeInTheDocument();
  });

  // --- awaiting_human status ---

  it("shows only Cancel button for awaiting_human status", () => {
    renderWithProviders(
      <RunHeader run={makeRun({ status: "awaiting_human" })} />
    );

    expect(screen.queryByText("Pause")).not.toBeInTheDocument();
    expect(screen.queryByText("Resume")).not.toBeInTheDocument();
    expect(screen.getByText("Cancel")).toBeInTheDocument();
  });

  // --- awaiting_retry status ---

  it("renders awaiting_retry status badge", () => {
    renderWithProviders(
      <RunHeader run={makeRun({ status: "awaiting_retry" })} />
    );

    expect(screen.getByText("awaiting retry")).toBeInTheDocument();
  });

  it("shows Cancel button for awaiting_retry status", () => {
    renderWithProviders(
      <RunHeader run={makeRun({ status: "awaiting_retry" })} />
    );

    expect(screen.getByText("Cancel")).toBeInTheDocument();
    expect(screen.queryByText("Pause")).not.toBeInTheDocument();
    expect(screen.queryByText("Resume")).not.toBeInTheDocument();
  });

  // --- cancelled status ---

  it("hides all action buttons for cancelled status", () => {
    renderWithProviders(
      <RunHeader run={makeRun({ status: "cancelled" })} />
    );

    expect(screen.queryByText("Pause")).not.toBeInTheDocument();
    expect(screen.queryByText("Resume")).not.toBeInTheDocument();
    expect(screen.queryByText("Cancel")).not.toBeInTheDocument();
  });
});
