import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor, fireEvent } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import RunListTable from "../RunListTable";

// Mock the useRuns hook
vi.mock("@/hooks/useRuns", () => ({
  useRuns: vi.fn(),
}));

const mockUseMobileBreakpoint = vi.fn().mockReturnValue(false);
vi.mock("@/hooks/useMobileBreakpoint", () => ({
  useMobileBreakpoint: () => mockUseMobileBreakpoint(),
}));

const mockNavigate = vi.fn();
vi.mock("react-router", async () => {
  const actual = await vi.importActual("react-router");
  return { ...actual, useNavigate: () => mockNavigate };
});

import { useRuns } from "@/hooks/useRuns";

const mockUseRuns = useRuns as ReturnType<typeof vi.fn>;

describe("RunListTable", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockUseMobileBreakpoint.mockReturnValue(false);
    mockNavigate.mockReset();
  });

  it("renders table headers including Name", () => {
    mockUseRuns.mockReturnValue({ data: undefined, isLoading: false, isError: false });
    renderWithProviders(<RunListTable />);

    expect(screen.getByText("Name")).toBeInTheDocument();
    expect(screen.getByText("Template")).toBeInTheDocument();
    expect(screen.getByText("Software Project")).toBeInTheDocument();
    expect(screen.getByText("Status")).toBeInTheDocument();
    expect(screen.getByText("Started")).toBeInTheDocument();
  });

  it("shows skeleton rows when loading", () => {
    mockUseRuns.mockReturnValue({ data: undefined, isLoading: true, isError: false });
    const { container } = renderWithProviders(<RunListTable />);

    // Skeleton rows render 5 rows
    const skeletonDivs = container.querySelectorAll("[data-slot='skeleton']");
    expect(skeletonDivs.length).toBeGreaterThan(0);
  });

  it("shows error message on fetch failure", () => {
    mockUseRuns.mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: true,
      error: new Error("Network error"),
    });
    renderWithProviders(<RunListTable />);

    expect(screen.getByText(/Failed to load runs/)).toBeInTheDocument();
    expect(screen.getByText(/Network error/)).toBeInTheDocument();
  });

  it("shows empty state when no runs exist", () => {
    mockUseRuns.mockReturnValue({ data: { content: [], totalElements: 0, totalPages: 1, number: 0, size: 20, first: true, last: true, empty: true }, isLoading: false, isError: false });
    renderWithProviders(<RunListTable />);

    expect(screen.getByText("No runs found.")).toBeInTheDocument();
  });

  it("renders run rows with data including name", () => {
    const runs = [
      {
        id: "abcdef12-3456-7890-abcd-ef1234567890",
        templateName: "My Template",
        name: "Add dark mode",
        status: "running",
        startedAt: "2026-03-01T10:00:00Z",
        completedAt: null,
        createdAt: "2026-03-01T09:59:00Z",
        softwareProject: null,
      },
      {
        id: "12345678-abcd-ef01-2345-678901234567",
        templateName: "Other Template",
        name: null,
        status: "completed",
        startedAt: "2026-03-01T08:00:00Z",
        completedAt: "2026-03-01T09:00:00Z",
        createdAt: "2026-03-01T07:59:00Z",
        softwareProject: null,
      },
    ];
    mockUseRuns.mockReturnValue({ data: { content: runs, totalElements: 2, totalPages: 1, number: 0, size: 20, first: true, last: true, empty: false }, isLoading: false, isError: false });
    renderWithProviders(<RunListTable />);

    // Run names
    expect(screen.getByText("Add dark mode")).toBeInTheDocument();

    // Template names
    expect(screen.getByText("My Template")).toBeInTheDocument();
    expect(screen.getByText("Other Template")).toBeInTheDocument();

    // Status badges
    expect(screen.getByText("running")).toBeInTheDocument();
    expect(screen.getByText("completed")).toBeInTheDocument();
  });

  it("renders dash for runs without a name", () => {
    const runs = [
      {
        id: "abcdef12-3456-7890-abcd-ef1234567890",
        templateName: "My Template",
        name: null,
        status: "pending",
        startedAt: null,
        completedAt: null,
        createdAt: "2026-03-01T09:59:00Z",
        softwareProject: null,
      },
    ];
    mockUseRuns.mockReturnValue({ data: { content: runs, totalElements: 1, totalPages: 1, number: 0, size: 20, first: true, last: true, empty: false }, isLoading: false, isError: false });
    renderWithProviders(<RunListTable />);

    // The hyphen-minus dash characters: one for name, one for Started.
    // The software project column shows an em dash (—) when null, which is separate.
    const dashes = screen.getAllByText("-");
    expect(dashes.length).toBe(2);
  });

  it("navigates to run page on row click", async () => {
    const user = userEvent.setup();
    const runs = [
      {
        id: "abcdef12-3456-7890-abcd-ef1234567890",
        templateName: "Test",
        name: "Test Run",
        status: "running",
        startedAt: null,
        completedAt: null,
        createdAt: "2026-03-01T09:59:00Z",
      },
    ];
    mockUseRuns.mockReturnValue({ data: { content: runs, totalElements: 1, totalPages: 1, number: 0, size: 20, first: true, last: true, empty: false }, isLoading: false, isError: false });
    renderWithProviders(<RunListTable />);

    const row = screen.getByTestId("run-row");
    await user.click(row);
    expect(mockNavigate).toHaveBeenCalledWith("/runs/abcdef12-3456-7890-abcd-ef1234567890");
  });

  it("passes status filter to useRuns", () => {
    mockUseRuns.mockReturnValue({ data: { content: [], totalElements: 0, totalPages: 1, number: 0, size: 20, first: true, last: true, empty: true }, isLoading: false, isError: false });
    renderWithProviders(<RunListTable status="completed" />);

    expect(mockUseRuns).toHaveBeenCalledWith("completed", undefined, { page: 0, size: 20, sort: null });
  });

  it("shows dash for Started when startedAt is null", () => {
    const runs = [
      {
        id: "abcdef12-0000-0000-0000-000000000000",
        templateName: "Test",
        name: "Test run",
        status: "pending",
        startedAt: null,
        completedAt: null,
        createdAt: "2026-03-01T09:59:00Z",
      },
    ];
    mockUseRuns.mockReturnValue({ data: { content: runs, totalElements: 1, totalPages: 1, number: 0, size: 20, first: true, last: true, empty: false }, isLoading: false, isError: false });
    renderWithProviders(<RunListTable />);

    // One dash: for "Started" column
    const cells = screen.getAllByText("-");
    expect(cells.length).toBe(1);
  });

  it("applies table-fixed class to the table", () => {
    mockUseRuns.mockReturnValue({ data: { content: [], totalElements: 0, totalPages: 1, number: 0, size: 20, first: true, last: true, empty: true }, isLoading: false, isError: false });
    const { container } = renderWithProviders(<RunListTable />);

    const table = container.querySelector("table");
    expect(table).toHaveClass("table-fixed");
  });

  it("wraps name cell content in a truncate span", () => {
    const runs = [
      {
        id: "abcdef12-3456-7890-abcd-ef1234567890",
        templateName: "My Template",
        name: "A very long run name",
        status: "running",
        startedAt: "2026-03-01T10:00:00Z",
        completedAt: null,
        createdAt: "2026-03-01T09:59:00Z",
      },
    ];
    mockUseRuns.mockReturnValue({ data: { content: runs, totalElements: 1, totalPages: 1, number: 0, size: 20, first: true, last: true, empty: false }, isLoading: false, isError: false });
    renderWithProviders(<RunListTable />);

    const nameSpan = screen.getByText("A very long run name");
    expect(nameSpan).toHaveClass("truncate");
  });

  it("wraps template cell content in a truncate span", () => {
    const runs = [
      {
        id: "abcdef12-3456-7890-abcd-ef1234567890",
        templateName: "My Template",
        name: "Test",
        status: "running",
        startedAt: "2026-03-01T10:00:00Z",
        completedAt: null,
        createdAt: "2026-03-01T09:59:00Z",
      },
    ];
    mockUseRuns.mockReturnValue({ data: { content: runs, totalElements: 1, totalPages: 1, number: 0, size: 20, first: true, last: true, empty: false }, isLoading: false, isError: false });
    renderWithProviders(<RunListTable />);

    const templateSpan = screen.getByText("My Template");
    expect(templateSpan).toHaveClass("truncate");
  });

  it("shows tooltip content on hover for name cell", async () => {
    const user = userEvent.setup();
    const runs = [
      {
        id: "abcdef12-3456-7890-abcd-ef1234567890",
        templateName: "My Template",
        name: "A very long run name that gets truncated",
        status: "running",
        startedAt: "2026-03-01T10:00:00Z",
        completedAt: null,
        createdAt: "2026-03-01T09:59:00Z",
      },
    ];
    mockUseRuns.mockReturnValue({ data: { content: runs, totalElements: 1, totalPages: 1, number: 0, size: 20, first: true, last: true, empty: false }, isLoading: false, isError: false });
    renderWithProviders(<RunListTable />);

    const nameTrigger = screen.getByText("A very long run name that gets truncated");
    await user.hover(nameTrigger);

    await waitFor(() => {
      const tooltips = screen.getAllByText("A very long run name that gets truncated");
      // The text appears in both the trigger and the tooltip content
      expect(tooltips.length).toBeGreaterThanOrEqual(2);
    });
  });

  it("does not render tooltip for null name", () => {
    const runs = [
      {
        id: "abcdef12-3456-7890-abcd-ef1234567890",
        templateName: "My Template",
        name: null,
        status: "pending",
        startedAt: null,
        completedAt: null,
        createdAt: "2026-03-01T09:59:00Z",
      },
    ];
    mockUseRuns.mockReturnValue({ data: { content: runs, totalElements: 1, totalPages: 1, number: 0, size: 20, first: true, last: true, empty: false }, isLoading: false, isError: false });
    renderWithProviders(<RunListTable />);

    // The dash for null name should not be wrapped in a tooltip trigger
    const dashes = screen.getAllByText("-");
    const nameDash = dashes.find((el) => !el.closest("[data-slot='tooltip-trigger']"));
    expect(nameDash).toBeTruthy();
  });

  // -------------------------------------------------------------------------
  // Mobile card layout tests
  // -------------------------------------------------------------------------

  it("renders card layout when mobile", () => {
    mockUseMobileBreakpoint.mockReturnValue(true);
    const runs = [
      {
        id: "abcdef12-3456-7890-abcd-ef1234567890",
        templateName: "My Template",
        name: "Test Run",
        status: "running",
        startedAt: "2026-03-01T10:00:00Z",
        completedAt: null,
        createdAt: "2026-03-01T09:59:00Z",
      },
    ];
    mockUseRuns.mockReturnValue({ data: { content: runs, totalElements: 1, totalPages: 1, number: 0, size: 20, first: true, last: true, empty: false }, isLoading: false, isError: false });
    renderWithProviders(<RunListTable />);

    expect(screen.getByTestId("run-card-list")).toBeInTheDocument();
    expect(screen.getByTestId("run-card")).toBeInTheDocument();
    // Table should not be rendered — "Template" header only shows in desktop table
    expect(screen.queryByText("Template")).not.toBeInTheDocument();
  });

  it("renders table layout when desktop", () => {
    mockUseMobileBreakpoint.mockReturnValue(false);
    const runs = [
      {
        id: "abcdef12-3456-7890-abcd-ef1234567890",
        templateName: "My Template",
        name: "Test Run",
        status: "running",
        startedAt: "2026-03-01T10:00:00Z",
        completedAt: null,
        createdAt: "2026-03-01T09:59:00Z",
      },
    ];
    mockUseRuns.mockReturnValue({ data: { content: runs, totalElements: 1, totalPages: 1, number: 0, size: 20, first: true, last: true, empty: false }, isLoading: false, isError: false });
    renderWithProviders(<RunListTable />);

    expect(screen.getByText("Name")).toBeInTheDocument();
    expect(screen.queryByTestId("run-card-list")).not.toBeInTheDocument();
  });

  it("card shows run name, status badge, template, and time", () => {
    mockUseMobileBreakpoint.mockReturnValue(true);
    const runs = [
      {
        id: "abcdef12-3456-7890-abcd-ef1234567890",
        templateName: "My Template",
        name: "My Run",
        status: "running",
        startedAt: "2026-03-01T10:00:00Z",
        completedAt: null,
        createdAt: "2026-03-01T09:59:00Z",
      },
    ];
    mockUseRuns.mockReturnValue({ data: { content: runs, totalElements: 1, totalPages: 1, number: 0, size: 20, first: true, last: true, empty: false }, isLoading: false, isError: false });
    renderWithProviders(<RunListTable />);

    expect(screen.getByText("My Run")).toBeInTheDocument();
    expect(screen.getByText("running")).toBeInTheDocument();
    expect(screen.getByText("My Template")).toBeInTheDocument();
  });

  it("card is wrapped in a Link to the run detail page", () => {
    mockUseMobileBreakpoint.mockReturnValue(true);
    const runs = [
      {
        id: "abcdef12-3456-7890-abcd-ef1234567890",
        templateName: "Test",
        name: "Test Run",
        status: "running",
        startedAt: null,
        completedAt: null,
        createdAt: "2026-03-01T09:59:00Z",
      },
    ];
    mockUseRuns.mockReturnValue({ data: { content: runs, totalElements: 1, totalPages: 1, number: 0, size: 20, first: true, last: true, empty: false }, isLoading: false, isError: false });
    renderWithProviders(<RunListTable />);

    const card = screen.getByTestId("run-card");
    expect(card.closest("a")).toHaveAttribute(
      "href",
      "/runs/abcdef12-3456-7890-abcd-ef1234567890"
    );
  });

  // -------------------------------------------------------------------------
  // Row click navigation tests
  // -------------------------------------------------------------------------

  it("navigates to run page on Enter key press", () => {
    const runs = [
      {
        id: "abcdef12-3456-7890-abcd-ef1234567890",
        templateName: "Test",
        name: "Test Run",
        status: "running",
        startedAt: "2026-03-01T10:00:00Z",
        completedAt: null,
        createdAt: "2026-03-01T09:59:00Z",
      },
    ];
    mockUseRuns.mockReturnValue({ data: { content: runs, totalElements: 1, totalPages: 1, number: 0, size: 20, first: true, last: true, empty: false }, isLoading: false, isError: false });
    renderWithProviders(<RunListTable />);

    const row = screen.getByTestId("run-row");
    fireEvent.keyDown(row, { key: "Enter" });
    expect(mockNavigate).toHaveBeenCalledWith("/runs/abcdef12-3456-7890-abcd-ef1234567890");
  });

  it("navigates to run page on Space key press", () => {
    const runs = [
      {
        id: "abcdef12-3456-7890-abcd-ef1234567890",
        templateName: "Test",
        name: "Test Run",
        status: "running",
        startedAt: "2026-03-01T10:00:00Z",
        completedAt: null,
        createdAt: "2026-03-01T09:59:00Z",
      },
    ];
    mockUseRuns.mockReturnValue({ data: { content: runs, totalElements: 1, totalPages: 1, number: 0, size: 20, first: true, last: true, empty: false }, isLoading: false, isError: false });
    renderWithProviders(<RunListTable />);

    const row = screen.getByTestId("run-row");
    fireEvent.keyDown(row, { key: " " });
    expect(mockNavigate).toHaveBeenCalledWith("/runs/abcdef12-3456-7890-abcd-ef1234567890");
  });

  it("applies cursor-pointer class to run rows", () => {
    const runs = [
      {
        id: "abcdef12-3456-7890-abcd-ef1234567890",
        templateName: "Test",
        name: "Test Run",
        status: "running",
        startedAt: "2026-03-01T10:00:00Z",
        completedAt: null,
        createdAt: "2026-03-01T09:59:00Z",
      },
    ];
    mockUseRuns.mockReturnValue({ data: { content: runs, totalElements: 1, totalPages: 1, number: 0, size: 20, first: true, last: true, empty: false }, isLoading: false, isError: false });
    renderWithProviders(<RunListTable />);

    const row = screen.getByTestId("run-row");
    expect(row).toHaveClass("cursor-pointer");
  });

  it("mobile card does not show duration separator", () => {
    mockUseMobileBreakpoint.mockReturnValue(true);
    const runs = [
      {
        id: "abcdef12-3456-7890-abcd-ef1234567890",
        templateName: "My Template",
        name: "Test Run",
        status: "running",
        startedAt: "2026-03-01T10:00:00Z",
        completedAt: null,
        createdAt: "2026-03-01T09:59:00Z",
        softwareProject: null,
      },
    ];
    mockUseRuns.mockReturnValue({ data: { content: runs, totalElements: 1, totalPages: 1, number: 0, size: 20, first: true, last: true, empty: false }, isLoading: false, isError: false });
    renderWithProviders(<RunListTable />);

    // The middle-dot separator should not appear in mobile cards
    const cardList = screen.getByTestId("run-card-list");
    expect(cardList.textContent).not.toContain("\u00B7");
  });

  // -------------------------------------------------------------------------
  // Software Project column tests
  // -------------------------------------------------------------------------

  it("renders Software Project column header", () => {
    mockUseRuns.mockReturnValue({ data: undefined, isLoading: false, isError: false });
    renderWithProviders(<RunListTable />);

    expect(screen.getByText("Software Project")).toBeInTheDocument();
  });

  it("renders software project name in desktop table", () => {
    const runs = [
      {
        id: "abcdef12-3456-7890-abcd-ef1234567890",
        templateName: "My Template",
        name: "Test Run",
        status: "running",
        startedAt: "2026-03-01T10:00:00Z",
        completedAt: null,
        createdAt: "2026-03-01T09:59:00Z",
        softwareProject: { id: "sp-1", type: "git_repo", name: "my-repo" },
      },
    ];
    mockUseRuns.mockReturnValue({ data: { content: runs, totalElements: 1, totalPages: 1, number: 0, size: 20, first: true, last: true, empty: false }, isLoading: false, isError: false });
    renderWithProviders(<RunListTable />);

    expect(screen.getByText("my-repo")).toBeInTheDocument();
  });

  it("renders em dash for software project when null (desktop)", () => {
    const runs = [
      {
        id: "abcdef12-3456-7890-abcd-ef1234567890",
        templateName: "My Template",
        name: "Test Run",
        status: "running",
        startedAt: "2026-03-01T10:00:00Z",
        completedAt: null,
        createdAt: "2026-03-01T09:59:00Z",
        softwareProject: null,
      },
    ];
    mockUseRuns.mockReturnValue({ data: { content: runs, totalElements: 1, totalPages: 1, number: 0, size: 20, first: true, last: true, empty: false }, isLoading: false, isError: false });
    renderWithProviders(<RunListTable />);

    // The software project column shows an em dash when null
    expect(screen.getByText("\u2014")).toBeInTheDocument();
  });

  it("mobile card shows software project name", () => {
    mockUseMobileBreakpoint.mockReturnValue(true);
    const runs = [
      {
        id: "abcdef12-3456-7890-abcd-ef1234567890",
        templateName: "My Template",
        name: "Test Run",
        status: "running",
        startedAt: "2026-03-01T10:00:00Z",
        completedAt: null,
        createdAt: "2026-03-01T09:59:00Z",
        softwareProject: { id: "sp-1", type: "git_repo", name: "my-repo" },
      },
    ];
    mockUseRuns.mockReturnValue({ data: { content: runs, totalElements: 1, totalPages: 1, number: 0, size: 20, first: true, last: true, empty: false }, isLoading: false, isError: false });
    renderWithProviders(<RunListTable />);

    expect(screen.getByText("my-repo")).toBeInTheDocument();
  });

  it("mobile card omits software project line when null", () => {
    mockUseMobileBreakpoint.mockReturnValue(true);
    const runs = [
      {
        id: "abcdef12-3456-7890-abcd-ef1234567890",
        templateName: "My Template",
        name: "Test Run",
        status: "running",
        startedAt: "2026-03-01T10:00:00Z",
        completedAt: null,
        createdAt: "2026-03-01T09:59:00Z",
        softwareProject: null,
      },
    ];
    mockUseRuns.mockReturnValue({ data: { content: runs, totalElements: 1, totalPages: 1, number: 0, size: 20, first: true, last: true, empty: false }, isLoading: false, isError: false });
    renderWithProviders(<RunListTable />);

    expect(screen.queryByText("my-repo")).not.toBeInTheDocument();
  });
});
