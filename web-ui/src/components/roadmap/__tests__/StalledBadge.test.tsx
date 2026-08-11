import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/__tests__/test-utils";
import StalledBadge from "@/components/roadmap/StalledBadge";

describe("StalledBadge", () => {
  it("renders nothing for stalled=false", () => {
    const { container } = renderWithProviders(<StalledBadge stalled={false} data-testid="badge" />);
    expect(container).toBeEmptyDOMElement();
  });

  it("renders a Stalled badge with icon, label, and title for stalled=true", () => {
    renderWithProviders(<StalledBadge stalled data-testid="badge" />);
    expect(screen.getByTestId("badge")).toHaveTextContent("Stalled");
    expect(screen.getByTestId("badge")).toHaveAttribute("title", "Stalled — no recent activity");
    expect(screen.getByTestId("badge").querySelector("svg")).toBeInTheDocument();
  });

  it("forwards data-testid", () => {
    renderWithProviders(<StalledBadge stalled data-testid="my-stalled-badge" />);
    expect(screen.getByTestId("my-stalled-badge")).toBeInTheDocument();
  });

  it("renders without a data-testid too", () => {
    renderWithProviders(<StalledBadge stalled />);
    expect(screen.getByText("Stalled")).toBeInTheDocument();
  });

  it("applies the compact size variant", () => {
    renderWithProviders(<StalledBadge stalled size="compact" data-testid="badge" />);
    expect(screen.getByTestId("badge").className).toContain("text-[10px]");
  });

  it("applies the default size variant", () => {
    renderWithProviders(<StalledBadge stalled data-testid="badge" />);
    expect(screen.getByTestId("badge").className).toContain("text-xs");
  });
});
