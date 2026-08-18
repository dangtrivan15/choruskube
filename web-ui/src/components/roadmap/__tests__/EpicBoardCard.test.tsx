import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import EpicBoardCard from "@/components/roadmap/EpicBoardCard";
import type { EpicResponse, StoryResponse } from "@/lib/types";

vi.mock("@/lib/api", () => ({
  api: {
    get: vi.fn(),
    getPage: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    patch: vi.fn(),
    delete: vi.fn(),
  },
}));

import { api } from "@/lib/api";

const mockApi = api as unknown as {
  get: ReturnType<typeof vi.fn>;
};

function makeEpic(overrides: Partial<EpicResponse> = {}): EpicResponse {
  return {
    id: "epic-1",
    title: "Add dark mode",
    description: "Add a dark theme",
    motivation: null,
    stage: "backlog",
    priority: "medium",
    targetDate: null,
    progress: { totalTasks: 3, doneTasks: 1, startedTasks: 1 },
    softwareProject: { id: "r1", type: "git_repo", name: "backend-api" },
    repos: [],
    createdAt: "2026-04-01T00:00:00Z",
    updatedAt: "2026-04-01T00:00:00Z",
    readyItemCount: 0,
    milestone: null,
    ...overrides,
  };
}

function makeStory(overrides: Partial<StoryResponse> = {}): StoryResponse {
  return {
    id: "story-1",
    epicId: "epic-1",
    title: "Dark theme toggle",
    description: "desc",
    stage: "backlog",
    priority: "medium",
    targetDate: null,
    readiness: null,
    progress: { totalTasks: 2, doneTasks: 1, startedTasks: 1 },
    createdAt: "2026-04-01T00:00:00Z",
    updatedAt: "2026-04-01T00:00:00Z",
    ...overrides,
  };
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe("EpicBoardCard", () => {
  it("renders the title and 'X of Y tasks complete' progress", () => {
    renderWithProviders(<EpicBoardCard epic={makeEpic()} />);
    expect(screen.getByTestId("epic-board-card-title")).toHaveTextContent("Add dark mode");
    expect(screen.getByTestId("epic-board-card-progress")).toHaveTextContent(
      "1 of 3 tasks complete"
    );
  });

  it("renders the readyItemCount badge with the correct count next to the progress figure", () => {
    renderWithProviders(<EpicBoardCard epic={makeEpic({ readyItemCount: 2 })} />);
    expect(screen.getByTestId("epic-board-card-ready-count")).toHaveTextContent("2 ready");
  });

  it("does not render the readyItemCount badge when there are no ready items", () => {
    renderWithProviders(<EpicBoardCard epic={makeEpic({ readyItemCount: 0 })} />);
    expect(screen.queryByTestId("epic-board-card-ready-count")).not.toBeInTheDocument();
  });

  it("does not fetch stories until expanded", () => {
    renderWithProviders(<EpicBoardCard epic={makeEpic()} />);
    expect(mockApi.get).not.toHaveBeenCalled();
    expect(screen.queryByTestId("epic-board-card-stories")).not.toBeInTheDocument();
  });

  it("expanding shows Story rows with their own mini progress", async () => {
    mockApi.get.mockResolvedValue([makeStory()]);
    renderWithProviders(<EpicBoardCard epic={makeEpic()} />);
    const user = userEvent.setup();

    await user.click(screen.getByTestId("epic-board-card-expand"));

    await waitFor(() => expect(screen.getByTestId("epic-board-card-story")).toBeInTheDocument());
    expect(screen.getByTestId("epic-board-card-story")).toHaveTextContent("Dark theme toggle");
    expect(screen.getByTestId("epic-board-card-story-progress")).toHaveTextContent("1/2");
    expect(mockApi.get).toHaveBeenCalledWith("/epics/epic-1/stories");
  });

  it("shows a blocked badge on a Story whose readiness is BLOCKED", async () => {
    mockApi.get.mockResolvedValue([makeStory({ readiness: "BLOCKED" })]);
    renderWithProviders(<EpicBoardCard epic={makeEpic()} />);
    const user = userEvent.setup();

    await user.click(screen.getByTestId("epic-board-card-expand"));

    await waitFor(() => expect(screen.getByTestId("epic-board-card-story-blocked")).toBeInTheDocument());
  });

  it("does not show a blocked badge on a Story whose readiness is READY", async () => {
    mockApi.get.mockResolvedValue([makeStory({ readiness: "READY" })]);
    renderWithProviders(<EpicBoardCard epic={makeEpic()} />);
    const user = userEvent.setup();

    await user.click(screen.getByTestId("epic-board-card-expand"));

    await waitFor(() => expect(screen.getByTestId("epic-board-card-story")).toBeInTheDocument());
    expect(screen.queryByTestId("epic-board-card-story-blocked")).not.toBeInTheDocument();
  });

  it("collapsing hides Stories, and re-expanding reuses the cached query (fetched once)", async () => {
    mockApi.get.mockResolvedValue([makeStory()]);
    renderWithProviders(<EpicBoardCard epic={makeEpic()} />);
    const user = userEvent.setup();

    // Expand
    await user.click(screen.getByTestId("epic-board-card-expand"));
    await waitFor(() => expect(screen.getByTestId("epic-board-card-story")).toBeInTheDocument());
    expect(mockApi.get).toHaveBeenCalledTimes(1);

    // Collapse
    await user.click(screen.getByTestId("epic-board-card-expand"));
    expect(screen.queryByTestId("epic-board-card-stories")).not.toBeInTheDocument();

    // Re-expand — no duplicate fetch, cache is reused.
    await user.click(screen.getByTestId("epic-board-card-expand"));
    await waitFor(() => expect(screen.getByTestId("epic-board-card-story")).toBeInTheDocument());
    expect(mockApi.get).toHaveBeenCalledTimes(1);
  });

  // --- Focus wiring (§3.1/§3.3/§3.4) ---

  it("isFocused applies the highlight styling", () => {
    renderWithProviders(<EpicBoardCard epic={makeEpic()} isFocused />);
    expect(screen.getByTestId("epic-board-card")).toHaveAttribute("data-focused", "true");
    expect(screen.getByTestId("epic-board-card").className).toMatch(/ring-2/);
  });

  it("does not apply the highlight styling when isFocused is false/omitted", () => {
    renderWithProviders(<EpicBoardCard epic={makeEpic()} />);
    expect(screen.getByTestId("epic-board-card")).toHaveAttribute("data-focused", "false");
  });

  it("clicking the card body calls onFocus with the Epic's id", async () => {
    const onFocus = vi.fn();
    renderWithProviders(<EpicBoardCard epic={makeEpic({ id: "epic-9" })} onFocus={onFocus} />);
    const user = userEvent.setup();

    await user.click(screen.getByTestId("epic-board-card-title"));

    expect(onFocus).toHaveBeenCalledWith("epic-9");
  });

  it("clicking the expand chevron does not call onFocus", async () => {
    const onFocus = vi.fn();
    renderWithProviders(<EpicBoardCard epic={makeEpic()} onFocus={onFocus} />);
    const user = userEvent.setup();

    await user.click(screen.getByTestId("epic-board-card-expand"));

    expect(onFocus).not.toHaveBeenCalled();
  });

  it("initiallyExpanded renders the Story list open on mount with no click, and fetches Stories immediately", async () => {
    mockApi.get.mockResolvedValue([makeStory()]);
    renderWithProviders(<EpicBoardCard epic={makeEpic()} initiallyExpanded />);

    // Inverse of "does not fetch stories until expanded": fetch happens without any user
    // interaction at all.
    await waitFor(() => expect(mockApi.get).toHaveBeenCalledWith("/epics/epic-1/stories"));
    expect(screen.getByTestId("epic-board-card-stories")).toBeInTheDocument();
  });

  it("focusedStoryId matching a loaded Story highlights that row only", async () => {
    mockApi.get.mockResolvedValue([
      makeStory({ id: "story-a", title: "Story A" }),
      makeStory({ id: "story-b", title: "Story B" }),
    ]);
    renderWithProviders(<EpicBoardCard epic={makeEpic()} initiallyExpanded focusedStoryId="story-b" />);

    await waitFor(() => expect(screen.getAllByTestId("epic-board-card-story")).toHaveLength(2));
    const rows = screen.getAllByTestId("epic-board-card-story");
    const rowA = rows.find((r) => r.getAttribute("data-story-id") === "story-a")!;
    const rowB = rows.find((r) => r.getAttribute("data-story-id") === "story-b")!;

    expect(rowA).toHaveAttribute("data-focused", "false");
    expect(rowB).toHaveAttribute("data-focused", "true");
  });

  it("a focusedStoryId matching no loaded Story highlights nothing and does not throw", async () => {
    mockApi.get.mockResolvedValue([makeStory({ id: "story-a", title: "Story A" })]);

    expect(() =>
      renderWithProviders(
        <EpicBoardCard epic={makeEpic()} initiallyExpanded focusedStoryId="story-does-not-exist" />,
      ),
    ).not.toThrow();

    await waitFor(() => expect(screen.getByTestId("epic-board-card-story")).toBeInTheDocument());
    expect(screen.getByTestId("epic-board-card-story")).toHaveAttribute("data-focused", "false");
  });

  it("cardRef receives the underlying Card DOM node, and dnd-kit's drag wiring is unaffected", () => {
    const cardRef = vi.fn();
    renderWithProviders(<EpicBoardCard epic={makeEpic()} cardRef={cardRef} />);

    expect(cardRef).toHaveBeenCalledWith(expect.any(HTMLElement));

    // Regression coverage for the merged-ref change: dnd-kit's own `attributes` (spread onto the
    // same Card element) must still land on the DOM node, not get clobbered by the ref merge.
    const card = screen.getByTestId("epic-board-card");
    expect(card).toHaveAttribute("aria-roledescription", "draggable");
    expect(card).toHaveAttribute("tabindex", "0");
  });
});
