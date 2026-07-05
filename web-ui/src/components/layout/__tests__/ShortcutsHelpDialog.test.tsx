import { describe, it, expect, vi } from "vitest";
import { screen } from "@testing-library/react";
import type { ReactNode } from "react";
import { renderWithProviders } from "@/__tests__/test-utils";
import ShortcutsHelpDialog from "@/components/layout/ShortcutsHelpDialog";
import { useAuth } from "@/components/AuthProvider";
import { ExtensionsProvider } from "@/ExtensionsContext";
import type { Command } from "@/lib/commands";

const cloudCommands: Command[] = [
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

function withCloud(children: ReactNode) {
  return <ExtensionsProvider value={{ commands: cloudCommands }}>{children}</ExtensionsProvider>;
}

vi.mock("@/components/AuthProvider", () => ({
  useAuth: vi.fn(() => ({
    organizationId: undefined,
    actingAsPlatformAdmin: false,
  })),
}));

const mockUseAuth = useAuth as ReturnType<typeof vi.fn>;

describe("ShortcutsHelpDialog", () => {
  const onOpenChange = vi.fn();

  it("renders nothing when closed", () => {
    renderWithProviders(
      <ShortcutsHelpDialog open={false} onOpenChange={onOpenChange} />
    );
    expect(screen.queryByText("Keyboard Shortcuts")).not.toBeInTheDocument();
  });

  it("shows title when open", () => {
    renderWithProviders(
      <ShortcutsHelpDialog open={true} onOpenChange={onOpenChange} />
    );
    expect(screen.getByText("Keyboard Shortcuts")).toBeInTheDocument();
  });

  it("shows description", () => {
    renderWithProviders(
      <ShortcutsHelpDialog open={true} onOpenChange={onOpenChange} />
    );
    expect(
      screen.getByText("Use these shortcuts to navigate quickly.")
    ).toBeInTheDocument();
  });

  it("displays group labels", () => {
    renderWithProviders(
      <ShortcutsHelpDialog open={true} onOpenChange={onOpenChange} />
    );
    expect(screen.getByText("Navigation")).toBeInTheDocument();
    expect(screen.getByText("Actions")).toBeInTheDocument();
  });

  it("shows command labels", () => {
    renderWithProviders(
      <ShortcutsHelpDialog open={true} onOpenChange={onOpenChange} />
    );
    expect(screen.getByText("Go to Runs")).toBeInTheDocument();
    expect(screen.getByText("Toggle Theme")).toBeInTheDocument();
    expect(screen.getByText("Show Keyboard Shortcuts")).toBeInTheDocument();
  });

  it("renders kbd elements for shortcut keys", () => {
    renderWithProviders(
      <ShortcutsHelpDialog open={true} onOpenChange={onOpenChange} />
    );
    const kbds = screen.getAllByText(
      (_, element) => element?.tagName === "KBD"
    );
    expect(kbds.length).toBeGreaterThan(0);
  });

  it("lists 'g o' shortcut row for org-bearing user", () => {
    mockUseAuth.mockReturnValue({
      organizationId: "o-1",
      actingAsPlatformAdmin: false,
    });
    renderWithProviders(
      withCloud(<ShortcutsHelpDialog open={true} onOpenChange={onOpenChange} />)
    );
    expect(screen.getByText("Go to My Organization")).toBeInTheDocument();
    // The dialog renders one <kbd> cell per shortcut token. "g o" splits into "g" + "o".
    const kbds = screen.getAllByText(
      (_, element) => element?.tagName === "KBD"
    );
    expect(kbds.some((el) => el.textContent === "o")).toBe(true);
  });

  it("hides 'Go to My Organization' for user with no organizationId", () => {
    mockUseAuth.mockReturnValue({
      organizationId: undefined,
      actingAsPlatformAdmin: false,
    });
    renderWithProviders(
      <ShortcutsHelpDialog open={true} onOpenChange={onOpenChange} />
    );
    expect(
      screen.queryByText("Go to My Organization")
    ).not.toBeInTheDocument();
  });

  it("lists 'g O' shortcut row for platform admin", () => {
    mockUseAuth.mockReturnValue({
      organizationId: "o-1",
      actingAsPlatformAdmin: true,
    });
    renderWithProviders(
      withCloud(<ShortcutsHelpDialog open={true} onOpenChange={onOpenChange} />)
    );
    expect(screen.getByText("Go to Organizations")).toBeInTheDocument();
    // "g O" splits into two <kbd> cells: "g" and "O" (capital).
    const kbds = screen.getAllByText(
      (_, element) => element?.tagName === "KBD"
    );
    expect(kbds.some((el) => el.textContent === "O")).toBe(true);
  });

  it("hides 'Go to Organizations' for non-platform-admin", () => {
    mockUseAuth.mockReturnValue({
      organizationId: "o-1",
      actingAsPlatformAdmin: false,
    });
    renderWithProviders(
      <ShortcutsHelpDialog open={true} onOpenChange={onOpenChange} />
    );
    expect(
      screen.queryByText("Go to Organizations")
    ).not.toBeInTheDocument();
  });
});
