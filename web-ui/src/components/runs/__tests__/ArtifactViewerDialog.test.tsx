import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, fireEvent } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import ArtifactViewerDialog from "../ArtifactViewerDialog";

vi.mock("@/hooks/useArtifacts", () => ({
  useArtifacts: vi.fn(),
  useArtifactContent: vi.fn(),
}));

import { useArtifactContent } from "@/hooks/useArtifacts";

const mockUseArtifactContent = useArtifactContent as ReturnType<typeof vi.fn>;

const baseArtifacts = [
  { name: "output.json", size: 1024, lastModified: "2026-01-01T00:00:00Z" },
  { name: "report.md", size: 512, lastModified: "2026-01-01T00:00:00Z" },
  { name: "image.png", size: 2048, lastModified: "2026-01-01T00:00:00Z" },
];

const defaultProps = {
  runId: "r1",
  execId: "e1",
  artifacts: baseArtifacts,
  selectedFile: "output.json" as string | null,
  open: true,
  onOpenChange: vi.fn(),
  onFileChange: vi.fn(),
};

describe("ArtifactViewerDialog", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockUseArtifactContent.mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: false,
    });
  });

  it("renders dialog with file name in title", () => {
    mockUseArtifactContent.mockReturnValue({
      data: '{"result": "ok"}',
      isLoading: false,
      isError: false,
    });
    renderWithProviders(<ArtifactViewerDialog {...defaultProps} />);

    const title = screen.getByRole("heading", { level: 2 });
    expect(title).toHaveTextContent("output.json");
  });

  it("renders plain text content in a pre tag", () => {
    mockUseArtifactContent.mockReturnValue({
      data: '{"result": "success"}',
      isLoading: false,
      isError: false,
    });
    renderWithProviders(<ArtifactViewerDialog {...defaultProps} />);

    const content = screen.getByText('{"result": "success"}');
    expect(content.closest("pre")).toBeInTheDocument();
  });

  it("renders markdown files with MarkdownViewer", () => {
    mockUseArtifactContent.mockReturnValue({
      data: "# Hello\n\nThis is **bold** text.",
      isLoading: false,
      isError: false,
    });
    renderWithProviders(
      <ArtifactViewerDialog {...defaultProps} selectedFile="report.md" />
    );

    expect(screen.getByText("Hello").tagName).toBe("H1");
    expect(screen.getByText("bold").tagName).toBe("STRONG");
  });

  it("renders image files with an img tag", () => {
    renderWithProviders(
      <ArtifactViewerDialog {...defaultProps} selectedFile="image.png" />
    );

    const img = screen.getByRole("img");
    expect(img).toBeInTheDocument();
    expect(img).toHaveAttribute("alt", "image.png");
    expect(img.getAttribute("src")).toContain("/artifacts/image.png");
  });

  it("shows error placeholder when image fails to load", () => {
    renderWithProviders(
      <ArtifactViewerDialog {...defaultProps} selectedFile="image.png" />
    );

    const img = screen.getByRole("img");
    fireEvent.error(img);

    expect(screen.getByText("Failed to load image")).toBeInTheDocument();
  });

  it("shows binary file placeholder for non-image binary extensions", () => {
    const artifacts = [
      ...baseArtifacts,
      { name: "archive.zip", size: 4096, lastModified: "2026-01-01T00:00:00Z" },
    ];
    renderWithProviders(
      <ArtifactViewerDialog
        {...defaultProps}
        artifacts={artifacts}
        selectedFile="archive.zip"
      />
    );

    expect(screen.getByText(/preview not available/)).toBeInTheDocument();
  });

  it("shows loading state", () => {
    mockUseArtifactContent.mockReturnValue({
      data: undefined,
      isLoading: true,
      isError: false,
    });
    renderWithProviders(<ArtifactViewerDialog {...defaultProps} />);

    expect(screen.getByText("Loading...")).toBeInTheDocument();
  });

  it("shows error state", () => {
    mockUseArtifactContent.mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: true,
    });
    renderWithProviders(<ArtifactViewerDialog {...defaultProps} />);

    expect(screen.getByText("Failed to load file content.")).toBeInTheDocument();
  });

  it("renders file switcher pills for multiple artifacts", () => {
    mockUseArtifactContent.mockReturnValue({
      data: "content",
      isLoading: false,
      isError: false,
    });
    renderWithProviders(<ArtifactViewerDialog {...defaultProps} />);

    // All artifact names should appear as pills
    const buttons = screen.getAllByRole("button");
    const pillButtons = buttons.filter(
      (btn) =>
        btn.textContent === "output.json" ||
        btn.textContent === "report.md" ||
        btn.textContent === "image.png"
    );
    expect(pillButtons).toHaveLength(3);
  });

  it("highlights the selected file pill", () => {
    mockUseArtifactContent.mockReturnValue({
      data: "content",
      isLoading: false,
      isError: false,
    });
    renderWithProviders(<ArtifactViewerDialog {...defaultProps} />);

    // Find the pill buttons (excluding the title which also contains "output.json")
    const pills = screen.getAllByRole("button").filter(
      (btn) => btn.classList.contains("rounded-full")
    );
    const selectedPill = pills.find((btn) => btn.textContent === "output.json");
    const unselectedPill = pills.find((btn) => btn.textContent === "report.md");

    expect(selectedPill?.className).toContain("bg-primary");
    expect(unselectedPill?.className).toContain("bg-muted");
  });

  it("calls onFileChange when a pill is clicked", async () => {
    const user = userEvent.setup();
    const onFileChange = vi.fn();
    mockUseArtifactContent.mockReturnValue({
      data: "content",
      isLoading: false,
      isError: false,
    });
    renderWithProviders(
      <ArtifactViewerDialog
        {...defaultProps}
        onFileChange={onFileChange}
      />
    );

    // Click on the report.md pill
    const pills = screen.getAllByRole("button").filter(
      (btn) => btn.classList.contains("rounded-full")
    );
    const reportPill = pills.find((btn) => btn.textContent === "report.md");
    await user.click(reportPill!);

    expect(onFileChange).toHaveBeenCalledWith("report.md");
  });

  it("does not render pills for a single artifact", () => {
    mockUseArtifactContent.mockReturnValue({
      data: "content",
      isLoading: false,
      isError: false,
    });
    const singleArtifact = [baseArtifacts[0]];
    renderWithProviders(
      <ArtifactViewerDialog
        {...defaultProps}
        artifacts={singleArtifact}
      />
    );

    // Should not have any pill buttons (only close button and title)
    const pills = screen.getAllByRole("button").filter(
      (btn) => btn.classList.contains("rounded-full")
    );
    expect(pills).toHaveLength(0);
  });

  it("does not render when open is false", () => {
    renderWithProviders(
      <ArtifactViewerDialog {...defaultProps} open={false} />
    );

    expect(screen.queryByText("output.json")).not.toBeInTheDocument();
  });

  it("does not fetch content for image files", () => {
    renderWithProviders(
      <ArtifactViewerDialog {...defaultProps} selectedFile="image.png" />
    );

    // useArtifactContent should be called with null filename for image files
    expect(mockUseArtifactContent).toHaveBeenCalledWith("r1", "e1", null);
  });

  it("renders SVG files as images (not as inline SVG)", () => {
    const artifacts = [
      ...baseArtifacts,
      { name: "diagram.svg", size: 512, lastModified: "2026-01-01T00:00:00Z" },
    ];
    renderWithProviders(
      <ArtifactViewerDialog
        {...defaultProps}
        artifacts={artifacts}
        selectedFile="diagram.svg"
      />
    );

    const img = screen.getByRole("img");
    expect(img).toBeInTheDocument();
    expect(img.getAttribute("src")).toContain("/artifacts/diagram.svg");
  });
});
