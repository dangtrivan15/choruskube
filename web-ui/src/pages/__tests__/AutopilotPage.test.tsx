import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import AutopilotPage from "@/pages/AutopilotPage";
import type { AutopilotStatus, AutopilotTaskRef } from "@/lib/types";

vi.mock("@/hooks/useAutopilot", () => ({
  useAutopilot: vi.fn(),
  useUpdateAutopilot: vi.fn(),
  useEngageAutopilot: vi.fn(),
  useDisengageAutopilot: vi.fn(),
  useTickAutopilot: vi.fn(),
  useAutopilotSubscription: vi.fn(),
}));

import {
  useAutopilot,
  useUpdateAutopilot,
  useEngageAutopilot,
  useDisengageAutopilot,
  useTickAutopilot,
} from "@/hooks/useAutopilot";

const mockUseAutopilot = useAutopilot as ReturnType<typeof vi.fn>;
const mockUseUpdateAutopilot = useUpdateAutopilot as ReturnType<typeof vi.fn>;
const mockUseEngageAutopilot = useEngageAutopilot as ReturnType<typeof vi.fn>;
const mockUseDisengageAutopilot = useDisengageAutopilot as ReturnType<typeof vi.fn>;
const mockUseTickAutopilot = useTickAutopilot as ReturnType<typeof vi.fn>;

function taskRef(overrides: Partial<AutopilotTaskRef> = {}): AutopilotTaskRef {
  return {
    taskId: "task-1",
    title: "Wire up billing webhook",
    runId: null,
    status: "ready",
    ...overrides,
  };
}

function makeStatus(overrides: Partial<AutopilotStatus> = {}): AutopilotStatus {
  return {
    engaged: true,
    maxParallel: 2,
    inFlight: 1,
    slots: 1,
    nextUp: [],
    whyIdle: [],
    awaitingYou: [],
    needsAttention: [],
    consecutiveFailures: 0,
    disengagedReason: null,
    lastTickAt: null,
    ...overrides,
  };
}

describe("AutopilotPage", () => {
  let engageMutate: ReturnType<typeof vi.fn>;
  let disengageMutate: ReturnType<typeof vi.fn>;
  let updateMutate: ReturnType<typeof vi.fn>;
  let tickMutate: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    vi.clearAllMocks();
    engageMutate = vi.fn();
    disengageMutate = vi.fn();
    updateMutate = vi.fn();
    tickMutate = vi.fn();

    mockUseEngageAutopilot.mockReturnValue({ mutate: engageMutate, isPending: false });
    mockUseDisengageAutopilot.mockReturnValue({ mutate: disengageMutate, isPending: false });
    mockUseUpdateAutopilot.mockReturnValue({ mutate: updateMutate, isPending: false });
    mockUseTickAutopilot.mockReturnValue({ mutate: tickMutate, isPending: false });
  });

  it("shows a loading skeleton while the status is loading", () => {
    mockUseAutopilot.mockReturnValue({ data: undefined, isLoading: true, isError: false });

    renderWithProviders(<AutopilotPage />);

    expect(screen.getByText("Autopilot")).toBeInTheDocument();
    expect(screen.queryByTestId("autopilot-toggle")).not.toBeInTheDocument();
  });

  it("shows an error message when the status fails to load", () => {
    mockUseAutopilot.mockReturnValue({ data: undefined, isLoading: false, isError: true });

    renderWithProviders(<AutopilotPage />);

    expect(screen.getByText("Failed to load Autopilot status.")).toBeInTheDocument();
  });

  it("renders the in-flight count and slots", () => {
    mockUseAutopilot.mockReturnValue({
      data: makeStatus({ inFlight: 2, maxParallel: 3, slots: 1 }),
      isLoading: false,
      isError: false,
    });

    renderWithProviders(<AutopilotPage />);

    const inFlight = screen.getByTestId("autopilot-in-flight");
    expect(inFlight).toHaveTextContent("2");
    expect(inFlight).toHaveTextContent("of 3 slot(s) in use");
    expect(inFlight).toHaveTextContent("1 free");
  });

  it("renders why-idle reasons when present", () => {
    mockUseAutopilot.mockReturnValue({
      data: makeStatus({ whyIdle: ["Epic 'Billing' — no tasks defined"] }),
      isLoading: false,
      isError: false,
    });

    renderWithProviders(<AutopilotPage />);

    expect(screen.getByTestId("autopilot-why-idle")).toHaveTextContent(
      "Epic 'Billing' — no tasks defined"
    );
  });

  it("shows a not-idle message when why-idle is empty", () => {
    mockUseAutopilot.mockReturnValue({
      data: makeStatus({ whyIdle: [] }),
      isLoading: false,
      isError: false,
    });

    renderWithProviders(<AutopilotPage />);

    expect(screen.getByTestId("autopilot-why-idle")).toHaveTextContent("Not idle");
  });

  it("renders next-up, awaiting-you, and needs-attention task refs", () => {
    mockUseAutopilot.mockReturnValue({
      data: makeStatus({
        nextUp: [taskRef({ taskId: "t-1", title: "Next task", status: "ready" })],
        awaitingYou: [
          taskRef({ taskId: "t-2", title: "Parked task", runId: "run-1", status: "awaiting_human" }),
        ],
        needsAttention: [
          taskRef({ taskId: "t-3", title: "Failed task", runId: "run-2", status: "awaiting_retry" }),
        ],
      }),
      isLoading: false,
      isError: false,
    });

    renderWithProviders(<AutopilotPage />);

    expect(screen.getByTestId("autopilot-next-up")).toHaveTextContent("Next task");
    const awaitingYou = screen.getByTestId("autopilot-awaiting-you");
    expect(awaitingYou).toHaveTextContent("Parked task");
    expect(awaitingYou).toHaveTextContent("awaiting_human");
    const needsAttention = screen.getByTestId("autopilot-needs-attention");
    expect(needsAttention).toHaveTextContent("Failed task");
    expect(needsAttention).toHaveTextContent("awaiting_retry");
  });

  it("shows the disengaged banner with the reason when present", () => {
    mockUseAutopilot.mockReturnValue({
      data: makeStatus({
        engaged: false,
        disengagedReason: "Disengaged after 3 consecutive failures — the last failure was: boom",
      }),
      isLoading: false,
      isError: false,
    });

    renderWithProviders(<AutopilotPage />);

    expect(screen.getByTestId("autopilot-disengaged-banner")).toHaveTextContent(
      "Disengaged after 3 consecutive failures — the last failure was: boom"
    );
  });

  it("does not show the disengaged banner when disengagedReason is null", () => {
    mockUseAutopilot.mockReturnValue({
      data: makeStatus({ disengagedReason: null }),
      isLoading: false,
      isError: false,
    });

    renderWithProviders(<AutopilotPage />);

    expect(screen.queryByTestId("autopilot-disengaged-banner")).not.toBeInTheDocument();
  });

  it("engages the Autopilot when the toggle is switched on from disengaged", async () => {
    const user = userEvent.setup();
    mockUseAutopilot.mockReturnValue({
      data: makeStatus({ engaged: false }),
      isLoading: false,
      isError: false,
    });

    renderWithProviders(<AutopilotPage />);

    await user.click(screen.getByTestId("autopilot-toggle"));

    expect(engageMutate).toHaveBeenCalled();
    expect(disengageMutate).not.toHaveBeenCalled();
  });

  it("disengages the Autopilot when the toggle is switched off from engaged", async () => {
    const user = userEvent.setup();
    mockUseAutopilot.mockReturnValue({
      data: makeStatus({ engaged: true }),
      isLoading: false,
      isError: false,
    });

    renderWithProviders(<AutopilotPage />);

    await user.click(screen.getByTestId("autopilot-toggle"));

    expect(disengageMutate).toHaveBeenCalled();
    expect(engageMutate).not.toHaveBeenCalled();
  });

  it("commits a new maxParallel value on blur", async () => {
    const user = userEvent.setup();
    mockUseAutopilot.mockReturnValue({
      data: makeStatus({ maxParallel: 2 }),
      isLoading: false,
      isError: false,
    });

    renderWithProviders(<AutopilotPage />);

    const input = screen.getByTestId("autopilot-max-parallel");
    await user.clear(input);
    await user.type(input, "5");
    await user.tab();

    expect(updateMutate).toHaveBeenCalledWith({ maxParallel: 5 });
  });

  it("does not commit when the value is unchanged", async () => {
    const user = userEvent.setup();
    mockUseAutopilot.mockReturnValue({
      data: makeStatus({ maxParallel: 2 }),
      isLoading: false,
      isError: false,
    });

    renderWithProviders(<AutopilotPage />);

    const input = screen.getByTestId("autopilot-max-parallel");
    await user.click(input);
    await user.tab();

    expect(updateMutate).not.toHaveBeenCalled();
  });

  it("runs a manual tick when the tick button is clicked", async () => {
    const user = userEvent.setup();
    mockUseAutopilot.mockReturnValue({
      data: makeStatus(),
      isLoading: false,
      isError: false,
    });

    renderWithProviders(<AutopilotPage />);

    await user.click(screen.getByTestId("autopilot-tick"));

    expect(tickMutate).toHaveBeenCalled();
  });
});
