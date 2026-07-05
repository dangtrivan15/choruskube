import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/__tests__/test-utils";
import DocsPage from "@/pages/DocsPage";

vi.mock("@/hooks/useDocs", () => ({
  useDocsList: vi.fn(),
  useDocsPage: vi.fn(),
}));

// Spy on MarkdownViewer to assert it receives the expected props.
// The pass-through component renders {props.content} so existing tests stay valid.
const mockMarkdownViewerSpy = vi.fn();
vi.mock("@/components/ui/MarkdownViewer", () => ({
  default: (props: { content: string; variant?: string; linkBase?: string }) => {
    mockMarkdownViewerSpy(props);
    return <>{props.content}</>;
  },
}));

vi.mock("react-router", async (importOriginal) => {
  const actual = await importOriginal<typeof import("react-router")>();
  return {
    ...actual,
    useParams: vi.fn().mockReturnValue({}),
    Link: ({ children, to, ...props }: { children: React.ReactNode; to: string; [key: string]: unknown }) => (
      <a href={to} {...props}>{children}</a>
    ),
  };
});

import { useDocsList, useDocsPage } from "@/hooks/useDocs";
import { useParams } from "react-router";

const mockUseDocsList = useDocsList as ReturnType<typeof vi.fn>;
const mockUseDocsPage = useDocsPage as ReturnType<typeof vi.fn>;
const mockUseParams = useParams as ReturnType<typeof vi.fn>;

describe("DocsPage", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockUseDocsPage.mockReturnValue({ data: undefined, isLoading: false, isError: false });
    mockUseDocsList.mockReturnValue({ data: undefined, isLoading: false, isError: false });
  });

  describe("list view (no slug)", () => {
    beforeEach(() => {
      mockUseParams.mockReturnValue({});
    });

    it("shows loading skeletons when loading", () => {
      mockUseDocsList.mockReturnValue({ data: undefined, isLoading: true, isError: false });
      renderWithProviders(<DocsPage />);
      expect(document.querySelectorAll('[data-slot="skeleton"]').length).toBeGreaterThan(0);
    });

    it("renders doc titles as links", () => {
      const docs = [
        { slug: "getting-started", title: "Getting Started", order: 1 },
        { slug: "features", title: "Features", order: 2 },
      ];
      mockUseDocsList.mockReturnValue({ data: docs, isLoading: false, isError: false });
      renderWithProviders(<DocsPage />);
      expect(screen.getByText("Getting Started")).toBeInTheDocument();
      expect(screen.getByText("Features")).toBeInTheDocument();
    });

    it("shows empty state when list is empty", () => {
      mockUseDocsList.mockReturnValue({ data: [], isLoading: false, isError: false });
      renderWithProviders(<DocsPage />);
      expect(screen.getByText(/no documentation pages found/i)).toBeInTheDocument();
    });

    it("shows error message on fetch failure", () => {
      mockUseDocsList.mockReturnValue({ data: undefined, isLoading: false, isError: true });
      renderWithProviders(<DocsPage />);
      expect(screen.getByText(/failed to load documentation/i)).toBeInTheDocument();
    });

    it("renders description under list item when present", () => {
      const docs = [
        { slug: "getting-started", title: "Getting Started", order: 1, description: "Step-by-step guide." },
        { slug: "features", title: "Features", order: 2 },
      ];
      mockUseDocsList.mockReturnValue({ data: docs, isLoading: false, isError: false });
      renderWithProviders(<DocsPage />);
      expect(screen.getByText("Step-by-step guide.")).toBeInTheDocument();
      // Only the item with a description renders the description element
      expect(screen.queryAllByTestId("docs-list-item-description")).toHaveLength(1);
      // Both items are rendered
      expect(screen.queryAllByTestId("docs-list-item")).toHaveLength(2);
    });
  });

  describe("page view (with slug)", () => {
    beforeEach(() => {
      mockUseParams.mockReturnValue({ slug: "getting-started" });
    });

    it("renders page title and content", () => {
      const page = { slug: "getting-started", title: "Getting Started", content: "## Overview\n\nWelcome." };
      mockUseDocsPage.mockReturnValue({ data: page, isLoading: false, isError: false });
      renderWithProviders(<DocsPage />);
      expect(screen.getByTestId("docs-page-title")).toHaveTextContent("Getting Started");
      expect(screen.getByTestId("docs-page-content")).toBeInTheDocument();
    });

    it("shows loading skeletons when loading", () => {
      mockUseDocsPage.mockReturnValue({ data: undefined, isLoading: true, isError: false });
      renderWithProviders(<DocsPage />);
      expect(document.querySelectorAll('[data-slot="skeleton"]').length).toBeGreaterThan(0);
    });

    it("shows not-found message when page is null", () => {
      mockUseDocsPage.mockReturnValue({ data: null, isLoading: false, isError: false });
      renderWithProviders(<DocsPage />);
      expect(screen.getByTestId("docs-not-found")).toBeInTheDocument();
    });

    it("shows error message on fetch failure", () => {
      mockUseDocsPage.mockReturnValue({ data: undefined, isLoading: false, isError: true });
      renderWithProviders(<DocsPage />);
      expect(screen.getByTestId("docs-load-error")).toBeInTheDocument();
    });

    it("passes linkBase='/docs' to MarkdownViewer", () => {
      const page = { slug: "getting-started", title: "Getting Started", content: "Hello world." };
      mockUseDocsPage.mockReturnValue({ data: page, isLoading: false, isError: false });
      renderWithProviders(<DocsPage />);
      expect(mockMarkdownViewerSpy).toHaveBeenCalledWith(
        expect.objectContaining({ linkBase: "/docs" })
      );
    });
  });
});
