import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import ErrorAlert from "../ErrorAlert";

describe("ErrorAlert", () => {
  it("renders error message text", () => {
    render(<ErrorAlert message="Something went wrong." />);
    expect(screen.getByText("Something went wrong.")).toBeInTheDocument();
  });

  it("has role=alert for accessibility", () => {
    render(<ErrorAlert message="Error occurred" />);
    expect(screen.getByRole("alert")).toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveTextContent("Error occurred");
  });
});
