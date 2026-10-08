import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Link } from "react-router";
import { renderWithProviders } from "@/__tests__/test-utils";
import TruncatedText from "../TruncatedText";

describe("TruncatedText", () => {
  it("renders children text", () => {
    renderWithProviders(<TruncatedText>Hello World</TruncatedText>);
    expect(screen.getByText("Hello World")).toBeInTheDocument();
  });

  it("applies truncate class on trigger element", () => {
    renderWithProviders(<TruncatedText>Some text</TruncatedText>);
    const el = screen.getByText("Some text");
    expect(el).toHaveClass("truncate");
  });

  it("renders as span by default", () => {
    renderWithProviders(<TruncatedText>Default span</TruncatedText>);
    const el = screen.getByText("Default span");
    expect(el.tagName).toBe("SPAN");
  });

  it("supports as prop to render as different element", () => {
    renderWithProviders(<TruncatedText as="h3">Title</TruncatedText>);
    const el = screen.getByText("Title");
    expect(el.tagName).toBe("H3");
  });

  it("passes through additional className", () => {
    renderWithProviders(
      <TruncatedText className="text-sm font-semibold">Styled</TruncatedText>
    );
    const el = screen.getByText("Styled");
    expect(el).toHaveClass("truncate");
    expect(el).toHaveClass("text-sm");
    expect(el).toHaveClass("font-semibold");
  });

  it("shows tooltip content on hover", async () => {
    const user = userEvent.setup();
    renderWithProviders(<TruncatedText>Hover me</TruncatedText>);

    const trigger = screen.getByText("Hover me");
    await user.hover(trigger);

    await waitFor(() => {
      const instances = screen.getAllByText("Hover me");
      // Text appears in both the trigger and the tooltip content
      expect(instances.length).toBeGreaterThanOrEqual(2);
    });
  });

  it("renders the given render element as the trigger, merging its own props", () => {
    renderWithProviders(
      <TruncatedText render={<Link to="/x" />}>Link text</TruncatedText>
    );
    const el = screen.getByText("Link text");
    expect(el.tagName).toBe("A");
    expect(el).toHaveAttribute("href", "/x");
    expect(el).toHaveClass("truncate");
  });

  it("ignores `as` when `render` is set", () => {
    renderWithProviders(
      <TruncatedText as="h3" render={<Link to="/y" />}>Link text</TruncatedText>
    );
    expect(screen.getByText("Link text").tagName).toBe("A");
  });

  it("shows the tooltip override instead of children", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <TruncatedText tooltip="Full title text">Short</TruncatedText>
    );

    await user.hover(screen.getByText("Short"));

    await waitFor(() => {
      expect(screen.getByText("Full title text")).toBeInTheDocument();
    });
    expect(screen.queryAllByText("Short")).toHaveLength(1);
  });

  it("passes data-testid through to the trigger", () => {
    renderWithProviders(
      <TruncatedText data-testid="my-truncated">Hello</TruncatedText>
    );
    expect(screen.getByTestId("my-truncated")).toHaveTextContent("Hello");
  });
});
