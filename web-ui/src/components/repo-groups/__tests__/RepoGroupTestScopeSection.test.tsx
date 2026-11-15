import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import RepoGroupTestScopeSection from "@/components/repo-groups/RepoGroupTestScopeSection";
import type { RepoGroupMember, RepoGroupTestScopeRequest } from "@/lib/types";

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
    expect(screen.queryByText("alpha")).not.toBeInTheDocument();
  });

  it("togglingOnShowsOneRowPerMember", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(<RepoGroupTestScopeSection members={members} value={off} onChange={onChange} />);

    await user.click(screen.getByLabelText("Test only affected repos"));

    expect(onChange).toHaveBeenCalledWith({ affectedOnly: true, dependencies: [] });
  });

  it("showsOneRowPerMemberWhenOn", () => {
    render(<RepoGroupTestScopeSection members={members} value={on} onChange={vi.fn()} />);

    for (const name of ["alpha", "beta", "gamma"]) {
      expect(screen.getByRole("group", { name })).toBeInTheDocument();
    }
  });

  it("rowOptionsExcludeTheMemberItself", () => {
    render(<RepoGroupTestScopeSection members={members} value={on} onChange={vi.fn()} />);

    expect(screen.queryByLabelText("alpha depends on alpha")).not.toBeInTheDocument();
    expect(screen.getByLabelText("alpha depends on beta")).toBeInTheDocument();
    expect(screen.getByLabelText("alpha depends on gamma")).toBeInTheDocument();
  });

  it("selectingADependencyCallsOnChange", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(<RepoGroupTestScopeSection members={members} value={on} onChange={onChange} />);

    await user.click(screen.getByLabelText("alpha depends on beta"));

    expect(onChange).toHaveBeenCalledWith({
      affectedOnly: true,
      dependencies: [{ repoId: "r1", dependsOnRepoIds: ["r2"] }],
    });
  });

  it("unselectingTheLastDependencyDropsTheRow", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    const value = {
      affectedOnly: true,
      dependencies: [{ repoId: "r1", dependsOnRepoIds: ["r2"] }],
    };
    render(<RepoGroupTestScopeSection members={members} value={value} onChange={onChange} />);

    expect(screen.getByLabelText("alpha depends on beta")).toBeChecked();
    await user.click(screen.getByLabelText("alpha depends on beta"));

    expect(onChange).toHaveBeenCalledWith({ affectedOnly: true, dependencies: [] });
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
