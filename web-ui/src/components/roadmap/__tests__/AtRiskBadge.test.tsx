import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/__tests__/test-utils";
import AtRiskBadge from "@/components/roadmap/AtRiskBadge";

describe("AtRiskBadge", () => {
  it("renders nothing when not at risk, even with a positive count", () => {
    const { container } = renderWithProviders(
      <AtRiskBadge atRisk={false} count={3} data-testid="badge" />
    );
    expect(container).toBeEmptyDOMElement();
  });

  it("renders an At Risk badge with the count, icon, and title when at risk", () => {
    renderWithProviders(<AtRiskBadge atRisk count={2} data-testid="badge" />);
    expect(screen.getByTestId("badge")).toHaveTextContent("At Risk (2)");
    expect(screen.getByTestId("badge")).toHaveAttribute(
      "title",
      "At risk — 2 items past target date"
    );
    expect(screen.getByTestId("badge").querySelector("svg")).toBeInTheDocument();
  });

  it("uses singular wording in the title for a count of 1", () => {
    renderWithProviders(<AtRiskBadge atRisk count={1} data-testid="badge" />);
    expect(screen.getByTestId("badge")).toHaveAttribute(
      "title",
      "At risk — 1 item past target date"
    );
  });

  it("forwards data-testid", () => {
    renderWithProviders(<AtRiskBadge atRisk count={1} data-testid="my-at-risk-badge" />);
    expect(screen.getByTestId("my-at-risk-badge")).toBeInTheDocument();
  });

  it("applies the compact size variant", () => {
    renderWithProviders(<AtRiskBadge atRisk count={1} size="compact" data-testid="badge" />);
    expect(screen.getByTestId("badge").className).toContain("text-[10px]");
  });

  it("applies the default size variant", () => {
    renderWithProviders(<AtRiskBadge atRisk count={1} data-testid="badge" />);
    expect(screen.getByTestId("badge").className).toContain("text-xs");
  });

  it("uses warning status color tokens", () => {
    renderWithProviders(<AtRiskBadge atRisk count={1} data-testid="badge" />);
    expect(screen.getByTestId("badge").className).toContain("status-warning");
  });
});
