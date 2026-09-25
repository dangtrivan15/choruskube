import { describe, it, expect } from "vitest";
import { render } from "@testing-library/react";
import StatusDot from "../StatusDot";
import { STATUS_TONE_CLASSES, type StatusTone } from "@/lib/statusColors";

const TONES: StatusTone[] = ["success", "error", "info", "warning", "accent", "neutral"];

describe("StatusDot", () => {
  it("is aria-hidden", () => {
    const { container } = render(<StatusDot tone="success" data-testid="dot" />);
    expect(container.querySelector('[data-testid="dot"]')).toHaveAttribute("aria-hidden", "true");
  });

  it.each(TONES)("carries data-tone=%s and the tone's dot class", (tone) => {
    const { container } = render(<StatusDot tone={tone} data-testid="dot" />);
    const el = container.querySelector('[data-testid="dot"]');
    expect(el).toHaveAttribute("data-tone", tone);
    for (const cls of STATUS_TONE_CLASSES[tone].dot.split(" ")) {
      expect(el?.className).toContain(cls);
    }
  });

  it("merges an extra className", () => {
    const { container } = render(
      <StatusDot tone="success" className="ml-2" data-testid="dot" />
    );
    expect(container.querySelector('[data-testid="dot"]')?.className).toContain("ml-2");
  });
});
