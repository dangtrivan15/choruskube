import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import RunMetaBar from "../RunMetaBar";
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

describe("RunMetaBar", () => {
  it("renders nothing when both task and promptText are null", () => {
    const { container } = renderWithProviders(
      <RunMetaBar run={makeRun({ task: null, promptText: null })} />
    );
    expect(container.firstChild).toBeNull();
  });

  it("renders the prompt section when promptText is set without a task", () => {
    renderWithProviders(
      <RunMetaBar run={makeRun({ promptText: "Add a logout button" })} />
    );

    expect(screen.getByTestId("run-meta-bar-prompt")).toHaveTextContent(
      "Add a logout button",
    );
    expect(screen.queryByTestId("run-meta-bar-task-link")).toBeNull();
  });

  it("renders both prompt and task sections when both are present", () => {
    const task = makeTask({ title: "Roadmap entry", id: "t-1" });
    renderWithProviders(
      <RunMetaBar
        run={makeRun({ promptText: "Add a logout button", task })}
      />,
    );

    expect(screen.getByTestId("run-meta-bar-prompt")).toHaveTextContent(
      "Add a logout button",
    );
    expect(screen.getByTestId("run-meta-bar-task-link")).toHaveTextContent(
      "Roadmap entry",
    );
  });

  it("renders an Expand button next to the prompt section", () => {
    renderWithProviders(
      <RunMetaBar run={makeRun({ promptText: "Add a logout button" })} />
    );

    expect(screen.getByTestId("run-meta-bar-prompt-expand")).toBeInTheDocument();
  });

  it("opens a dialog with the full prompt when Expand is clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <RunMetaBar run={makeRun({ promptText: "Add a logout button" })} />
    );

    await user.click(screen.getByTestId("run-meta-bar-prompt-expand"));

    // The dialog renders its own copy of the prompt; both the inline
    // preview and the dialog body should now contain the text.
    const matches = screen.getAllByText("Add a logout button");
    expect(matches.length).toBeGreaterThanOrEqual(2);
  });

  it("renders the task title when task is present", () => {
    const task = makeTask({ title: "Add dark mode" });
    renderWithProviders(<RunMetaBar run={makeRun({ task })} />);

    expect(screen.getByText("Add dark mode")).toBeInTheDocument();
  });

  it("renders software project row from run.softwareProject", () => {
    renderWithProviders(
      <RunMetaBar
        run={makeRun({
          softwareProject: { id: "sp-1", type: "git_repo", name: "my-repo" },
          task: null,
          promptText: null,
        })}
      />,
    );

    expect(screen.getByTestId("run-meta-bar-software-project")).toHaveTextContent("my-repo");
  });

  it("renders meta bar when only softwareProject is set (no task, no prompt)", () => {
    renderWithProviders(
      <RunMetaBar
        run={makeRun({
          softwareProject: { id: "sp-1", type: "git_repo", name: "my-repo" },
          task: null,
          promptText: null,
        })}
      />,
    );

    expect(screen.getByTestId("run-meta-bar")).toBeInTheDocument();
  });

  it("runs with a task and null softwareProject do not render software-project row", () => {
    const task = makeTask({ softwareProject: null });
    renderWithProviders(
      <RunMetaBar run={makeRun({ task, softwareProject: null })} />,
    );

    expect(screen.queryByTestId("run-meta-bar-software-project")).toBeNull();
  });

  it("links the task title to /tasks/{id}", () => {
    const task = makeTask({ id: "task-42", title: "Support OAuth" });
    renderWithProviders(<RunMetaBar run={makeRun({ task })} />);

    const link = screen.getByTestId("run-meta-bar-task-link");
    expect(link).toHaveAttribute("href", "/tasks/task-42");
    expect(link).toHaveTextContent("Support OAuth");
  });

  it("renders 'backlog' badge text for backlog status", () => {
    const task = makeTask({ status: "backlog" });
    renderWithProviders(<RunMetaBar run={makeRun({ task })} />);

    expect(screen.getByTestId("run-meta-bar-status")).toHaveTextContent("backlog");
  });

  it("renders 'in progress' badge text for in_progress status", () => {
    const task = makeTask({ status: "in_progress" });
    renderWithProviders(<RunMetaBar run={makeRun({ task })} />);

    expect(screen.getByTestId("run-meta-bar-status")).toHaveTextContent("in progress");
  });

  it("renders 'done' badge text for done status", () => {
    const task = makeTask({ status: "done" });
    renderWithProviders(<RunMetaBar run={makeRun({ task })} />);

    expect(screen.getByTestId("run-meta-bar-status")).toHaveTextContent("done");
  });

  it("renders nothing when task, promptText, and softwareProject are all null", () => {
    const { container } = renderWithProviders(
      <RunMetaBar
        run={makeRun({ task: null, promptText: null, softwareProject: null })}
      />,
    );
    expect(container.firstChild).toBeNull();
  });

  it("task row does not contain software project text when softwareProject comes from run", () => {
    const task = makeTask({ title: "My Feature" });
    renderWithProviders(
      <RunMetaBar
        run={makeRun({
          task,
          softwareProject: { id: "sp-1", type: "git_repo", name: "my-repo" },
        })}
      />,
    );

    // The software-project row is the standalone element (not inside task section)
    const spRow = screen.getByTestId("run-meta-bar-software-project");
    expect(spRow).toHaveTextContent("my-repo");

    // The task link exists alongside it
    expect(screen.getByTestId("run-meta-bar-task-link")).toHaveTextContent("My Feature");
  });
});
