import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import ArtifactBrowser from "../ArtifactBrowser";

vi.mock("@/hooks/useArtifacts", () => ({
  useArtifacts: vi.fn(),
  useArtifactContent: vi.fn(),
}));

import { useArtifacts, useArtifactContent } from "@/hooks/useArtifacts";

const mockUseArtifacts = useArtifacts as ReturnType<typeof vi.fn>;
const mockUseArtifactContent = useArtifactContent as ReturnType<typeof vi.fn>;

describe("ArtifactBrowser", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockUseArtifactContent.mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: false,
    });
  });

  it("shows loading state", () => {
    mockUseArtifacts.mockReturnValue({ data: undefined, isLoading: true });
    renderWithProviders(<ArtifactBrowser runId="r1" execId="e1" />);

    expect(screen.getByText("Loading artifacts...")).toBeInTheDocument();
  });

  it("renders nothing when artifacts list is empty", () => {
    mockUseArtifacts.mockReturnValue({ data: [], isLoading: false });
    const { container } = renderWithProviders(
      <ArtifactBrowser runId="r1" execId="e1" />
    );

    expect(container.innerHTML).toBe("");
  });

  it("renders nothing when artifacts is undefined", () => {
    mockUseArtifacts.mockReturnValue({ data: undefined, isLoading: false });
    const { container } = renderWithProviders(
      <ArtifactBrowser runId="r1" execId="e1" />
    );

    expect(container.innerHTML).toBe("");
  });

  it("renders artifact list with names and sizes", () => {
    const artifacts = [
      { name: "output.json", size: 1024, lastModified: "2026-01-01T00:00:00Z" },
      { name: "report.txt", size: 512, lastModified: "2026-01-01T00:00:00Z" },
    ];
    mockUseArtifacts.mockReturnValue({ data: artifacts, isLoading: false });
    renderWithProviders(<ArtifactBrowser runId="r1" execId="e1" />);

    expect(screen.getByText("Artifacts")).toBeInTheDocument();
    expect(screen.getByText("output.json")).toBeInTheDocument();
    expect(screen.getByText("report.txt")).toBeInTheDocument();
    expect(screen.getByText("1.0 KB")).toBeInTheDocument();
    expect(screen.getByText("512 B")).toBeInTheDocument();
  });

  it("shows artifact count badge", () => {
    const artifacts = [
      { name: "file1.txt", size: 100, lastModified: "2026-01-01T00:00:00Z" },
      { name: "file2.txt", size: 200, lastModified: "2026-01-01T00:00:00Z" },
      { name: "file3.txt", size: 300, lastModified: "2026-01-01T00:00:00Z" },
    ];
    mockUseArtifacts.mockReturnValue({ data: artifacts, isLoading: false });
    renderWithProviders(<ArtifactBrowser runId="r1" execId="e1" />);

    expect(screen.getByText("3")).toBeInTheDocument();
  });

  it("opens dialog when a file is clicked", async () => {
    const user = userEvent.setup();
    const artifacts = [
      { name: "output.json", size: 256, lastModified: "2026-01-01T00:00:00Z" },
    ];
    mockUseArtifacts.mockReturnValue({ data: artifacts, isLoading: false });
    mockUseArtifactContent.mockReturnValue({
      data: '{"result": "success"}',
      isLoading: false,
      isError: false,
    });
    renderWithProviders(<ArtifactBrowser runId="r1" execId="e1" />);

    await user.click(screen.getByText("output.json"));

    // Dialog should open with the file content
    expect(screen.getByText('{"result": "success"}')).toBeInTheDocument();
    expect(screen.getByText("Viewing artifact file contents")).toBeInTheDocument();
  });

  it("toggles expanded/collapsed state", async () => {
    const user = userEvent.setup();
    const artifacts = [
      { name: "file.txt", size: 100, lastModified: "2026-01-01T00:00:00Z" },
    ];
    mockUseArtifacts.mockReturnValue({ data: artifacts, isLoading: false });
    renderWithProviders(<ArtifactBrowser runId="r1" execId="e1" />);

    // Initially expanded
    expect(screen.getByText("file.txt")).toBeInTheDocument();

    // Collapse
    await user.click(screen.getByText("Artifacts"));
    expect(screen.queryByText("file.txt")).not.toBeInTheDocument();

    // Expand
    await user.click(screen.getByText("Artifacts"));
    expect(screen.getByText("file.txt")).toBeInTheDocument();
  });

  it("opens dialog with markdown content for .md files", async () => {
    const user = userEvent.setup();
    const artifacts = [
      { name: "readme.md", size: 256, lastModified: "2026-01-01T00:00:00Z" },
    ];
    mockUseArtifacts.mockReturnValue({ data: artifacts, isLoading: false });
    mockUseArtifactContent.mockReturnValue({
      data: "# Hello\n\nThis is **bold** text.",
      isLoading: false,
      isError: false,
    });
    renderWithProviders(<ArtifactBrowser runId="r1" execId="e1" />);

    await user.click(screen.getByText("readme.md"));

    expect(screen.getByText("Hello").tagName).toBe("H1");
    expect(screen.getByText("bold").tagName).toBe("STRONG");
  });

  it("filters artifacts when filterArtifactNames is provided", () => {
    const artifacts = [
      { name: "spec_and_plan.md", size: 1024, lastModified: "2026-01-01T00:00:00Z" },
      { name: "output.json", size: 512, lastModified: "2026-01-01T00:00:00Z" },
      { name: "report.txt", size: 256, lastModified: "2026-01-01T00:00:00Z" },
    ];
    mockUseArtifacts.mockReturnValue({ data: artifacts, isLoading: false });
    renderWithProviders(
      <ArtifactBrowser runId="r1" execId="e1" filterArtifactNames={["spec_and_plan.md"]} />
    );

    expect(screen.getByText("spec_and_plan.md")).toBeInTheDocument();
    expect(screen.queryByText("output.json")).not.toBeInTheDocument();
    expect(screen.queryByText("report.txt")).not.toBeInTheDocument();
    // Badge should show filtered count
    expect(screen.getByText("1")).toBeInTheDocument();
  });

  it("opens dialog with plain text content for non-.md files", async () => {
    const user = userEvent.setup();
    const artifacts = [
      { name: "data.json", size: 256, lastModified: "2026-01-01T00:00:00Z" },
    ];
    mockUseArtifacts.mockReturnValue({ data: artifacts, isLoading: false });
    mockUseArtifactContent.mockReturnValue({
      data: '{"key": "value"}',
      isLoading: false,
      isError: false,
    });
    renderWithProviders(<ArtifactBrowser runId="r1" execId="e1" />);

    await user.click(screen.getByText("data.json"));

    // Should render in a <pre> tag in the dialog, not as markdown
    const content = screen.getByText('{"key": "value"}');
    expect(content.closest("pre")).toBeInTheDocument();
  });
});
