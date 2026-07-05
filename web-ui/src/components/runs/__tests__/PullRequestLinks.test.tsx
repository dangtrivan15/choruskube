import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/__tests__/test-utils";
import PullRequestLinks from "../PullRequestLinks";
import type { RunPullRequestResponse } from "@/lib/types";

function makePr(overrides: Partial<RunPullRequestResponse> = {}): RunPullRequestResponse {
  return {
    id: crypto.randomUUID(),
    workflowRunId: "run-1",
    gitRepoId: "repo-1",
    nodeExecutionId: null,
    prUrl: "https://github.com/org/repo/pull/1",
    prNumber: 1,
    title: "feat: add login",
    repoName: "backend-api",
    repoUrl: "https://github.com/org/repo",
    createdAt: new Date().toISOString(),
    ...overrides,
  };
}

describe("PullRequestLinks", () => {
  it("renders nothing when pullRequests is empty", () => {
    const { container } = renderWithProviders(
      <PullRequestLinks pullRequests={[]} />
    );
    expect(container.firstChild).toBeNull();
  });

  it("renders single PR with correct link and title", () => {
    const pr = makePr({
      prUrl: "https://github.com/org/backend/pull/42",
      title: "feat: add user auth",
      repoName: "backend",
    });

    renderWithProviders(<PullRequestLinks pullRequests={[pr]} />);

    expect(screen.getByText("Pull Requests")).toBeInTheDocument();
    const link = screen.getByRole("link", { name: /add user auth/i });
    expect(link).toHaveAttribute("href", "https://github.com/org/backend/pull/42");
    expect(link).toHaveAttribute("target", "_blank");
    expect(screen.getByText("backend")).toBeInTheDocument();
  });

  it("renders two PRs with companion indicator", () => {
    const pr1 = makePr({ repoName: "backend-api", title: "feat: API" });
    const pr2 = makePr({ repoName: "frontend-app", title: "feat: UI" });

    renderWithProviders(
      <PullRequestLinks pullRequests={[pr1, pr2]} />
    );

    expect(screen.getByText("backend-api")).toBeInTheDocument();
    expect(screen.getByText("frontend-app")).toBeInTheDocument();
    expect(screen.getByText(/2 companion PRs/)).toBeInTheDocument();
  });

  it("displays 'repo' fallback when repoName is null", () => {
    const pr = makePr({ repoName: null });

    renderWithProviders(<PullRequestLinks pullRequests={[pr]} />);

    expect(screen.getByText("repo")).toBeInTheDocument();
  });

  it("displays PR number fallback when title is null", () => {
    const pr = makePr({ title: null, prNumber: 99 });

    renderWithProviders(<PullRequestLinks pullRequests={[pr]} />);

    expect(screen.getByText("PR #99")).toBeInTheDocument();
  });

  it("renders N PRs correctly", () => {
    const prs = [
      makePr({ repoName: "svc-a", title: "PR A" }),
      makePr({ repoName: "svc-b", title: "PR B" }),
      makePr({ repoName: "svc-c", title: "PR C" }),
    ];

    renderWithProviders(<PullRequestLinks pullRequests={prs} />);

    expect(screen.getByText("svc-a")).toBeInTheDocument();
    expect(screen.getByText("svc-b")).toBeInTheDocument();
    expect(screen.getByText("svc-c")).toBeInTheDocument();
    expect(screen.getByText(/3 companion PRs/)).toBeInTheDocument();
  });

  it("applies mobile-stack + desktop-inline classes on the link", () => {
    const pr = makePr({ title: "feat: add user auth" });

    renderWithProviders(<PullRequestLinks pullRequests={[pr]} />);

    const link = screen.getByRole("link", { name: /add user auth/i });
    expect(link).toHaveClass("w-full");
    expect(link).toHaveClass("min-w-0");
    expect(link).toHaveClass("max-w-full");
    expect(link).toHaveClass("sm:w-auto");
    expect(link).toHaveClass("sm:max-w-md");
  });

  it("applies title and aria-label with PR title", () => {
    const pr = makePr({ title: "feat: add user auth" });

    renderWithProviders(<PullRequestLinks pullRequests={[pr]} />);

    const link = screen.getByRole("link", { name: /add user auth/i });
    expect(link).toHaveAttribute("title", "feat: add user auth");
    expect(link).toHaveAttribute(
      "aria-label",
      "Open feat: add user auth on GitHub"
    );
  });

  it("falls back to PR # in title and aria-label when title is null", () => {
    const pr = makePr({ title: null, prNumber: 99 });

    renderWithProviders(<PullRequestLinks pullRequests={[pr]} />);

    const link = screen.getByRole("link", { name: /Open PR #99 on GitHub/i });
    expect(link).toHaveAttribute("title", "PR #99");
    expect(link).toHaveAttribute("aria-label", "Open PR #99 on GitHub");
  });

  it("marks decorative icons as aria-hidden", () => {
    const pr = makePr({ title: "feat: add login" });

    const { container } = renderWithProviders(
      <PullRequestLinks pullRequests={[pr]} />
    );

    // Header GitPullRequest icon + per-PR ExternalLink icon should both
    // be marked aria-hidden so screen readers don't read them out.
    const hidden = container.querySelectorAll('[aria-hidden="true"]');
    // 1 header icon + 1 ExternalLink icon for the single PR = 2.
    expect(hidden.length).toBeGreaterThanOrEqual(2);
  });
});
