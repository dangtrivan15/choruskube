import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { createRef } from "react";
import { Input, inputVariants } from "../input";

describe("inputVariants", () => {
  it("includes shared base classes", () => {
    const classes = inputVariants();
    expect(classes).toContain("flex");
    expect(classes).toContain("w-full");
    expect(classes).toContain("rounded-lg");
    expect(classes).toContain("border");
    expect(classes).toContain("border-input");
    expect(classes).toContain("bg-transparent");
    expect(classes).toContain("outline-none");
    expect(classes).toContain("transition-colors");
  });

  it("includes placeholder, focus-visible, disabled, and aria-invalid states", () => {
    const classes = inputVariants();
    expect(classes).toContain("placeholder:text-muted-foreground");
    expect(classes).toContain("focus-visible:border-ring");
    expect(classes).toContain("focus-visible:ring-3");
    expect(classes).toContain("disabled:cursor-not-allowed");
    expect(classes).toContain("disabled:opacity-50");
    expect(classes).toContain("aria-invalid:border-destructive");
  });

  it("defaults to h-9 size", () => {
    const classes = inputVariants();
    expect(classes).toContain("h-9");
  });

  it("applies sm size variant", () => {
    const classes = inputVariants({ size: "sm" });
    expect(classes).toContain("h-8");
    expect(classes).not.toContain("h-9");
  });

  it("applies lg size variant", () => {
    const classes = inputVariants({ size: "lg" });
    expect(classes).toContain("h-10");
    expect(classes).not.toContain("h-9");
  });

  it("applies ghost variant with border-transparent", () => {
    const classes = inputVariants({ variant: "ghost" });
    expect(classes).toContain("border-transparent");
    expect(classes).toContain("shadow-none");
  });

  it("omits height classes when size is null", () => {
    const classes = inputVariants({ size: null });
    expect(classes).not.toContain("h-9");
    expect(classes).not.toContain("h-8");
    expect(classes).not.toContain("h-10");
    // base classes should still be present
    expect(classes).toContain("flex");
    expect(classes).toContain("rounded-lg");
  });

  it("appends custom className", () => {
    const classes = inputVariants({ className: "my-custom-class" });
    expect(classes).toContain("my-custom-class");
  });
});

describe("Input component", () => {
  it("renders an input element with data-slot attribute", () => {
    render(<Input data-testid="test-input" />);
    const input = screen.getByTestId("test-input");
    expect(input.tagName).toBe("INPUT");
    expect(input).toHaveAttribute("data-slot", "input");
  });

  it("forwards ref to the underlying input element", () => {
    const ref = createRef<HTMLInputElement>();
    render(<Input ref={ref} />);
    expect(ref.current).toBeInstanceOf(HTMLInputElement);
    expect(ref.current).toHaveAttribute("data-slot", "input");
  });

  it("applies variant and size classes to the rendered element", () => {
    render(<Input data-testid="styled" variant="ghost" size="sm" />);
    const input = screen.getByTestId("styled");
    expect(input.className).toContain("border-transparent");
    expect(input.className).toContain("h-8");
  });

  it("spreads native HTML props onto the input element", () => {
    render(
      <Input
        data-testid="native"
        type="email"
        placeholder="Enter email"
        disabled
      />
    );
    const input = screen.getByTestId("native");
    expect(input).toHaveAttribute("type", "email");
    expect(input).toHaveAttribute("placeholder", "Enter email");
    expect(input).toBeDisabled();
  });

  it("merges custom className with variant classes", () => {
    render(<Input data-testid="merged" className="my-extra-class" />);
    const input = screen.getByTestId("merged");
    expect(input.className).toContain("my-extra-class");
    expect(input.className).toContain("rounded-lg");
  });
});
