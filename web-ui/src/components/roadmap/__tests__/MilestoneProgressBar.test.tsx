import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/__tests__/test-utils";
import MilestoneProgressBar from "@/components/roadmap/MilestoneProgressBar";

describe("MilestoneProgressBar", () => {
  it("renders a title summarizing done/total tasks", () => {
    renderWithProviders(
      <MilestoneProgressBar
        progress={{ totalTasks: 4, doneTasks: 1, inProgressTasks: 1, notStartedTasks: 2 }}
        data-testid="bar"
      />
    );
    expect(screen.getByTestId("bar")).toHaveAttribute("title", "1/4 tasks done");
  });

  it("renders an empty track (no NaN widths) for a Milestone with no descendant Tasks", () => {
    renderWithProviders(
      <MilestoneProgressBar
        progress={{ totalTasks: 0, doneTasks: 0, inProgressTasks: 0, notStartedTasks: 0 }}
        data-testid="bar"
      />
    );
    const segments = screen.getByTestId("bar").querySelectorAll("div");
    segments.forEach((segment) => {
      expect((segment as HTMLElement).style.width).toBe("0%");
    });
  });

  it("splits segment widths proportionally to the task counts", () => {
    renderWithProviders(
      <MilestoneProgressBar
        progress={{ totalTasks: 4, doneTasks: 1, inProgressTasks: 1, notStartedTasks: 2 }}
        data-testid="bar"
      />
    );
    const [done, inProgress, notStarted] = Array.from(
      screen.getByTestId("bar").querySelectorAll("div")
    ) as HTMLElement[];
    expect(done.style.width).toBe("25%");
    expect(inProgress.style.width).toBe("25%");
    expect(notStarted.style.width).toBe("50%");
  });

  it("forwards data-testid", () => {
    renderWithProviders(
      <MilestoneProgressBar
        progress={{ totalTasks: 1, doneTasks: 1, inProgressTasks: 0, notStartedTasks: 0 }}
        data-testid="my-progress-bar"
      />
    );
    expect(screen.getByTestId("my-progress-bar")).toBeInTheDocument();
  });
});
