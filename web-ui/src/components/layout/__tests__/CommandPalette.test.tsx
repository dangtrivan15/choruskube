import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import CommandPalette from "@/components/layout/CommandPalette";
import { useAuth } from "@/components/AuthProvider";
import { ExtensionsProvider } from "@/ExtensionsContext";
import type { Command } from "@/lib/commands";

vi.mock("@/components/AuthProvider", () => ({
  useAuth: vi.fn(() => ({
    organizationId: undefined,
    actingAsPlatformAdmin: false,
  })),
}));

const mockUseAuth = useAuth as ReturnType<typeof vi.fn>;

describe("CommandPalette", () => {
  const onOpenChange = vi.fn();
  const onExecute = vi.fn();

  beforeEach(() => {
    vi.clearAllMocks();
  });

  function renderPalette(open = true) {
    return renderWithProviders(
      <CommandPalette
        open={open}
        onOpenChange={onOpenChange}
        onExecute={onExecute}
      />
    );
  }

  /** Wait for the input to be focused (requestAnimationFrame in component). */
  async function waitForInputFocus() {
    const input = screen.getByPlaceholderText("Type a command...");
    await waitFor(() => {
      expect(document.activeElement).toBe(input);
    });
    return input;
  }

  // ---------------------------------------------------------------
  // Rendering
  // ---------------------------------------------------------------

  it("renders nothing when closed", () => {
    renderPalette(false);
    expect(
      screen.queryByPlaceholderText("Type a command...")
    ).not.toBeInTheDocument();
  });

  it("renders search input when open", () => {
    renderPalette(true);
    expect(
      screen.getByPlaceholderText("Type a command...")
    ).toBeInTheDocument();
  });

  it("shows navigation commands by default", () => {
    renderPalette(true);
    expect(screen.getByText("Go to Runs")).toBeInTheDocument();
    expect(screen.getByText("Go to Approvals")).toBeInTheDocument();
    expect(screen.getByText("Go to Templates")).toBeInTheDocument();
    expect(screen.getByText("Go to Roadmap")).toBeInTheDocument();
  });

  it("shows action commands by default", () => {
    renderPalette(true);
    expect(screen.getByText("Toggle Theme")).toBeInTheDocument();
    expect(screen.getByText("Show Keyboard Shortcuts")).toBeInTheDocument();
  });

  it("renders group labels", () => {
    renderPalette(true);
    expect(screen.getByText("Navigation")).toBeInTheDocument();
    expect(screen.getByText("Actions")).toBeInTheDocument();
  });

  // ---------------------------------------------------------------
  // Search / Filtering
  // ---------------------------------------------------------------

  it("filters commands as user types", async () => {
    const user = userEvent.setup();
    renderPalette(true);

    const input = await waitForInputFocus();
    await user.type(input, "toggle");

    expect(screen.getByText("Toggle Theme")).toBeInTheDocument();
    expect(screen.queryByText("Go to Runs")).not.toBeInTheDocument();
  });

  it("shows no results message for unmatched query", async () => {
    const user = userEvent.setup();
    renderPalette(true);

    const input = await waitForInputFocus();
    await user.type(input, "xyznonexistent");

    expect(screen.getByText("No results found.")).toBeInTheDocument();
  });

  // ---------------------------------------------------------------
  // Keyboard navigation
  // ---------------------------------------------------------------

  it("navigates items with arrow keys", async () => {
    const user = userEvent.setup();
    renderPalette(true);

    await waitForInputFocus();

    // First item is active by default
    const firstItem = screen
      .getByText("Go to Runs")
      .closest("[role='option']");
    expect(firstItem).toHaveAttribute("data-active", "true");

    // Press down
    await user.keyboard("{ArrowDown}");
    const secondItem = screen
      .getByText("Go to Approvals")
      .closest("[role='option']");
    expect(secondItem).toHaveAttribute("data-active", "true");
  });

  it("executes selected command on Enter", async () => {
    const user = userEvent.setup();
    renderPalette(true);

    await waitForInputFocus();
    await user.keyboard("{Enter}");

    expect(onExecute).toHaveBeenCalledTimes(1);
    expect(onExecute).toHaveBeenCalledWith(
      expect.objectContaining({ id: "nav:runs" })
    );
    expect(onOpenChange).toHaveBeenCalledWith(false);
  });

  it("wraps around when pressing ArrowUp from the beginning", async () => {
    const user = userEvent.setup();
    renderPalette(true);

    await waitForInputFocus();

    // Press ArrowUp from the beginning to wrap to the end
    await user.keyboard("{ArrowUp}");

    // The last item should now be active
    const lastItem = screen
      .getByText("Show Keyboard Shortcuts")
      .closest("[role='option']");
    expect(lastItem).toHaveAttribute("data-active", "true");
  });

  // ---------------------------------------------------------------
  // Mouse interaction
  // ---------------------------------------------------------------

  it("executes command on click", async () => {
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    renderPalette(true);

    await user.click(screen.getByText("Toggle Theme"));

    expect(onExecute).toHaveBeenCalledWith(
      expect.objectContaining({ id: "action:toggle-theme" })
    );
  });

  it("highlights item on mouse hover", async () => {
    const user = userEvent.setup({ pointerEventsCheck: 0 });
    renderPalette(true);

    const item = screen
      .getByText("Toggle Theme")
      .closest("[role='option']")!;
    await user.hover(item);

    expect(item).toHaveAttribute("data-active", "true");
  });

  // ---------------------------------------------------------------
  // Accessibility
  // ---------------------------------------------------------------

  it("has proper ARIA attributes on input", () => {
    renderPalette(true);
    const input = screen.getByPlaceholderText("Type a command...");
    expect(input).toHaveAttribute("role", "combobox");
    expect(input).toHaveAttribute("aria-expanded", "true");
    expect(input).toHaveAttribute("aria-controls", "command-palette-list");
  });

  it("has listbox role on results container", () => {
    renderPalette(true);
    expect(screen.getByRole("listbox")).toBeInTheDocument();
  });

  it("has option roles on command items", () => {
    renderPalette(true);
    const options = screen.getAllByRole("option");
    expect(options.length).toBeGreaterThan(0);
  });

  // ---------------------------------------------------------------
  // Runs integration
  // ---------------------------------------------------------------

  it("shows run commands when runs are provided", () => {
    renderWithProviders(
      <CommandPalette
        open={true}
        onOpenChange={onOpenChange}
        onExecute={onExecute}
        runs={[
          {
            id: "run-abc-123",
            name: "My Test Run",
            graphTemplateId: "tpl-1",
            templateName: "Test",
            status: "running",
            startedAt: null,
            completedAt: null,
            createdAt: "2025-01-01",
            autopilotId: null,
            softwareProject: null,
          },
        ]}
      />
    );

    expect(screen.getByText("My Test Run")).toBeInTheDocument();
    expect(screen.getByText("Recent Runs")).toBeInTheDocument();
  });

  // ---------------------------------------------------------------
  // Org gating (visibleWhen)
  // ---------------------------------------------------------------

  // Org commands are injected by an extension entrypoint (AppExtensions.commands), not core.
  // These tests verify the palette renders injected commands and honours their visibleWhen.
  describe("org gating (injected extension commands)", () => {
    const injectedCommands: Command[] = [
      {
        id: "nav:my-organization",
        label: "Go to My Organization",
        category: "navigation",
        shortcut: "g o",
        visibleWhen: (a) => !!a.organizationId,
      },
      {
        id: "nav:admin-organizations",
        label: "Go to Organizations",
        category: "navigation",
        shortcut: "g O",
        visibleWhen: (a) => a.actingAsPlatformAdmin,
      },
    ];

    function renderWithCommands() {
      return renderWithProviders(
        <ExtensionsProvider value={{ commands: injectedCommands }}>
          <CommandPalette open={true} onOpenChange={onOpenChange} onExecute={onExecute} />
        </ExtensionsProvider>
      );
    }

    it("renders 'Go to My Organization' when organizationId is set", () => {
      mockUseAuth.mockReturnValue({ organizationId: "o-1", actingAsPlatformAdmin: false });
      renderWithCommands();
      expect(screen.getByText("Go to My Organization")).toBeInTheDocument();
    });

    it("renders 'Go to Organizations' only for platform admin", () => {
      mockUseAuth.mockReturnValue({ organizationId: "o-1", actingAsPlatformAdmin: true });
      renderWithCommands();
      expect(screen.getByText("Go to Organizations")).toBeInTheDocument();
    });

    it("does NOT render 'Go to My Organization' when organizationId is undefined", () => {
      mockUseAuth.mockReturnValue({ organizationId: undefined, actingAsPlatformAdmin: false });
      renderWithCommands();
      expect(screen.queryByText("Go to My Organization")).not.toBeInTheDocument();
    });

    it("does NOT render 'Go to Organizations' for non-admin org member", () => {
      mockUseAuth.mockReturnValue({ organizationId: "o-1", actingAsPlatformAdmin: false });
      renderWithCommands();
      expect(screen.queryByText("Go to Organizations")).not.toBeInTheDocument();
    });

    it("renders no org commands in OSS (none injected)", () => {
      mockUseAuth.mockReturnValue({ organizationId: "o-1", actingAsPlatformAdmin: true });
      renderWithProviders(
        <CommandPalette open={true} onOpenChange={onOpenChange} onExecute={onExecute} />
      );
      expect(screen.queryByText("Go to My Organization")).not.toBeInTheDocument();
      expect(screen.queryByText("Go to Organizations")).not.toBeInTheDocument();
    });
  });
});
