import { render, screen } from "@testing-library/react";
import { describe, it, expect } from "vitest";
import PageShell from "../PageShell";

describe("PageShell", () => {
  it("renders children", () => {
    render(
      <PageShell>
        <p>Hello world</p>
      </PageShell>,
    );
    expect(screen.getByText("Hello world")).toBeInTheDocument();
  });

  it('applies space-y-4 by default (normal spacing)', () => {
    const { container } = render(
      <PageShell>
        <p>Content</p>
      </PageShell>,
    );
    expect(container.firstChild).toHaveClass("space-y-4");
  });

  it('applies space-y-4 for spacing="normal"', () => {
    const { container } = render(
      <PageShell spacing="normal">
        <p>Content</p>
      </PageShell>,
    );
    expect(container.firstChild).toHaveClass("space-y-4");
  });

  it('applies space-y-6 for spacing="relaxed"', () => {
    const { container } = render(
      <PageShell spacing="relaxed">
        <p>Content</p>
      </PageShell>,
    );
    expect(container.firstChild).toHaveClass("space-y-6");
  });
});
