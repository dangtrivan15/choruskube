import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import EmptyState from "../EmptyState";

describe("EmptyState", () => {
  it("renders title text", () => {
    render(<EmptyState title="Nothing here" />);
    expect(screen.getByText("Nothing here")).toBeInTheDocument();
  });

  it("renders icon when provided", () => {
    render(
      <EmptyState
        title="Empty"
        icon={<svg data-testid="test-icon" />}
      />
    );
    expect(screen.getByTestId("test-icon")).toBeInTheDocument();
  });

  it("renders description when provided", () => {
    render(
      <EmptyState title="Empty" description="Try adding something." />
    );
    expect(screen.getByText("Try adding something.")).toBeInTheDocument();
  });

  it("renders action slot when provided", () => {
    render(
      <EmptyState title="Empty" action={<button>Add Item</button>} />
    );
    expect(screen.getByRole("button", { name: "Add Item" })).toBeInTheDocument();
  });

  it("does not render icon wrapper when icon is not provided", () => {
    const { container } = render(<EmptyState title="Bare" />);
    const outerDiv = container.firstElementChild!;
    // Should only have the title paragraph, no icon or description wrappers
    expect(outerDiv.children).toHaveLength(1);
  });

  it("does not render description when not provided", () => {
    render(<EmptyState title="Title Only" />);
    expect(screen.queryByText(/./)).toHaveTextContent("Title Only");
  });

  it("does not render action wrapper when not provided", () => {
    const { container } = render(<EmptyState title="No Action" description="Some desc" />);
    const outerDiv = container.firstElementChild!;
    // Should have title + description = 2 children
    expect(outerDiv.children).toHaveLength(2);
  });
});
