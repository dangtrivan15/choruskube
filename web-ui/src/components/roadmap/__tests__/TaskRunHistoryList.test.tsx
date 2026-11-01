import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/__tests__/test-utils";
import TaskRunHistoryList from "@/components/roadmap/TaskRunHistoryList";
import type { RunSummary } from "@/lib/types";

function makeRun(overrides: Partial<RunSummary> = {}): RunSummary {
  return {
    id: "run-1",
    graphTemplateId: "tpl-1",
    templateName: "Task Delivery",
    name: null,
    status: "running",
    startedAt: null,
    completedAt: null,
    createdAt: "2026-10-01T00:00:00Z",
    autopilotId: null,
    softwareProject: null,
    storySpecVersion: null,
    ...overrides,
  };
}

describe("TaskRunHistoryList", () => {
  it("names the Story spec version each run planned against", () => {
    renderWithProviders(
      <TaskRunHistoryList
        runs={[makeRun({ id: "run-2", storySpecVersion: 3 }), makeRun({ id: "run-1", storySpecVersion: 2 })]}
        isLoading={false}
      />
    );

    const versions = screen.getAllByTestId("task-run-history-spec-version").map((el) => el.textContent);
    expect(versions).toEqual(["planned against spec v3", "planned against spec v2"]);
  });

  it("names no spec version for a run that planned against none", () => {
    renderWithProviders(
      <TaskRunHistoryList runs={[makeRun({ templateName: "Feature Development" })]} isLoading={false} />
    );

    expect(screen.getByTestId("task-run-history-item")).toHaveTextContent("Feature Development");
    expect(screen.queryByTestId("task-run-history-spec-version")).not.toBeInTheDocument();
  });
});
