import { describe, it, expect } from "vitest";
import { firstLinePreview } from "../textPreview";

describe("firstLinePreview", () => {
  it("returns an empty string for null", () => {
    expect(firstLinePreview(null)).toBe("");
  });

  it("returns an empty string for a blank string", () => {
    expect(firstLinePreview("   \n\n  ")).toBe("");
  });

  it("skips leading blank lines", () => {
    expect(firstLinePreview("\n\n  \nAdd a logout button")).toBe("Add a logout button");
  });

  it("strips a heading marker", () => {
    expect(firstLinePreview("## Add dark mode")).toBe("Add dark mode");
  });

  it("strips a blockquote marker", () => {
    expect(firstLinePreview("> Add dark mode")).toBe("Add dark mode");
  });

  it("strips a bullet list marker", () => {
    expect(firstLinePreview("- Add dark mode")).toBe("Add dark mode");
    expect(firstLinePreview("* Add dark mode")).toBe("Add dark mode");
  });

  it("strips an ordered list marker", () => {
    expect(firstLinePreview("1. Add dark mode")).toBe("Add dark mode");
  });

  it("strips inline emphasis and code markers", () => {
    expect(firstLinePreview("Add **dark** mode with `theme.css`")).toBe(
      "Add dark mode with theme.css",
    );
    expect(firstLinePreview("Add _dark_ mode")).toBe("Add dark mode");
  });

  it("returns only the first line of a multi-line string", () => {
    expect(firstLinePreview("Add dark mode\n\nMore detail below.")).toBe("Add dark mode");
  });
});
