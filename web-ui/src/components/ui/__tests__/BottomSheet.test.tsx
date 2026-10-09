import { describe, it, expect, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import BottomSheet from "../BottomSheet";

describe("BottomSheet", () => {
  it("renders nothing when closed", () => {
    renderWithProviders(
      <BottomSheet open={false} onOpenChange={vi.fn()} title="Run info" data-testid="sheet">
        <p>Body</p>
      </BottomSheet>,
    );
    expect(screen.queryByTestId("sheet")).not.toBeInTheDocument();
  });

  it("renders the popup with its test id and an accessible name from the title when open", () => {
    renderWithProviders(
      <BottomSheet open onOpenChange={vi.fn()} title="Run info" data-testid="sheet">
        <p>Body</p>
      </BottomSheet>,
    );
    const popup = screen.getByTestId("sheet");
    expect(popup).toBeInTheDocument();
    expect(popup).toHaveAccessibleName("Run info");
  });

  it("calls onOpenChange(false) when the close button is clicked", async () => {
    const user = userEvent.setup();
    const onOpenChange = vi.fn();
    renderWithProviders(
      <BottomSheet open onOpenChange={onOpenChange} title="Run info" data-testid="sheet">
        <p>Body</p>
      </BottomSheet>,
    );

    await user.click(screen.getByTestId("bottom-sheet-close"));
    expect(onOpenChange.mock.calls[0]?.[0]).toBe(false);
  });

  it("calls onOpenChange(false) on Escape", async () => {
    const user = userEvent.setup();
    const onOpenChange = vi.fn();
    renderWithProviders(
      <BottomSheet open onOpenChange={onOpenChange} title="Run info" data-testid="sheet">
        <p>Body</p>
      </BottomSheet>,
    );

    await user.keyboard("{Escape}");
    await waitFor(() => expect(onOpenChange.mock.calls[0]?.[0]).toBe(false));
  });

  it("hides the close button when showClose is false", () => {
    renderWithProviders(
      <BottomSheet open onOpenChange={vi.fn()} title="Run info" showClose={false} data-testid="sheet">
        <p>Body</p>
      </BottomSheet>,
    );
    expect(screen.queryByTestId("bottom-sheet-close")).not.toBeInTheDocument();
  });

  it("keeps the accessible name when hideTitle visually hides the title", () => {
    renderWithProviders(
      <BottomSheet open onOpenChange={vi.fn()} title="Run info" hideTitle data-testid="sheet">
        <p>Body</p>
      </BottomSheet>,
    );
    expect(screen.getByTestId("sheet")).toHaveAccessibleName("Run info");
    expect(screen.getByText("Run info")).toHaveClass("sr-only");
  });

  it("calls onOpenChange(false) on an outside (backdrop) click", async () => {
    const user = userEvent.setup();
    const onOpenChange = vi.fn();
    renderWithProviders(
      <BottomSheet open onOpenChange={onOpenChange} title="Run info" data-testid="sheet">
        <p>Body</p>
      </BottomSheet>,
    );

    // Clicking outside the popup is what the backdrop's pointer-dismissal covers —
    // Base UI tracks outside presses on the document, not specifically on the
    // backdrop element, so this exercises the same dismissal path.
    await user.click(document.body);
    await waitFor(() => expect(onOpenChange.mock.calls[0]?.[0]).toBe(false));
  });

  it("never puts initial focus on a text input even when one is the first tabbable child", async () => {
    renderWithProviders(
      <BottomSheet open onOpenChange={vi.fn()} title="Feedback" data-testid="sheet">
        <textarea data-testid="feedback-textarea" />
      </BottomSheet>,
    );

    await waitFor(() => {
      expect(document.activeElement).not.toBe(screen.getByTestId("feedback-textarea"));
    });
  });
});
