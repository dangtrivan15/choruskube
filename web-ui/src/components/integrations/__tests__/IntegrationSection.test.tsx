import { render, screen } from "@testing-library/react";
import { describe, it, expect } from "vitest";
import IntegrationSection from "../IntegrationSection";

describe("IntegrationSection", () => {
  it("renders title as an h3 with text-base font-semibold", () => {
    render(
      <IntegrationSection title="GitHub Integration" description="desc">
        <div>body</div>
      </IntegrationSection>,
    );
    const heading = screen.getByRole("heading", { name: "GitHub Integration" });
    expect(heading).toBeInTheDocument();
    expect(heading.tagName).toBe("H3");
    expect(heading.className).toContain("text-base");
    expect(heading.className).toContain("font-semibold");
  });

  it("renders description below the title", () => {
    render(
      <IntegrationSection title="X" description="A description string">
        <div>body</div>
      </IntegrationSection>,
    );
    expect(screen.getByText("A description string")).toBeInTheDocument();
  });

  it("renders children inside the section", () => {
    render(
      <IntegrationSection title="X" description="d">
        <div data-testid="body">body</div>
      </IntegrationSection>,
    );
    expect(screen.getByTestId("body")).toBeInTheDocument();
  });

  it("attaches data-testid when provided", () => {
    render(
      <IntegrationSection title="X" description="d" testId="my-section">
        <div>b</div>
      </IntegrationSection>,
    );
    expect(screen.getByTestId("my-section")).toBeInTheDocument();
  });
});
