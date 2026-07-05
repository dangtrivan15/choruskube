import { describe, it, expect, vi } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import ActivityFeedButton from "@/components/layout/ActivityFeedButton";

describe("ActivityFeedButton", () => {
  it("renders the bell button with correct label", () => {
    renderWithProviders(<ActivityFeedButton onClick={() => {}} />);
    expect(screen.getByRole("button", { name: "Activity feed" })).toBeInTheDocument();
  });

  it("calls onClick when clicked", async () => {
    const user = userEvent.setup();
    const onClick = vi.fn();
    renderWithProviders(<ActivityFeedButton onClick={onClick} />);

    await user.click(screen.getByRole("button"));
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it("does not show badge when unread count is 0", () => {
    const { container } = renderWithProviders(
      <ActivityFeedButton onClick={() => {}} />,
    );
    // No badge span should exist
    expect(container.querySelector(".bg-destructive")).toBeNull();
  });
});
