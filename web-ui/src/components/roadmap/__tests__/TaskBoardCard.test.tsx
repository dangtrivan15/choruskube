import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/__tests__/test-utils";
import TaskBoardCard from "@/components/roadmap/TaskBoardCard";
import type { TaskResponse } from "@/lib/types";

function makeTask(overrides: Partial<TaskResponse> = {}): TaskResponse {
  return {
    id: "task-1",
    storyId: "story-1",
    title: "Implement dark mode toggle",
    description: "Add a toggle control",
    status: "backlog",
    softwareProject: { id: "r1", type: "git_repo", name: "backend-api" },
    repos: [],
    latestRunId: null,
    latestRunStatus: null,
    readiness: null,
    recentRuns: [],
    totalRunCount: 0,
    createdAt: "2026-04-01T00:00:00Z",
    updatedAt: "2026-04-01T00:00:00Z",
    priority: "medium",
    ...overrides,
  };
}

describe("TaskBoardCard", () => {
  it("renders the title and status badge", () => {
    renderWithProviders(
      <TaskBoardCard task={makeTask({ title: "Implement dark mode toggle", status: "in_progress" })} />
    );
    expect(screen.getByTestId("task-board-card-title")).toHaveTextContent(
      "Implement dark mode toggle"
    );
    expect(screen.getByTestId("task-board-card-status")).toHaveTextContent("in progress");
  });

  it("renders the latest run status when present", () => {
    renderWithProviders(<TaskBoardCard task={makeTask({ latestRunStatus: "completed" })} />);
    expect(screen.getByTestId("task-board-card-run-status")).toHaveTextContent("completed");
  });

  it("does not render a run-status badge when latestRunStatus is null", () => {
    renderWithProviders(<TaskBoardCard task={makeTask({ latestRunStatus: null })} />);
    expect(screen.queryByTestId("task-board-card-run-status")).not.toBeInTheDocument();
  });

  it("does not render a readiness badge even when readiness is BLOCKED", () => {
    renderWithProviders(<TaskBoardCard task={makeTask({ readiness: "BLOCKED" })} />);
    expect(screen.queryByText("Blocked")).not.toBeInTheDocument();
  });

  it("title links to the task detail page", () => {
    renderWithProviders(<TaskBoardCard task={makeTask({ id: "task-42" })} />);
    expect(screen.getByTestId("task-board-card-title")).toHaveAttribute("href", "/tasks/task-42");
  });

  it("renders the Task's priority badge", () => {
    renderWithProviders(<TaskBoardCard task={makeTask({ priority: "high" })} />);
    expect(screen.getByTestId("task-board-card-priority-badge")).toHaveTextContent("High");
  });

  it("renders the Medium priority badge for a Task with no explicit priority override", () => {
    renderWithProviders(<TaskBoardCard task={makeTask({ priority: "medium" })} />);
    expect(screen.getByTestId("task-board-card-priority-badge")).toHaveTextContent("Medium");
  });
});
