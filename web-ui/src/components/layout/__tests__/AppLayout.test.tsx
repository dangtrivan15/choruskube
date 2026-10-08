import { describe, it, expect, vi, beforeEach } from "vitest";
import { useState } from "react";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router";
import AppLayout from "@/components/layout/AppLayout";
import { useFullBleedMain } from "@/components/layout/MainLayoutContext";
import { ActivityFeedProvider } from "@/hooks/useActivityFeed";
import type { RunSummary, PageResponse } from "@/lib/types";

// ---------------------------------------------------------------------------
// Mocks — prevent real network/websocket calls from child components
// ---------------------------------------------------------------------------

vi.mock("@/hooks/usePendingGates", () => ({
  usePendingGatesCount: vi.fn().mockReturnValue({ data: { count: 0 } }),
}));

vi.mock("@/hooks/usePendingGatesSubscription", () => ({
  usePendingGatesSubscription: vi.fn(),
}));

const mockUseMobileBreakpoint = vi.fn().mockReturnValue(false);
vi.mock("@/hooks/useMobileBreakpoint", () => ({
  useMobileBreakpoint: () => mockUseMobileBreakpoint(),
}));

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: Infinity },
      mutations: { retry: false },
    },
  });
}

function renderAppLayout(queryClient: QueryClient, extraRoute?: React.ReactNode) {
  return render(
    <QueryClientProvider client={queryClient}>
      <ActivityFeedProvider>
        <MemoryRouter initialEntries={["/runs"]}>
          <Routes>
            <Route element={<AppLayout />}>
              <Route path="/runs" element={extraRoute ?? <div>Runs Page</div>} />
            </Route>
          </Routes>
        </MemoryRouter>
      </ActivityFeedProvider>
    </QueryClientProvider>
  );
}

/** A minimal full-bleed page, toggleable so a test can unmount it mid-test. */
function FullBleedChild() {
  useFullBleedMain();
  return <div data-testid="full-bleed-child">Full bleed</div>;
}

function ToggleableFullBleedPage() {
  const [mounted, setMounted] = useState(true);
  return (
    <div>
      <button onClick={() => setMounted(false)}>Unmount</button>
      {mounted && <FullBleedChild />}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe("AppLayout", () => {
  let queryClient: QueryClient;

  beforeEach(() => {
    queryClient = createQueryClient();
    vi.clearAllMocks();
    mockUseMobileBreakpoint.mockReturnValue(false);
  });

  it("renders the layout with sidebar and main content", () => {
    renderAppLayout(queryClient);
    expect(screen.getByText("ChorusKube")).toBeInTheDocument();
    expect(screen.getByText("Runs Page")).toBeInTheDocument();
  });

  it("opens command palette on Ctrl+K and shows cached runs from PageResponse", async () => {
    const user = userEvent.setup();

    // Seed the cache with the exact key shape that useRuns produces:
    //   ["runs", status, name, pagination] → PageResponse<RunSummary>
    const runsPage: PageResponse<RunSummary> = {
      content: [
        {
          id: "abc-123-def",
          name: "My Cached Run",
          graphTemplateId: "tpl-1",
          templateName: "TestTemplate",
          status: "running",
          startedAt: null,
          completedAt: null,
          createdAt: "2025-01-01",
          autopilotId: null,
          softwareProject: null,
        },
      ],
      totalElements: 1,
      totalPages: 1,
      size: 20,
      number: 0,
      first: true,
      last: true,
      empty: false,
    };
    queryClient.setQueryData(["runs", undefined, undefined, undefined], runsPage);

    renderAppLayout(queryClient);

    // Open the command palette via Ctrl+K
    await user.keyboard("{Control>}k{/Control}");

    // The palette should be open
    await waitFor(() => {
      expect(screen.getByPlaceholderText("Type a command...")).toBeInTheDocument();
    });

    // The cached run should appear in the palette
    expect(screen.getByText("My Cached Run")).toBeInTheDocument();
    expect(screen.getByText("Recent Runs")).toBeInTheDocument();
  });

  it("does not show Recent Runs when cache is empty", async () => {
    const user = userEvent.setup();

    renderAppLayout(queryClient);

    await user.keyboard("{Control>}k{/Control}");

    await waitFor(() => {
      expect(screen.getByPlaceholderText("Type a command...")).toBeInTheDocument();
    });

    // No "Recent Runs" group should appear
    expect(screen.queryByText("Recent Runs")).not.toBeInTheDocument();
  });

  it("handles cached runs with filters (non-undefined status) in query key", async () => {
    const user = userEvent.setup();

    // Seed cache with a filtered query key
    const runsPage: PageResponse<RunSummary> = {
      content: [
        {
          id: "filtered-run-1",
          name: "Filtered Run",
          graphTemplateId: "tpl-1",
          templateName: "TestTemplate",
          status: "completed",
          startedAt: null,
          completedAt: null,
          createdAt: "2025-01-01",
          autopilotId: null,
          softwareProject: null,
        },
      ],
      totalElements: 1,
      totalPages: 1,
      size: 20,
      number: 0,
      first: true,
      last: true,
      empty: false,
    };
    queryClient.setQueryData(["runs", "completed", undefined, undefined], runsPage);

    renderAppLayout(queryClient);

    await user.keyboard("{Control>}k{/Control}");

    await waitFor(() => {
      expect(screen.getByPlaceholderText("Type a command...")).toBeInTheDocument();
    });

    // Should still find runs via prefix match even with non-default query key
    expect(screen.getByText("Filtered Run")).toBeInTheDocument();
  });

  // -------------------------------------------------------------------------
  // Mobile layout tests
  // -------------------------------------------------------------------------

  it("renders hamburger button on mobile", () => {
    mockUseMobileBreakpoint.mockReturnValue(true);
    renderAppLayout(queryClient);

    expect(screen.getByTestId("mobile-menu-button")).toBeInTheDocument();
  });

  it("sidebar is not visible by default on mobile", () => {
    mockUseMobileBreakpoint.mockReturnValue(true);
    renderAppLayout(queryClient);

    // Navigation items should not be visible without opening the drawer
    // (the drawer is closed by default)
    expect(screen.queryByTestId("nav-runs")).not.toBeInTheDocument();
  });

  it("desktop layout does not render hamburger button", () => {
    mockUseMobileBreakpoint.mockReturnValue(false);
    renderAppLayout(queryClient);

    expect(screen.queryByTestId("mobile-menu-button")).not.toBeInTheDocument();
    // Sidebar is always visible on desktop
    expect(screen.getByText("ChorusKube")).toBeInTheDocument();
  });

  // -------------------------------------------------------------------------
  // Dynamic viewport height + full-bleed opt-in
  // -------------------------------------------------------------------------

  it("sizes the root to the dynamic viewport height", () => {
    const { container } = renderAppLayout(queryClient);
    expect(container.firstElementChild).toHaveClass("h-dvh");
  });

  it("pads the main area by default", () => {
    renderAppLayout(queryClient);
    const main = screen.getByText("Runs Page").closest("main");
    expect(main).toHaveClass("overflow-auto");
    expect(main?.className).toMatch(/\bp-4\b/);
  });

  it("removes padding and scroll when a page opts into full-bleed", () => {
    renderAppLayout(queryClient, <ToggleableFullBleedPage />);
    const main = screen.getByTestId("full-bleed-child").closest("main");
    expect(main).toHaveClass("overflow-hidden");
    expect(main?.className).not.toMatch(/\bp-4\b/);
  });

  it("restores the padded, scrolling main once the full-bleed page unmounts", async () => {
    const user = userEvent.setup();
    renderAppLayout(queryClient, <ToggleableFullBleedPage />);

    const main = screen.getByTestId("full-bleed-child").closest("main");
    expect(main).toHaveClass("overflow-hidden");

    await user.click(screen.getByText("Unmount"));

    expect(main).toHaveClass("overflow-auto");
    expect(main?.className).toMatch(/\bp-4\b/);
  });
});
