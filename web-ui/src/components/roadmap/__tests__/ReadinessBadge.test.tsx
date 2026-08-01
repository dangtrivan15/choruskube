import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/__tests__/test-utils";
import ReadinessBadge from "@/components/roadmap/ReadinessBadge";

describe("ReadinessBadge", () => {
  it("renders nothing for null readiness", () => {
    const { container } = renderWithProviders(<ReadinessBadge readiness={null} data-testid="badge" />);
    expect(container).toBeEmptyDOMElement();
  });

  it("renders nothing for READY readiness", () => {
    const { container } = renderWithProviders(<ReadinessBadge readiness="READY" data-testid="badge" />);
    expect(container).toBeEmptyDOMElement();
  });

  it("renders a Blocked badge for BLOCKED readiness", () => {
    renderWithProviders(<ReadinessBadge readiness="BLOCKED" data-testid="badge" />);
    expect(screen.getByTestId("badge")).toHaveTextContent("Blocked");
  });

  it("forwards data-testid", () => {
    renderWithProviders(<ReadinessBadge readiness="BLOCKED" data-testid="my-readiness-badge" />);
    expect(screen.getByTestId("my-readiness-badge")).toBeInTheDocument();
  });

  it("renders without a data-testid too", () => {
    renderWithProviders(<ReadinessBadge readiness="BLOCKED" />);
    expect(screen.getByText("Blocked")).toBeInTheDocument();
  });

  it("applies the compact size variant", () => {
    renderWithProviders(<ReadinessBadge readiness="BLOCKED" size="compact" data-testid="badge" />);
    expect(screen.getByTestId("badge").className).toContain("text-[10px]");
  });
});
