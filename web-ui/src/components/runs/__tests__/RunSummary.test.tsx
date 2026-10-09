import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import { RunSummaryStrip, RunSummaryMobileBar, RunSummaryDetails } from "../RunSummary";
import type { AttentionNodeRef } from "../RunSummary";
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
    autopilotId: null,
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
    storyId: null,
    storyTitle: null,
    epicId: null,
    epicTitle: null,
    ...overrides,
  };
}

const ATTENTION_GATE: AttentionNodeRef = {
  templateNodeId: "node-1",
  label: "review_gate",
  kind: "awaiting_human",
};

const ATTENTION_FAILED: AttentionNodeRef = {
  templateNodeId: "node-2",
  label: "code_review",
  kind: "failed",
};

beforeEach(() => {
  localStorage.clear();
});

describe("RunSummaryStrip", () => {
  it("returns null with no metadata and no attention node", () => {
    const { container } = renderWithProviders(<RunSummaryStrip run={makeRun()} />);
    expect(container.firstChild).toBeNull();
  });

  it("renders just the attention button when there is no metadata", () => {
    renderWithProviders(
      <RunSummaryStrip run={makeRun()} attentionNode={ATTENTION_GATE} onSelectNode={vi.fn()} />,
    );
    expect(screen.getByTestId("run-attention-button")).toBeInTheDocument();
  });

  it("renders the software project", () => {
    renderWithProviders(
      <RunSummaryStrip
        run={makeRun({ softwareProject: { id: "sp-1", type: "git_repo", name: "my-repo" } })}
      />,
    );
    expect(screen.getByTestId("run-summary-software-project")).toHaveTextContent("my-repo");
  });

  it("renders the roadmap breadcrumb and task status for a task-triggered run", () => {
    const task = makeTask({
      epicId: "epic-1",
      epicTitle: "UI Overhaul",
      storyId: "story-1",
      storyTitle: "Theming",
      status: "in_progress",
    });
    renderWithProviders(<RunSummaryStrip run={makeRun({ task })} />);

    const breadcrumb = screen.getByTestId("roadmap-breadcrumb");
    expect(breadcrumb).toHaveTextContent("UI Overhaul");
    expect(breadcrumb).toHaveTextContent("Theming");
    expect(screen.getByTestId("run-summary-task-status")).toHaveTextContent("in progress");
  });

  it("degrades gracefully when a task has an Epic but no resolvable Story", () => {
    const task = makeTask({ epicId: "epic-1", epicTitle: "UI Overhaul", storyId: null, storyTitle: null });
    renderWithProviders(<RunSummaryStrip run={makeRun({ task })} />);

    expect(screen.queryByTestId("roadmap-breadcrumb-story")).not.toBeInTheDocument();
    expect(screen.getByTestId("roadmap-breadcrumb-task")).toHaveTextContent("Add dark mode");
  });

  it("renders PR links inline", () => {
    renderWithProviders(
      <RunSummaryStrip
        run={makeRun({
          pullRequests: [
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
              state: null,
              mergedAt: null,
            },
          ],
        })}
      />,
    );
    expect(screen.getByTestId("pull-request-links")).toBeInTheDocument();
    expect(screen.getByTestId("pull-request-link")).toBeInTheDocument();
  });

  it("shows the first-line preview collapsed by default", () => {
    renderWithProviders(
      <RunSummaryStrip run={makeRun({ promptText: "## Add a logout button\n\nmore detail" })} />,
    );
    expect(screen.getByTestId("run-summary-prompt")).toHaveTextContent("Add a logout button");
  });

  it("the toggle expands to markdown and persists to localStorage", async () => {
    const user = userEvent.setup();
    renderWithProviders(<RunSummaryStrip run={makeRun({ promptText: "Add a logout button" })} />);

    await user.click(screen.getByTestId("run-summary-prompt-toggle"));

    expect(localStorage.getItem("run-summary-expanded")).toBe("true");
    expect(screen.getByTestId("run-summary-prompt-toggle")).toHaveAttribute("aria-expanded", "true");
  });

  it("renders expanded when localStorage already holds \"true\"", () => {
    localStorage.setItem("run-summary-expanded", "true");
    renderWithProviders(<RunSummaryStrip run={makeRun({ promptText: "Add a logout button" })} />);

    expect(screen.getByTestId("run-summary-prompt-toggle")).toHaveAttribute("aria-expanded", "true");
  });

  it("the expand button opens the prompt dialog", async () => {
    const user = userEvent.setup();
    renderWithProviders(<RunSummaryStrip run={makeRun({ promptText: "Add a logout button" })} />);

    await user.click(screen.getByTestId("run-summary-prompt-expand"));

    const matches = screen.getAllByText("Add a logout button");
    expect(matches.length).toBeGreaterThanOrEqual(2);
  });

  it("the attention button appears only when both props are given, and calls onSelectNode with the id", async () => {
    const user = userEvent.setup();
    const onSelectNode = vi.fn();
    const { rerender } = renderWithProviders(
      <RunSummaryStrip run={makeRun({ promptText: "x" })} attentionNode={ATTENTION_GATE} />,
    );
    expect(screen.queryByTestId("run-attention-button")).not.toBeInTheDocument();

    rerender(
      <RunSummaryStrip
        run={makeRun({ promptText: "x" })}
        attentionNode={ATTENTION_GATE}
        onSelectNode={onSelectNode}
      />,
    );
    await user.click(screen.getByTestId("run-attention-button"));
    expect(onSelectNode).toHaveBeenCalledWith("node-1");
  });

  it("labels an awaiting_human attention node as Review …", () => {
    renderWithProviders(
      <RunSummaryStrip run={makeRun()} attentionNode={ATTENTION_GATE} onSelectNode={vi.fn()} />,
    );
    expect(screen.getByTestId("run-attention-button")).toHaveTextContent("Review Review Gate");
  });

  it("labels a failed attention node as Failed: …", () => {
    renderWithProviders(
      <RunSummaryStrip run={makeRun()} attentionNode={ATTENTION_FAILED} onSelectNode={vi.fn()} />,
    );
    expect(screen.getByTestId("run-attention-button")).toHaveTextContent("Failed: Code Review");
  });
});

describe("RunSummaryMobileBar", () => {
  it("the info button calls onOpenInfo", async () => {
    const user = userEvent.setup();
    const onOpenInfo = vi.fn();
    renderWithProviders(
      <RunSummaryMobileBar run={makeRun()} onOpenInfo={onOpenInfo} onSelectNode={vi.fn()} />,
    );

    await user.click(screen.getByTestId("run-info-open-button"));
    expect(onOpenInfo).toHaveBeenCalled();
  });

  it("prioritizes task title over project over prompt", () => {
    const task = makeTask({ title: "My Task Title" });
    renderWithProviders(
      <RunSummaryMobileBar
        run={makeRun({
          task,
          softwareProject: { id: "sp-1", type: "git_repo", name: "my-repo" },
          promptText: "A prompt",
        })}
        onOpenInfo={vi.fn()}
        onSelectNode={vi.fn()}
      />,
    );
    expect(screen.getByText("My Task Title")).toBeInTheDocument();
    expect(screen.queryByText("my-repo")).not.toBeInTheDocument();
  });

  it("falls back to the project name when there is no task", () => {
    renderWithProviders(
      <RunSummaryMobileBar
        run={makeRun({ softwareProject: { id: "sp-1", type: "git_repo", name: "my-repo" } })}
        onOpenInfo={vi.fn()}
        onSelectNode={vi.fn()}
      />,
    );
    expect(screen.getByText("my-repo")).toBeInTheDocument();
  });

  it("falls back to the prompt's first line when there is no task or project", () => {
    renderWithProviders(
      <RunSummaryMobileBar
        run={makeRun({ promptText: "Add a logout button" })}
        onOpenInfo={vi.fn()}
        onSelectNode={vi.fn()}
      />,
    );
    expect(screen.getByText("Add a logout button")).toBeInTheDocument();
  });

  it("renders the attention button and calls onSelectNode when given", async () => {
    const user = userEvent.setup();
    const onSelectNode = vi.fn();
    renderWithProviders(
      <RunSummaryMobileBar
        run={makeRun()}
        attentionNode={ATTENTION_GATE}
        onOpenInfo={vi.fn()}
        onSelectNode={onSelectNode}
      />,
    );

    await user.click(screen.getByTestId("run-attention-button"));
    expect(onSelectNode).toHaveBeenCalledWith("node-1");
  });

  it("omits the attention button when there is none", () => {
    renderWithProviders(
      <RunSummaryMobileBar run={makeRun()} onOpenInfo={vi.fn()} onSelectNode={vi.fn()} />,
    );
    expect(screen.queryByTestId("run-attention-button")).not.toBeInTheDocument();
  });
});

describe("RunSummaryDetails", () => {
  it("shows the empty-state message when there is no metadata", () => {
    renderWithProviders(<RunSummaryDetails run={makeRun()} />);
    expect(screen.getByText("No run metadata available.")).toBeInTheDocument();
  });

  it("renders the full feature request", () => {
    renderWithProviders(<RunSummaryDetails run={makeRun({ promptText: "Add a logout button" })} />);
    expect(screen.getByTestId("run-summary-prompt")).toHaveTextContent("Add a logout button");
  });

  it("renders the stacked breadcrumb and list-variant PR links", () => {
    const task = makeTask({
      epicId: "epic-1",
      epicTitle: "UI Overhaul",
      storyId: "story-1",
      storyTitle: "Theming",
    });
    renderWithProviders(
      <RunSummaryDetails
        run={makeRun({
          task,
          pullRequests: [
            {
              id: "pr-1",
              workflowRunId: "run-1",
              gitRepoId: "repo-1",
              nodeExecutionId: null,
              prUrl: "https://github.com/org/repo/pull/1",
              prNumber: 1,
              title: "feat: x",
              repoName: "repo",
              repoUrl: "https://github.com/org/repo",
              createdAt: "2026-01-01T00:00:00Z",
              state: "open",
              mergedAt: null,
            },
          ],
        })}
      />,
    );

    const breadcrumb = screen.getByTestId("roadmap-breadcrumb");
    expect(breadcrumb).toHaveAttribute("data-variant", "stacked");
    expect(breadcrumb).toHaveTextContent("UI Overhaul");
    expect(screen.getByText("Pull Requests")).toBeInTheDocument();
  });
});
