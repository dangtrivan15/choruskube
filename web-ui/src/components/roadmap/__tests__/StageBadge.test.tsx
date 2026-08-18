import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/__tests__/test-utils";
import StageBadge from "@/components/roadmap/StageBadge";

describe("StageBadge", () => {
  it.each([
    ["backlog", "backlog"],
    ["in_progress", "in progress"],
    ["rolled_out", "rolled out"],
  ])("renders %s as %s", (stage, label) => {
    renderWithProviders(<StageBadge stage={stage} data-testid="stage" />);
    expect(screen.getByTestId("stage")).toHaveTextContent(label);
  });

  // The column is a Postgres enum extended via ALTER TYPE, so a newer API pod can serve a stage
  // this build has never heard of. Render it verbatim rather than crashing the page.
  it("renders an unrecognized stage verbatim", () => {
    renderWithProviders(<StageBadge stage="mothballed" data-testid="stage" />);
    expect(screen.getByTestId("stage")).toHaveTextContent("mothballed");
  });
});
