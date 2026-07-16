import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/__tests__/test-utils";
import RunMetaPanel from "../RunMetaPanel";
import type { RunResponse, RunTaskSummary } from "@/lib/types";

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
    task: null,
    softwareProject: null,
    ...overrides,
  };
}

function makeTask(overrides: Partial<RunTaskSummary> = {}): RunTaskSummary {
  return {
    id: "task-1",
    title: "Add dark mode",
    status: "backlog",
    softwareProject: { id: "sp-1", type: "git_repo", name: "my-repo" },
    ...overrides,
  };
}

describe("RunMetaPanel", () => {
  it("renders Run Info heading", () => {
    renderWithProviders(<RunMetaPanel run={makeRun()} />);
    expect(screen.getByText("Run Info")).toBeInTheDocument();
  });

  it("renders feature request section when promptText is provided", () => {
    renderWithProviders(
      <RunMetaPanel run={makeRun({ promptText: "Add a logout button" })} />
    );
    expect(screen.getByTestId("run-meta-panel-prompt")).toHaveTextContent("Add a logout button");
  });

  it("renders software project when present", () => {
    renderWithProviders(
      <RunMetaPanel
        run={makeRun({
          softwareProject: { id: "sp-1", type: "git_repo", name: "my-repo" },
        })}
      />
    );
    expect(screen.getByTestId("run-meta-panel-software-project")).toHaveTextContent("my-repo");
  });

  it("renders task link and status when task is provided", () => {
    const task = makeTask({ id: "t-1", title: "Dark Mode", status: "in_progress" });
    renderWithProviders(
      <RunMetaPanel run={makeRun({ task })} />
    );
    expect(screen.getByTestId("run-meta-panel-task-link")).toHaveTextContent("Dark Mode");
    expect(screen.getByTestId("run-meta-panel-status")).toHaveTextContent("in progress");
  });

  it("renders pull-request links when present", () => {
    const pullRequests = [
      {
        id: "pr-1",
        workflowRunId: "run-1",
        gitRepoId: "repo-1",
        nodeExecutionId: null,
        prUrl: "https://github.com/org/repo/pull/42",
        prNumber: 42,
        title: "Add feature",
        repoName: "my-repo",
        repoUrl: "https://github.com/org/repo",
        createdAt: "2024-01-01T00:00:00Z",
      },
    ];
    renderWithProviders(
      <RunMetaPanel run={makeRun({ pullRequests })} />
    );
    expect(screen.getByTestId("pull-request-links")).toBeInTheDocument();
    expect(screen.getByTestId("pull-request-link")).toBeInTheDocument();
  });

  it("renders empty state when no metadata fields are set", () => {
    renderWithProviders(
      <RunMetaPanel
        run={makeRun({
          promptText: null,
          softwareProject: null,
          task: null,
          pullRequests: [],
        })}
      />
    );
    expect(screen.getByText("No run metadata available.")).toBeInTheDocument();
  });

  it("does not render empty state when promptText is set", () => {
    renderWithProviders(
      <RunMetaPanel run={makeRun({ promptText: "Some feature request" })} />
    );
    expect(screen.queryByText("No run metadata available.")).not.toBeInTheDocument();
  });
});
