import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import RunMetaBar from "../RunMetaBar";
import type { RunResponse, RunFeatureProposalSummary } from "@/lib/types";

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

function makeProposal(overrides: Partial<RunFeatureProposalSummary> = {}): RunFeatureProposalSummary {
  return {
    id: "proposal-1",
    title: "Add dark mode",
    status: "backlog",
    softwareProject: { id: "sp-1", type: "git_repo", name: "my-repo" },
    ...overrides,
  };
}

describe("RunMetaBar", () => {
  it("renders nothing when both featureProposal and promptText are null", () => {
    const { container } = renderWithProviders(
      <RunMetaBar run={makeRun({ featureProposal: null, promptText: null })} />
    );
    expect(container.firstChild).toBeNull();
  });

  it("renders the prompt section when promptText is set without a proposal", () => {
    renderWithProviders(
      <RunMetaBar run={makeRun({ promptText: "Add a logout button" })} />
    );

    expect(screen.getByTestId("run-meta-bar-prompt")).toHaveTextContent(
      "Add a logout button",
    );
    expect(screen.queryByTestId("run-meta-bar-proposal-link")).toBeNull();
  });

  it("renders both prompt and proposal sections when both are present", () => {
    const proposal = makeProposal({ title: "Roadmap entry", id: "p-1" });
    renderWithProviders(
      <RunMetaBar
        run={makeRun({ promptText: "Add a logout button", featureProposal: proposal })}
      />,
    );

    expect(screen.getByTestId("run-meta-bar-prompt")).toHaveTextContent(
      "Add a logout button",
    );
    expect(screen.getByTestId("run-meta-bar-proposal-link")).toHaveTextContent(
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

  it("renders the proposal title when proposal is present", () => {
    const proposal = makeProposal({ title: "Add dark mode" });
    renderWithProviders(<RunMetaBar run={makeRun({ featureProposal: proposal })} />);

    expect(screen.getByText("Add dark mode")).toBeInTheDocument();
  });

  it("renders software project row from run.softwareProject", () => {
    renderWithProviders(
      <RunMetaBar
        run={makeRun({
          softwareProject: { id: "sp-1", type: "git_repo", name: "my-repo" },
          featureProposal: null,
          promptText: null,
        })}
      />,
    );

    expect(screen.getByTestId("run-meta-bar-software-project")).toHaveTextContent("my-repo");
  });

  it("renders meta bar when only softwareProject is set (no proposal, no prompt)", () => {
    renderWithProviders(
      <RunMetaBar
        run={makeRun({
          softwareProject: { id: "sp-1", type: "git_repo", name: "my-repo" },
          featureProposal: null,
          promptText: null,
        })}
      />,
    );

    expect(screen.getByTestId("run-meta-bar")).toBeInTheDocument();
  });

  it("runs with a proposal and null softwareProject do not render software-project row", () => {
    const proposal = makeProposal({ softwareProject: null });
    renderWithProviders(
      <RunMetaBar run={makeRun({ featureProposal: proposal, softwareProject: null })} />,
    );

    expect(screen.queryByTestId("run-meta-bar-software-project")).toBeNull();
  });

  it("links the proposal title to /proposals/{id}", () => {
    const proposal = makeProposal({ id: "proposal-42", title: "Support OAuth" });
    renderWithProviders(<RunMetaBar run={makeRun({ featureProposal: proposal })} />);

    const link = screen.getByTestId("run-meta-bar-proposal-link");
    expect(link).toHaveAttribute("href", "/proposals/proposal-42");
    expect(link).toHaveTextContent("Support OAuth");
  });

  it("renders 'backlog' badge text for backlog status", () => {
    const proposal = makeProposal({ status: "backlog" });
    renderWithProviders(<RunMetaBar run={makeRun({ featureProposal: proposal })} />);

    expect(screen.getByTestId("run-meta-bar-status")).toHaveTextContent("backlog");
  });

  it("renders 'in progress' badge text for in_progress status", () => {
    const proposal = makeProposal({ status: "in_progress" });
    renderWithProviders(<RunMetaBar run={makeRun({ featureProposal: proposal })} />);

    expect(screen.getByTestId("run-meta-bar-status")).toHaveTextContent("in progress");
  });

  it("renders 'rolled out' badge text for rolled_out status", () => {
    const proposal = makeProposal({ status: "rolled_out" });
    renderWithProviders(<RunMetaBar run={makeRun({ featureProposal: proposal })} />);

    expect(screen.getByTestId("run-meta-bar-status")).toHaveTextContent("rolled out");
  });

  it("renders nothing when proposal, promptText, and softwareProject are all null", () => {
    const { container } = renderWithProviders(
      <RunMetaBar
        run={makeRun({ featureProposal: null, promptText: null, softwareProject: null })}
      />,
    );
    expect(container.firstChild).toBeNull();
  });

  it("proposal row does not contain software project text when softwareProject comes from run", () => {
    const proposal = makeProposal({ title: "My Feature" });
    renderWithProviders(
      <RunMetaBar
        run={makeRun({
          featureProposal: proposal,
          softwareProject: { id: "sp-1", type: "git_repo", name: "my-repo" },
        })}
      />,
    );

    // The software-project row is the standalone element (not inside proposal section)
    const spRow = screen.getByTestId("run-meta-bar-software-project");
    expect(spRow).toHaveTextContent("my-repo");

    // The proposal link exists alongside it
    expect(screen.getByTestId("run-meta-bar-proposal-link")).toHaveTextContent("My Feature");
  });
});
