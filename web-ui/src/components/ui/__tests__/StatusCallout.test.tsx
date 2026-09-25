import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { Ban } from "lucide-react";
import StatusCallout from "../StatusCallout";

describe("StatusCallout", () => {
  it("renders a title and children", () => {
    render(
      <StatusCallout tone="warning" title="Heads up">
        Something needs attention.
      </StatusCallout>
    );
    expect(screen.getByText("Heads up")).toBeInTheDocument();
    expect(screen.getByText("Something needs attention.")).toBeInTheDocument();
  });

  it("passes role through, and has no role when omitted", () => {
    const { rerender } = render(
      <StatusCallout tone="error" role="alert">
        broken
      </StatusCallout>
    );
    expect(screen.getByRole("alert")).toBeInTheDocument();

    rerender(<StatusCallout tone="error">broken</StatusCallout>);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("carries data-tone and the callout recipe, with no text-status-* on the root", () => {
    const { container } = render(
      <StatusCallout tone="info" data-testid="callout">
        info body
      </StatusCallout>
    );
    const root = container.querySelector('[data-testid="callout"]');
    expect(root).toHaveAttribute("data-tone", "info");
    expect(root?.className).toContain("border-status-info/40");
    expect(root?.className).toContain("bg-status-info/10");
    expect(root?.className).not.toMatch(/\btext-status-/);
  });

  it("renders a default icon per tone as an aria-hidden svg carrying the tone text class", () => {
    const { container } = render(<StatusCallout tone="success">ok</StatusCallout>);
    const icon = container.querySelector("svg");
    expect(icon).toHaveAttribute("aria-hidden", "true");
    expect(icon?.getAttribute("class")).toContain("text-status-success");
  });

  it("lets the icon prop override the default", () => {
    const { container } = render(
      <StatusCallout tone="warning" icon={Ban}>
        overridden
      </StatusCallout>
    );
    expect(container.querySelector("svg.lucide-ban")).toBeInTheDocument();
  });
});
