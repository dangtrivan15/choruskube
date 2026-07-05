import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import PageHeader from "../PageHeader";

describe("PageHeader", () => {
  it("renders title text in an h1 element", () => {
    render(<PageHeader title="My Page" />);
    const heading = screen.getByRole("heading", { level: 1 });
    expect(heading).toHaveTextContent("My Page");
  });

  it("forwards data-testid to the h1", () => {
    render(<PageHeader title="Test" data-testid="custom-heading" />);
    const heading = screen.getByTestId("custom-heading");
    expect(heading.tagName).toBe("H1");
  });

  it("renders children in the actions container", () => {
    render(
      <PageHeader title="Page">
        <button>Action</button>
      </PageHeader>
    );
    expect(screen.getByRole("button", { name: "Action" })).toBeInTheDocument();
  });

  it("does not render empty wrapper div when no children", () => {
    const { container } = render(<PageHeader title="Solo" />);
    // The outer div should only contain the h1, no second child div
    const outerDiv = container.firstElementChild!;
    expect(outerDiv.children).toHaveLength(1);
  });
});
