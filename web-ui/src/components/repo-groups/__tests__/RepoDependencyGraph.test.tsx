import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import RepoDependencyGraph from "@/components/repo-groups/RepoDependencyGraph";
import { connectInShell as connect, shellProps } from "@/__tests__/reactFlowShell";
import type { RepoDependency, RepoGroupMember } from "@/lib/types";

vi.mock("@xyflow/react", async () => (await import("@/__tests__/reactFlowShell")).reactFlowShell);

const members: RepoGroupMember[] = [
  { gitRepoId: "r1", name: "alpha", position: 0 },
  { gitRepoId: "r2", name: "beta", position: 1 },
  { gitRepoId: "r3", name: "gamma", position: 2 },
];

async function renderGraph(dependencies: RepoDependency[]) {
  const onChange = vi.fn();
  render(<RepoDependencyGraph members={members} dependencies={dependencies} onChange={onChange} />);
  await waitFor(() =>
    expect(screen.getByTestId("repo-dependency-graph")).toHaveAttribute("data-layout-ready", "true"),
  );
  return onChange;
}

describe("RepoDependencyGraph", () => {
  beforeEach(() => vi.clearAllMocks());

  it("rendersOneBoxPerMemberAndOneRemovableArrowPerDependency", async () => {
    await renderGraph([{ repoId: "r1", dependsOnRepoIds: ["r2"] }]);

    for (const name of ["alpha", "beta", "gamma"]) {
      expect(screen.getByText(name)).toBeInTheDocument();
    }
    expect(screen.getByRole("button", { name: "Remove: alpha depends on beta" })).toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: /^Remove:/ })).toHaveLength(1);
  });

  it("connectingTwoReposAddsTheDependency", async () => {
    const onChange = await renderGraph([]);

    connect("r1", "r2");

    expect(onChange).toHaveBeenCalledWith([{ repoId: "r1", dependsOnRepoIds: ["r2"] }]);
  });

  it("connectingIntoALoopIsRefusedAndNamesTheLoop", async () => {
    const onChange = await renderGraph([
      { repoId: "r1", dependsOnRepoIds: ["r2"] },
      { repoId: "r2", dependsOnRepoIds: ["r3"] },
    ]);

    connect("r3", "r1");

    expect(onChange).not.toHaveBeenCalled();
    expect(screen.getByRole("alert")).toHaveTextContent(
      "gamma can't depend on alpha: that would make a loop (gamma → alpha → beta → gamma).",
    );
  });

  it("aSuccessfulConnectionClearsTheRefusal", async () => {
    const onChange = await renderGraph([{ repoId: "r1", dependsOnRepoIds: ["r2"] }]);
    connect("r2", "r1");
    expect(screen.getByRole("alert")).toBeInTheDocument();

    connect("r3", "r1");

    expect(onChange).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("offersNoConnectionToItselfOrOneThatExists", async () => {
    await renderGraph([{ repoId: "r1", dependsOnRepoIds: ["r2"] }]);
    const valid = (source: string, target: string) =>
      shellProps().isValidConnection({ source, target, sourceHandle: "out", targetHandle: "in" });

    expect(valid("r1", "r1")).toBe(false);
    expect(valid("r1", "r2")).toBe(false);
    expect(valid("r1", "r3")).toBe(true);
  });

  it("removeButtonDropsTheArrow", async () => {
    const user = userEvent.setup();
    const onChange = await renderGraph([{ repoId: "r1", dependsOnRepoIds: ["r2", "r3"] }]);

    await user.click(screen.getByRole("button", { name: "Remove: alpha depends on beta" }));

    expect(onChange).toHaveBeenCalledWith([{ repoId: "r1", dependsOnRepoIds: ["r3"] }]);
  });

  it("drawsNoArrowToARepoOutsideTheGroup", async () => {
    await renderGraph([{ repoId: "r1", dependsOnRepoIds: ["gone"] }]);

    expect(screen.queryByRole("button", { name: /^Remove:/ })).not.toBeInTheDocument();
  });
});
