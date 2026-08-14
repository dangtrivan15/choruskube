import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import TriggerBanner from "../TriggerBanner";

describe("TriggerBanner", () => {
  it("renders nothing for approved", () => {
    const { container } = render(<TriggerBanner trigger={{ kind: "approved" }} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("renders the review_conflict banner", () => {
    render(<TriggerBanner trigger={{ kind: "review_conflict" }} />);
    expect(screen.getByText("Review conflict detected")).toBeInTheDocument();
  });

  it("renders the uncertainty banner", () => {
    render(<TriggerBanner trigger={{ kind: "uncertainty" }} />);
    expect(screen.getByText("Reviewer uncertain about fix")).toBeInTheDocument();
  });

  it("renders the alternative_proposal banner", () => {
    render(<TriggerBanner trigger={{ kind: "alternative_proposal" }} />);
    expect(screen.getByText("Alternative design proposed")).toBeInTheDocument();
  });

  // Regression coverage for the mislabelling fall-through: the pre-fix TriggerBanner was an
  // if/else chain ending in an unconditional `alternative_proposal` return, so any kind the
  // chain didn't explicitly recognize (here: the v37 `environment`/`blocked_external`
  // escalation categories) rendered as "Alternative design proposed" — a confidently wrong
  // banner. These two kinds must render their own distinct banners, never the
  // alternative_proposal one.
  it("renders a dedicated environment banner, not the alternative_proposal fall-through", () => {
    render(<TriggerBanner trigger={{ kind: "environment" }} />);
    expect(screen.queryByText("Alternative design proposed")).not.toBeInTheDocument();
    expect(screen.getByText("Environment issue")).toBeInTheDocument();
  });

  it("renders a dedicated blocked_external banner, not the alternative_proposal fall-through", () => {
    render(<TriggerBanner trigger={{ kind: "blocked_external" }} />);
    expect(screen.queryByText("Alternative design proposed")).not.toBeInTheDocument();
    expect(screen.getByText("Blocked on an external dependency")).toBeInTheDocument();
  });
});
