import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import EpicDependenciesSection from "@/components/roadmap/EpicDependenciesSection";
import type { EpicDependencyResponse, EpicResponse } from "@/lib/types";

vi.mock("@/lib/api", () => ({
  api: {
    get: vi.fn(),
    getPage: vi.fn(),
    post: vi.fn(),
    delete: vi.fn(),
  },
  ApiError: class ApiError extends Error {},
}));

vi.mock("@/lib/oidc", () => ({
  isAuthEnabled: vi.fn(() => true),
}));

vi.mock("@/components/AuthProvider", () => ({
  useAuth: vi.fn(),
}));

import { api } from "@/lib/api";
import { useAuth } from "@/components/AuthProvider";

const mockApi = api as unknown as {
  get: ReturnType<typeof vi.fn>;
  getPage: ReturnType<typeof vi.fn>;
  post: ReturnType<typeof vi.fn>;
  delete: ReturnType<typeof vi.fn>;
};
const mockUseAuth = useAuth as ReturnType<typeof vi.fn>;

function asRole(role: "org-admin" | "operator" | "viewer") {
  mockUseAuth.mockReturnValue({ role, platformAdmin: false });
}

function makeEpic(id: string, title: string): EpicResponse {
  return {
    id,
    title,
    description: "d",
    motivation: null,
    stage: "backlog",
    priority: "medium",
    targetDate: null,
    progress: { totalTasks: 0, doneTasks: 0, startedTasks: 0 },
    softwareProject: { id: "r1", type: "git_repo", name: "backend-api" },
    repos: [],
    createdAt: "2026-04-01T00:00:00Z",
    updatedAt: "2026-04-01T00:00:00Z",
    readyItemCount: 0,
    milestone: null,
  };
}

const epics = [
  makeEpic("epic-1", "Add dark mode"),
  makeEpic("epic-2", "Auth Overhaul"),
  makeEpic("epic-3", "Billing"),
];

const blockedByEpic: EpicDependencyResponse = {
  edgeId: "dep-1",
  direction: "BLOCKED",
  itemType: "epic",
  itemId: "epic-2",
  title: "Auth Overhaul",
  epicId: "epic-2",
  epicTitle: "Auth Overhaul",
};

const blocksTask: EpicDependencyResponse = {
  edgeId: "dep-2",
  direction: "BLOCKING",
  itemType: "task",
  itemId: "task-9",
  title: "Invoice export",
  epicId: "epic-3",
  epicTitle: "Billing",
};

beforeEach(() => {
  vi.clearAllMocks();
  asRole("org-admin");
  mockApi.get.mockResolvedValue([blockedByEpic, blocksTask]);
  mockApi.getPage.mockResolvedValue({ content: epics, totalElements: 3, totalPages: 1, number: 0 });
  mockApi.post.mockResolvedValue({});
  mockApi.delete.mockResolvedValue(undefined);
});

async function renderSection() {
  renderWithProviders(<EpicDependenciesSection epicId="epic-1" />);
  await screen.findAllByTestId("epic-dependency-row");
}

describe("EpicDependenciesSection", () => {
  it("lists each edge on its side, linking to the other item's own page", async () => {
    await renderSection();

    expect(mockApi.get).toHaveBeenCalledWith("/epics/epic-1/dependencies");

    const blockedBy = screen.getByTestId("epic-blocked-by-list");
    expect(within(blockedBy).getByRole("link", { name: "Auth Overhaul" })).toHaveAttribute(
      "href",
      "/roadmap/epics/epic-2",
    );
    expect(within(blockedBy).getByTestId("level-badge-epic")).toBeInTheDocument();

    const blocks = screen.getByTestId("epic-blocks-list");
    expect(within(blocks).getByRole("link", { name: "Invoice export" })).toHaveAttribute(
      "href",
      "/tasks/task-9",
    );
    expect(within(blocks).getByTestId("level-badge-task")).toBeInTheDocument();
    expect(within(blocks).getByText("in Billing")).toBeInTheDocument();
  });

  it("shows an empty state for a side with no edges", async () => {
    mockApi.get.mockResolvedValue([blockedByEpic]);
    await renderSection();

    expect(screen.getByText("This Epic blocks nothing.")).toBeInTheDocument();
    expect(screen.queryByText("Nothing blocks this Epic.")).not.toBeInTheDocument();
  });

  it("hides the add and remove controls from a viewer", async () => {
    asRole("viewer");
    await renderSection();

    expect(screen.queryByTestId("epic-blocked-by-select")).not.toBeInTheDocument();
    expect(screen.queryByTestId("epic-blocks-add")).not.toBeInTheDocument();
    expect(screen.queryByTestId("epic-dependency-remove")).not.toBeInTheDocument();
  });

  it("lets an operator add but only an admin remove", async () => {
    asRole("operator");
    await renderSection();

    expect(screen.getByTestId("epic-blocked-by-select")).toBeInTheDocument();
    expect(screen.getByTestId("epic-blocks-add")).toBeInTheDocument();
    expect(screen.queryByTestId("epic-dependency-remove")).not.toBeInTheDocument();
  });

  it("adds a blocker Epic, offering neither this Epic nor one already blocking it", async () => {
    const user = userEvent.setup();
    await renderSection();

    await user.click(screen.getByTestId("epic-blocked-by-select"));
    const billing = await screen.findByRole("option", { name: "Billing · backend-api" });
    // Already on the "Blocked by" side, and the Epic itself — neither is a valid new blocker.
    expect(screen.getAllByRole("option").map((o) => o.textContent)).toEqual(["Billing · backend-api"]);
    await user.click(billing);

    await user.click(screen.getByTestId("epic-blocked-by-add"));

    await waitFor(() =>
      expect(mockApi.post).toHaveBeenCalledWith("/dependencies", {
        blockingItemType: "epic",
        blockingItemId: "epic-3",
        blockedItemType: "epic",
        blockedItemId: "epic-1",
      }),
    );
  });

  it("adds an Epic this one blocks, with this Epic as the blocking side", async () => {
    const user = userEvent.setup();
    await renderSection();

    await user.click(screen.getByTestId("epic-blocks-select"));
    await user.click(await screen.findByText("Auth Overhaul · backend-api"));
    await user.click(screen.getByTestId("epic-blocks-add"));

    await waitFor(() =>
      expect(mockApi.post).toHaveBeenCalledWith("/dependencies", {
        blockingItemType: "epic",
        blockingItemId: "epic-1",
        blockedItemType: "epic",
        blockedItemId: "epic-2",
      }),
    );
  });

  it("keeps Add disabled until an Epic is picked", async () => {
    await renderSection();

    expect(screen.getByTestId("epic-blocked-by-add")).toBeDisabled();
    expect(screen.getByTestId("epic-blocks-add")).toBeDisabled();
  });

  it("removes an edge by its id", async () => {
    const user = userEvent.setup();
    await renderSection();

    const blockedBy = screen.getByTestId("epic-blocked-by-list");
    await user.click(within(blockedBy).getByTestId("epic-dependency-remove"));

    await waitFor(() => expect(mockApi.delete).toHaveBeenCalledWith("/dependencies/dep-1"));
  });
});
