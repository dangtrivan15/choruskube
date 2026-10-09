import { describe, it, expect, vi } from "vitest";
import { renderWithProviders } from "@/__tests__/test-utils";
import DecisionButtons from "../DecisionButtons";

describe("DecisionButtons", () => {
  it("wraps in an @container so panel-hosted instances read their own width", () => {
    const { container } = renderWithProviders(
      <DecisionButtons
        options={["approved", "rejected"]}
        onSubmit={vi.fn()}
        isPending={false}
        feedback=""
      />,
    );
    expect(container.firstElementChild).toHaveClass("@container");
  });

  it("uses the container-query row variant, not the viewport one", () => {
    const { container } = renderWithProviders(
      <DecisionButtons
        options={["approved", "rejected"]}
        onSubmit={vi.fn()}
        isPending={false}
        feedback=""
      />,
    );
    const row = container.querySelector(".flex.flex-col");
    expect(row).toHaveClass("@md:flex-row");
    expect(row?.className).not.toMatch(/(?<!@)md:flex-row/);
  });
});
