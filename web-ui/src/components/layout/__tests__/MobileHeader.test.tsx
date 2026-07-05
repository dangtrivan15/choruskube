import { describe, it, expect, vi } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import MobileHeader from "@/components/layout/MobileHeader";

describe("MobileHeader", () => {
  it("renders the brand SVG mark next to the ChorusKube wordmark", () => {
    renderWithProviders(<MobileHeader onMenuToggle={vi.fn()} onActivityFeedToggle={vi.fn()} />);

    const wordmark = screen.getByText("ChorusKube");
    const brandRow = wordmark.parentElement;
    expect(brandRow).not.toBeNull();
    const mark = brandRow?.querySelector('[data-testid="logo-mark"]');
    expect(mark).not.toBeNull();
    expect(mark?.tagName.toLowerCase()).toBe("svg");
  });

  it("invokes onMenuToggle when the hamburger is clicked", async () => {
    const user = userEvent.setup();
    const onMenuToggle = vi.fn();

    renderWithProviders(
      <MobileHeader onMenuToggle={onMenuToggle} onActivityFeedToggle={vi.fn()} />,
    );

    await user.click(screen.getByTestId("mobile-menu-button"));
    expect(onMenuToggle).toHaveBeenCalledTimes(1);
  });
});
