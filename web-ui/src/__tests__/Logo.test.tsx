import { render, screen } from "@testing-library/react";
import { describe, it, expect } from "vitest";
import Logo from "@/components/Logo";

describe("Logo", () => {
  it("renders an SVG mark", () => {
    render(<Logo />);
    const svg = screen.getByTestId("logo-mark");
    expect(svg.tagName.toLowerCase()).toBe("svg");
  });

  it("is decorative by default (aria-hidden, no role)", () => {
    render(<Logo />);
    const svg = screen.getByTestId("logo-mark");
    expect(svg).toHaveAttribute("aria-hidden", "true");
    expect(svg).not.toHaveAttribute("role");
    expect(svg).not.toHaveAttribute("aria-label");
  });

  it("becomes a labeled image when aria-label is provided", () => {
    render(<Logo aria-label="ChorusKube" />);
    const svg = screen.getByRole("img", { name: "ChorusKube" });
    expect(svg).toHaveAttribute("aria-label", "ChorusKube");
    expect(svg).not.toHaveAttribute("aria-hidden");
  });

  it("respects a custom size prop", () => {
    render(<Logo size={42} />);
    const svg = screen.getByTestId("logo-mark");
    expect(svg).toHaveAttribute("width", "42");
    expect(svg).toHaveAttribute("height", "42");
  });

  it("defaults to size 22 when no size prop is given", () => {
    render(<Logo />);
    const svg = screen.getByTestId("logo-mark");
    expect(svg).toHaveAttribute("width", "22");
    expect(svg).toHaveAttribute("height", "22");
  });

  it("passes className through to the svg element", () => {
    render(<Logo className="rounded-md" />);
    const svg = screen.getByTestId("logo-mark");
    expect(svg).toHaveClass("rounded-md");
  });
});
