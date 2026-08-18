import { describe, it, expect, vi } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import RollOutPrompt, { readyToRollOut } from "@/components/roadmap/RollOutPrompt";
import type { EpicStage, WorkItemProgress } from "@/lib/types";

const progress = (totalTasks: number, doneTasks: number): WorkItemProgress => ({
  totalTasks,
  doneTasks,
  startedTasks: doneTasks,
});

describe("readyToRollOut", () => {
  const cases: { name: string; stage: EpicStage; progress: WorkItemProgress; expected: boolean }[] = [
    { name: "every Task done and not yet shipped", stage: "backlog", progress: progress(4, 4), expected: true },
    {
      name: "every Task done while the board already says in progress",
      stage: "in_progress",
      progress: progress(2, 2),
      expected: true,
    },
    { name: "already rolled out", stage: "rolled_out", progress: progress(4, 4), expected: false },
    { name: "work still outstanding", stage: "backlog", progress: progress(4, 3), expected: false },
    // An empty container has vacuously "no unfinished Tasks"; prompting to ship it would invite a
    // shipped-but-empty Epic, which is exactly what EpicReadinessAssembler refuses to treat as
    // satisfied. Emptiness is not completion.
    { name: "no Tasks at all", stage: "backlog", progress: progress(0, 0), expected: false },
  ];

  it.each(cases)("is $expected for $name", ({ stage, progress: p, expected }) => {
    expect(readyToRollOut(stage, p)).toBe(expected);
  });
});

describe("RollOutPrompt", () => {
  it("reports the finished count and offers the move", () => {
    renderWithProviders(
      <RollOutPrompt stage="backlog" progress={progress(4, 4)} onRollOut={vi.fn()} />,
    );
    expect(screen.getByTestId("roll-out-prompt")).toHaveTextContent(
      "All 4 tasks are done — not yet rolled out.",
    );
    expect(screen.getByTestId("roll-out-prompt-button")).toBeInTheDocument();
  });

  it("says 'task is' rather than 'tasks are' for a single Task", () => {
    renderWithProviders(
      <RollOutPrompt stage="backlog" progress={progress(1, 1)} onRollOut={vi.fn()} />,
    );
    expect(screen.getByTestId("roll-out-prompt")).toHaveTextContent("All 1 task is done");
  });

  it("calls onRollOut when the button is pressed", async () => {
    const onRollOut = vi.fn();
    renderWithProviders(<RollOutPrompt stage="backlog" progress={progress(2, 2)} onRollOut={onRollOut} />);
    await userEvent.click(screen.getByTestId("roll-out-prompt-button"));
    expect(onRollOut).toHaveBeenCalledOnce();
  });

  it("disables the button while the move is in flight", () => {
    renderWithProviders(
      <RollOutPrompt stage="backlog" progress={progress(2, 2)} pending onRollOut={vi.fn()} />,
    );
    expect(screen.getByTestId("roll-out-prompt-button")).toBeDisabled();
  });

  it("renders nothing once the item is rolled out", () => {
    renderWithProviders(
      <RollOutPrompt stage="rolled_out" progress={progress(2, 2)} onRollOut={vi.fn()} />,
    );
    expect(screen.queryByTestId("roll-out-prompt")).not.toBeInTheDocument();
  });
});
