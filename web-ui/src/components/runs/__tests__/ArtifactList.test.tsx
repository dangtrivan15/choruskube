import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import ArtifactList from "../ArtifactList";

vi.mock("@/hooks/useArtifacts", () => ({
  useArtifactsForGroups: vi.fn(() => []),
  useArtifacts: vi.fn(() => ({ data: [], isLoading: false })),
  useArtifactContent: vi.fn(() => ({ data: undefined, isLoading: false })),
}));

vi.mock("../ArtifactViewerDialog", () => ({
  default: ({ open }: { open: boolean }) => (
    open ? <div data-testid="artifact-viewer-dialog-mock">Dialog</div> : null
  ),
}));

import { useArtifactsForGroups } from "@/hooks/useArtifacts";
const mockUseArtifactsForGroups = useArtifactsForGroups as ReturnType<typeof vi.fn>;

describe("ArtifactList", () => {
  const defaultGroups = [
    { nodeExecutionId: "exec-a", nodeLabel: "groupA", artifacts: [] },
  ];

  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("renders nothing when all groups have null execId", () => {
    mockUseArtifactsForGroups.mockReturnValue([]);
    const groups = [
      { nodeExecutionId: null, nodeLabel: "Node A", artifacts: [{ name: "file.md", description: null, required: false }] },
    ];
    const { container } = renderWithProviders(
      <ArtifactList runId="run-1" groups={groups} />
    );
    expect(container.firstChild).toBeNull();
  });

  it("renders flat list with correct nodeLabel/filename format", () => {
    mockUseArtifactsForGroups.mockReturnValue([
      { data: [{ name: "fileA.md", size: 100, lastModified: "" }], isLoading: false, isSuccess: true },
      { data: [{ name: "fileB.md", size: 200, lastModified: "" }], isLoading: false, isSuccess: true },
    ]);
    const groups = [
      { nodeExecutionId: "exec-a", nodeLabel: "groupA", artifacts: [] },
      { nodeExecutionId: "exec-b", nodeLabel: "groupB", artifacts: [] },
    ];
    renderWithProviders(<ArtifactList runId="run-1" groups={groups} />);
    expect(screen.getByText("groupA/fileA.md")).toBeInTheDocument();
    expect(screen.getByText("groupB/fileB.md")).toBeInTheDocument();
  });

  it("filters to declared artifact names", () => {
    mockUseArtifactsForGroups.mockReturnValue([
      {
        data: [
          { name: "plan.md", size: 100, lastModified: "" },
          { name: "debug.log", size: 50, lastModified: "" },
        ],
        isLoading: false,
        isSuccess: true,
      },
    ]);
    const groups = [
      {
        nodeExecutionId: "exec-a",
        nodeLabel: "Node A",
        artifacts: [{ name: "plan.md", description: null, required: false }],
      },
    ];
    renderWithProviders(<ArtifactList runId="run-1" groups={groups} />);
    expect(screen.getByText("Node A/plan.md")).toBeInTheDocument();
    expect(screen.queryByText("Node A/debug.log")).not.toBeInTheDocument();
  });

  it("expand/collapse toggle hides and shows the list", async () => {
    const user = userEvent.setup();
    mockUseArtifactsForGroups.mockReturnValue([
      { data: [{ name: "file.md", size: 100, lastModified: "" }], isLoading: false, isSuccess: true },
    ]);
    renderWithProviders(<ArtifactList runId="run-1" groups={defaultGroups} />);

    // Initially expanded
    expect(screen.getByTestId("artifact-list-items")).toBeInTheDocument();

    // Click to collapse
    await user.click(screen.getByText("Artifacts"));
    expect(screen.queryByTestId("artifact-list-items")).not.toBeInTheDocument();

    // Click to expand again
    await user.click(screen.getByText("Artifacts"));
    expect(screen.getByTestId("artifact-list-items")).toBeInTheDocument();
  });

  it("clicking an artifact opens the viewer dialog", async () => {
    const user = userEvent.setup();
    mockUseArtifactsForGroups.mockReturnValue([
      { data: [{ name: "spec.md", size: 500, lastModified: "" }], isLoading: false, isSuccess: true },
    ]);
    renderWithProviders(<ArtifactList runId="run-1" groups={defaultGroups} />);

    await user.click(screen.getByText("groupA/spec.md"));
    expect(screen.getByTestId("artifact-viewer-dialog-mock")).toBeInTheDocument();
  });

  it("shows loading spinner while fetching", () => {
    mockUseArtifactsForGroups.mockReturnValue([
      { data: undefined, isLoading: true, isSuccess: false },
    ]);
    renderWithProviders(<ArtifactList runId="run-1" groups={defaultGroups} />);
    // Spinner text
    expect(screen.getByText("Loading artifacts...")).toBeInTheDocument();
    expect(screen.queryByTestId("artifact-list-items")).not.toBeInTheDocument();
  });

  it("returns null when all declared artifact names match nothing in API response", () => {
    mockUseArtifactsForGroups.mockReturnValue([
      {
        data: [{ name: "other.txt", size: 10, lastModified: "" }],
        isLoading: false,
        isSuccess: true,
      },
    ]);
    const groups = [
      {
        nodeExecutionId: "exec-a",
        nodeLabel: "Node A",
        artifacts: [{ name: "plan.md", description: null, required: false }],
      },
    ];
    const { container } = renderWithProviders(<ArtifactList runId="run-1" groups={groups} />);
    expect(container.firstChild).toBeNull();
  });
});
