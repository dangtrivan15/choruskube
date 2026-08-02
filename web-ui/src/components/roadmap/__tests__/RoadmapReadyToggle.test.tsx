import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import RoadmapReadyToggle from "@/components/roadmap/RoadmapReadyToggle";

describe("RoadmapReadyToggle", () => {
  it("renders unchecked by default", () => {
    render(<RoadmapReadyToggle checked={false} onChange={vi.fn()} />);
    const toggle = screen.getByTestId("ready-to-start-toggle");
    expect(toggle).toHaveAttribute("aria-pressed", "false");
  });

  it("renders checked when checked=true", () => {
    render(<RoadmapReadyToggle checked={true} onChange={vi.fn()} />);
    const toggle = screen.getByTestId("ready-to-start-toggle");
    expect(toggle).toHaveAttribute("aria-pressed", "true");
  });

  it("fires onChange with the new pressed state on click", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(<RoadmapReadyToggle checked={false} onChange={onChange} />);

    await user.click(screen.getByTestId("ready-to-start-toggle"));

    expect(onChange.mock.calls[0][0]).toBe(true);
  });

  it("fires onChange(false) when clicked while already checked", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    render(<RoadmapReadyToggle checked={true} onChange={onChange} />);

    await user.click(screen.getByTestId("ready-to-start-toggle"));

    expect(onChange.mock.calls[0][0]).toBe(false);
  });
});
