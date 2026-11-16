import { describe, it, expect, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import RepoGroupTestScopeSection from "@/components/repo-groups/RepoGroupTestScopeSection";
import { connectInShell } from "@/__tests__/reactFlowShell";
import type { RepoGroupMember, RepoGroupTestScopeRequest } from "@/lib/types";

vi.mock("@xyflow/react", async () => (await import("@/__tests__/reactFlowShell")).reactFlowShell);

const members: RepoGroupMember[] = [
  { gitRepoId: "r1", name: "alpha", position: 0 },
  { gitRepoId: "r2", name: "beta", position: 1 },
  { gitRepoId: "r3", name: "gamma", position: 2 },
];

const off: RepoGroupTestScopeRequest = { affectedOnly: false, dependencies: [] };
const on: RepoGroupTestScopeRequest = { affectedOnly: true, dependencies: [] };

describe("RepoGroupTestScopeSection", () => {
  it("rendersSwitchOffByDefault", () => {
    render(<RepoGroupTestScopeSection members={members} value={off} onChange={vi.fn()} />);

    expect(screen.getByRole("heading", { name: "Testing" })).toBeInTheDocument();
    expect(screen.getByLabelText("Test only affected repos")).not.toBeChecked();
    expect(
      screen.getByText(
        "Runs a repo's tests only when the run changed it or a repo it depends on.",
      ),
    ).toBeInTheDocument();
    expect(screen.queryByTestId("repo-dependency-graph")).not.toBeInTheDocument();
  });

  it("togglingOnCallsOnChange", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(<RepoGroupTestScopeSection members={members} value={off} onChange={onChange} />);

    await user.click(screen.getByLabelText("Test only affected repos"));

    expect(onChange).toHaveBeenCalledWith({ affectedOnly: true, dependencies: [] });
  });

  it("showsOneBoxPerMemberWhenOn", async () => {
    render(<RepoGroupTestScopeSection members={members} value={on} onChange={vi.fn()} />);

    await waitFor(() =>
      expect(screen.getByTestId("repo-dependency-graph")).toHaveAttribute("data-layout-ready", "true"),
    );
    for (const id of ["r1", "r2", "r3"]) {
      expect(screen.getByTestId(`flow-node-${id}`)).toBeInTheDocument();
    }
  });

  it("aGroupOfOneGetsAHintInsteadOfAGraph", () => {
    render(<RepoGroupTestScopeSection members={members.slice(0, 1)} value={on} onChange={vi.fn()} />);

    expect(screen.queryByTestId("repo-dependency-graph")).not.toBeInTheDocument();
    expect(
      screen.getByText("Add another repo to this group to draw dependencies between them."),
    ).toBeInTheDocument();
  });

  it("drawingAnArrowCallsOnChangeWithTheSwitchKept", async () => {
    const onChange = vi.fn();
    render(<RepoGroupTestScopeSection members={members} value={on} onChange={onChange} />);
    await waitFor(() =>
      expect(screen.getByTestId("repo-dependency-graph")).toHaveAttribute("data-layout-ready", "true"),
    );

    connectInShell("r1", "r2");

    expect(onChange).toHaveBeenCalledWith({
      affectedOnly: true,
      dependencies: [{ repoId: "r1", dependsOnRepoIds: ["r2"] }],
    });
  });

  it("offKeepsValues", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    const value = {
      affectedOnly: true,
      dependencies: [{ repoId: "r1", dependsOnRepoIds: ["r2"] }],
    };
    render(<RepoGroupTestScopeSection members={members} value={value} onChange={onChange} />);

    await user.click(screen.getByLabelText("Test only affected repos"));

    expect(onChange).toHaveBeenCalledWith({
      affectedOnly: false,
      dependencies: [{ repoId: "r1", dependsOnRepoIds: ["r2"] }],
    });
  });

  it("rendersError", () => {
    render(
      <RepoGroupTestScopeSection
        members={members}
        value={on}
        onChange={vi.fn()}
        error="Dependency cycle: alpha -> beta -> alpha"
      />,
    );

    expect(screen.getByRole("alert")).toHaveTextContent("Dependency cycle: alpha -> beta -> alpha");
  });
});
