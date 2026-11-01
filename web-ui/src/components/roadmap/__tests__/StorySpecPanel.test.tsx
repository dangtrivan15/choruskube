import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import StorySpecPanel from "@/components/roadmap/StorySpecPanel";
import type {
  StoryConflictResponse,
  StoryResponse,
  StorySpecResponse,
  StorySpecVersionSummary,
} from "@/lib/types";

const mockUseStorySpec = vi.fn();
const mockUseStorySpecVersions = vi.fn();
const mockUseStorySpecVersion = vi.fn();
const mockUseStoryConflicts = vi.fn();
const mockDesignMutate = vi.fn();
const mockReviseMutate = vi.fn();
const mockReviseReset = vi.fn();

vi.mock("@/hooks/useStorySpec", () => ({
  useStorySpec: (id: string | undefined) => mockUseStorySpec(id),
  useStorySpecVersions: (id: string | undefined) => mockUseStorySpecVersions(id),
  useStorySpecVersion: (id: string | undefined, v: number | null) => mockUseStorySpecVersion(id, v),
  useStoryConflicts: (id: string | undefined) => mockUseStoryConflicts(id),
  useDesignStory: () => ({ mutate: mockDesignMutate, isPending: false }),
  useReviseStory: () => ({
    mutate: mockReviseMutate,
    isPending: false,
    isError: false,
    error: null,
    reset: mockReviseReset,
  }),
}));

// Stands in for the real renderer so the test can see exactly what content was handed to it.
vi.mock("@/components/ui/MarkdownViewer", () => ({
  default: ({ content }: { content: string }) => <div data-testid="markdown-viewer">{content}</div>,
}));

function makeStory(overrides: Partial<StoryResponse> = {}): StoryResponse {
  return {
    id: "story-1",
    epicId: "epic-1",
    title: "Checkout",
    description: "desc",
    stage: "backlog",
    priority: "medium",
    targetDate: null,
    readiness: null,
    readyTaskCount: null,
    progress: { totalTasks: 0, doneTasks: 0, startedTasks: 0 },
    createdAt: "2026-10-01T00:00:00Z",
    updatedAt: "2026-10-01T00:00:00Z",
    specVersion: null,
    specUnderRevision: false,
    openDesignRunId: null,
    ...overrides,
  };
}

function makeSpec(overrides: Partial<StorySpecResponse> = {}): StorySpecResponse {
  return {
    storyId: "story-1",
    version: 2,
    content: "## Approach\nShip it in two Tasks.",
    approvedAt: "2026-10-20T00:00:00Z",
    runId: "design-run-2",
    ...overrides,
  };
}

const versions: StorySpecVersionSummary[] = [
  { version: 1, approvedAt: "2026-10-10T00:00:00Z", runId: "design-run-1" },
  { version: 2, approvedAt: "2026-10-20T00:00:00Z", runId: "design-run-2" },
];

function conflict(overrides: Partial<StoryConflictResponse> = {}): StoryConflictResponse {
  return {
    id: "conflict-1",
    taskRunId: "task-run-1",
    taskId: "task-1",
    taskTitle: "Wire the payment form",
    raisedAgainstVersion: 2,
    revisionRunId: "revision-run",
    createdAt: "2026-10-25T00:00:00Z",
    ...overrides,
  };
}

beforeEach(() => {
  vi.clearAllMocks();
  mockUseStorySpec.mockReturnValue({ data: null, isLoading: false, isError: false });
  mockUseStorySpecVersions.mockReturnValue({ data: [] });
  mockUseStorySpecVersion.mockReturnValue({ data: undefined, isLoading: false });
  mockUseStoryConflicts.mockReturnValue({ data: [] });
});

describe("StorySpecPanel", () => {
  describe("with no spec", () => {
    it("offers Design and shows no spec content", () => {
      renderWithProviders(<StorySpecPanel story={makeStory()} />);

      expect(screen.getByTestId("story-spec-panel")).toBeInTheDocument();
      expect(screen.getByTestId("story-spec-design-button")).toBeInTheDocument();
      expect(screen.queryByTestId("story-spec-revise-button")).not.toBeInTheDocument();
      expect(screen.queryByTestId("markdown-viewer")).not.toBeInTheDocument();
      expect(screen.queryByTestId("story-spec-version-select")).not.toBeInTheDocument();
    });

    it("does not ask the server for a spec the Story says it does not have", () => {
      renderWithProviders(<StorySpecPanel story={makeStory()} />);

      expect(mockUseStorySpec).toHaveBeenCalledWith(undefined);
    });

    it("starts a design for this Story when Design is clicked", async () => {
      const user = userEvent.setup();
      renderWithProviders(<StorySpecPanel story={makeStory()} />);

      await user.click(screen.getByTestId("story-spec-design-button"));

      expect(mockDesignMutate).toHaveBeenCalledWith("story-1");
    });

    it("links the first design run instead of offering Design while it is in flight", () => {
      renderWithProviders(<StorySpecPanel story={makeStory({ openDesignRunId: "design-run-1" })} />);

      expect(screen.queryByTestId("story-spec-design-button")).not.toBeInTheDocument();
      const inProgress = screen.getByTestId("story-spec-design-in-progress");
      expect(within(inProgress).getByRole("link")).toHaveAttribute("href", "/runs/design-run-1");
    });
  });

  describe("with a spec", () => {
    beforeEach(() => {
      mockUseStorySpec.mockReturnValue({ data: makeSpec(), isLoading: false, isError: false });
      mockUseStorySpecVersions.mockReturnValue({ data: versions });
    });

    it("renders the current spec through MarkdownViewer and offers Revise", () => {
      renderWithProviders(<StorySpecPanel story={makeStory({ specVersion: 2 })} />);

      expect(mockUseStorySpec).toHaveBeenCalledWith("story-1");
      expect(screen.getByTestId("markdown-viewer")).toHaveTextContent("Ship it in two Tasks.");
      expect(screen.getByTestId("story-spec-revise-button")).toBeInTheDocument();
      expect(screen.queryByTestId("story-spec-design-button")).not.toBeInTheDocument();
    });

    it("links the run that produced the version on screen", () => {
      renderWithProviders(<StorySpecPanel story={makeStory({ specVersion: 2 })} />);

      expect(screen.getByTestId("story-spec-run-link")).toHaveAttribute("href", "/runs/design-run-2");
    });

    it("shows an older version once it is picked", async () => {
      const user = userEvent.setup();
      mockUseStorySpecVersion.mockImplementation((_id: string | undefined, v: number | null) =>
        v === 1
          ? { data: makeSpec({ version: 1, content: "The first draft", runId: "design-run-1" }), isLoading: false }
          : { data: undefined, isLoading: false }
      );
      renderWithProviders(<StorySpecPanel story={makeStory({ specVersion: 2 })} />);

      expect(screen.getByTestId("story-spec-version-select")).toHaveTextContent("v2");
      await user.click(screen.getByTestId("story-spec-version-select"));
      await user.click(screen.getByTestId("story-spec-version-option-1"));

      expect(mockUseStorySpecVersion).toHaveBeenLastCalledWith("story-1", 1);
      expect(screen.getByTestId("markdown-viewer")).toHaveTextContent("The first draft");
      expect(screen.getByTestId("story-spec-run-link")).toHaveAttribute("href", "/runs/design-run-1");
    });

    it("sends the typed guidance when a revision is requested", async () => {
      const user = userEvent.setup();
      renderWithProviders(<StorySpecPanel story={makeStory({ specVersion: 2 })} />);

      await user.click(screen.getByTestId("story-spec-revise-button"));
      const submit = screen.getByTestId("story-spec-revise-submit");
      expect(submit).toBeDisabled();

      await user.type(screen.getByTestId("story-spec-revise-guidance"), "Split the API section");
      await user.click(submit);

      expect(mockReviseMutate).toHaveBeenCalledWith(
        { storyId: "story-1", guidance: "Split the API section" },
        expect.anything()
      );
    });

    it("clears a refused revision's error when the dialog is cancelled", async () => {
      const user = userEvent.setup();
      renderWithProviders(<StorySpecPanel story={makeStory({ specVersion: 2 })} />);

      await user.click(screen.getByTestId("story-spec-revise-button"));
      await user.click(screen.getByRole("button", { name: "Cancel" }));

      expect(mockReviseReset).toHaveBeenCalled();
    });

    it("follows a newer approval after the current version was picked", async () => {
      const user = userEvent.setup();
      const { rerender } = renderWithProviders(<StorySpecPanel story={makeStory({ specVersion: 2 })} />);

      await user.click(screen.getByTestId("story-spec-version-select"));
      await user.click(screen.getByTestId("story-spec-version-option-2"));
      expect(mockUseStorySpecVersion).toHaveBeenLastCalledWith("story-1", null);

      mockUseStorySpec.mockReturnValue({
        data: makeSpec({ version: 3, content: "The third draft", runId: "design-run-3" }),
        isLoading: false,
        isError: false,
      });
      rerender(<StorySpecPanel story={makeStory({ specVersion: 3 })} />);

      expect(screen.getByTestId("markdown-viewer")).toHaveTextContent("The third draft");
      expect(screen.getByTestId("story-spec-version-select")).toHaveTextContent("v3 (current)");
    });

    it("keeps Revise disabled for whitespace-only guidance", async () => {
      const user = userEvent.setup();
      renderWithProviders(<StorySpecPanel story={makeStory({ specVersion: 2 })} />);

      await user.click(screen.getByTestId("story-spec-revise-button"));
      await user.type(screen.getByTestId("story-spec-revise-guidance"), "   ");

      expect(screen.getByTestId("story-spec-revise-submit")).toBeDisabled();
    });
  });

  describe("while a revision is open", () => {
    beforeEach(() => {
      mockUseStorySpec.mockReturnValue({ data: makeSpec(), isLoading: false, isError: false });
      mockUseStorySpecVersions.mockReturnValue({ data: versions });
      mockUseStoryConflicts.mockReturnValue({
        data: [
          conflict(),
          conflict({ id: "conflict-2", taskRunId: "task-run-2", taskId: "task-2", taskTitle: "Store the receipt" }),
        ],
      });
    });

    function underRevision() {
      return makeStory({ specVersion: 2, specUnderRevision: true, openDesignRunId: "revision-run" });
    }

    it("names the conflicting Tasks, links the revision run, and hides Design and Revise", () => {
      renderWithProviders(<StorySpecPanel story={underRevision()} />);

      const banner = screen.getByTestId("story-spec-revision-banner");
      expect(banner).toHaveTextContent("Wire the payment form");
      expect(banner).toHaveTextContent("Store the receipt");
      const hrefs = within(banner)
        .getAllByRole("link")
        .map((link) => link.getAttribute("href"));
      expect(hrefs).toContain("/runs/revision-run");
      expect(hrefs).toContain("/runs/task-run-1");
      expect(hrefs).toContain("/runs/task-run-2");
      expect(screen.queryByTestId("story-spec-revise-button")).not.toBeInTheDocument();
      expect(screen.queryByTestId("story-spec-design-button")).not.toBeInTheDocument();
    });

    it("leaves out conflicts this revision does not answer", () => {
      mockUseStoryConflicts.mockReturnValue({
        data: [conflict(), conflict({ id: "conflict-3", taskTitle: "Raised later", revisionRunId: null })],
      });
      renderWithProviders(<StorySpecPanel story={underRevision()} />);

      const banner = screen.getByTestId("story-spec-revision-banner");
      expect(banner).toHaveTextContent("Wire the payment form");
      expect(banner).not.toHaveTextContent("Raised later");
    });

    it("still shows the approved spec the revision may replace", () => {
      renderWithProviders(<StorySpecPanel story={underRevision()} />);

      expect(screen.getByTestId("markdown-viewer")).toHaveTextContent("Ship it in two Tasks.");
    });
  });

  it("names conflicts still waiting for a revision run", () => {
    mockUseStorySpec.mockReturnValue({ data: makeSpec(), isLoading: false, isError: false });
    mockUseStoryConflicts.mockReturnValue({ data: [conflict({ revisionRunId: null })] });

    renderWithProviders(<StorySpecPanel story={makeStory({ specVersion: 2 })} />);

    expect(screen.getByTestId("story-spec-pending-conflicts")).toHaveTextContent("Wire the payment form");
  });
});
