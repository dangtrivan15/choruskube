import { useEffect } from "react";
import { describe, it, expect, vi } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/__tests__/test-utils";
import ActivityFeedButton from "@/components/layout/ActivityFeedButton";
import { useActivityFeed } from "@/hooks/useActivityFeed";

/** Adds `count` entries timestamped after the provider's mount, so all of them count as unread. */
function SeedUnread({ count }: { count: number }) {
  const { addEntry } = useActivityFeed();
  useEffect(() => {
    for (let i = 0; i < count; i++) {
      addEntry({ id: `entry-${i}`, timestamp: Date.now() + 60_000, message: "Gate opened", variant: "warning" });
    }
  }, [addEntry, count]);
  return null;
}

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

  it("does not show the unread count when nothing is unread", () => {
    renderWithProviders(<ActivityFeedButton onClick={() => {}} />);
    expect(screen.queryByTestId("activity-feed-unread-count")).toBeNull();
  });

  it("shows the unread count in ink on an opaque error tint", async () => {
    renderWithProviders(
      <>
        <SeedUnread count={3} />
        <ActivityFeedButton onClick={() => {}} />
      </>,
    );

    const count = await screen.findByTestId("activity-feed-unread-count");
    expect(count).toHaveTextContent("3");
    expect(count).toHaveClass("text-foreground", "bg-tint-status-error");
    expect(screen.getByRole("button", { name: "Activity feed — 3 unread" })).toBeInTheDocument();
  });
});
