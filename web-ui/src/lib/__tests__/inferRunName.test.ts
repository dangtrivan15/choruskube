import { describe, it, expect } from "vitest";
import { inferRunName } from "../inferRunName";
import type { InputSchemaField } from "../types";

function field(overrides: Partial<InputSchemaField> = {}): InputSchemaField {
  return { name: "field1", label: "Field 1", type: "text", required: false, ...overrides };
}

describe("inferRunName", () => {
  it("returns the first non-URL text input value", () => {
    const schema = [
      field({ name: "feature_request", label: "Feature Request" }),
    ];
    const result = inferRunName(schema, { feature_request: "Add dark mode" });
    expect(result).toBe("Add dark mode");
  });

  it("skips URL values and picks the next text field", () => {
    const schema = [
      field({ name: "repo_url", label: "Repo URL" }),
      field({ name: "description", label: "Description" }),
    ];
    const result = inferRunName(schema, {
      repo_url: "https://github.com/example/repo",
      description: "Fix login bug",
    });
    expect(result).toBe("Fix login bug");
  });

  it("skips http URLs (case-insensitive)", () => {
    const schema = [
      field({ name: "url", label: "URL" }),
    ];
    const result = inferRunName(schema, { url: "HTTP://EXAMPLE.COM" });
    expect(result).toBe("");
  });

  it("skips non-text fields", () => {
    const schema = [
      field({ name: "count", label: "Count", type: "number" }),
      field({ name: "title", label: "Title", type: "text" }),
    ];
    const result = inferRunName(schema, { count: "42", title: "My task" });
    expect(result).toBe("My task");
  });

  it("returns empty string when all values are empty", () => {
    const schema = [
      field({ name: "a", label: "A" }),
      field({ name: "b", label: "B" }),
    ];
    const result = inferRunName(schema, { a: "", b: "  " });
    expect(result).toBe("");
  });

  it("returns empty string when schema is empty", () => {
    const result = inferRunName([], { something: "value" });
    expect(result).toBe("");
  });

  it("returns empty string when inputs are empty", () => {
    const schema = [field({ name: "x", label: "X" })];
    const result = inferRunName(schema, {});
    expect(result).toBe("");
  });

  it("truncates values longer than 30 chars and appends an ellipsis marker", () => {
    const longValue = "a".repeat(60);
    const schema = [field({ name: "title", label: "Title" })];
    const result = inferRunName(schema, { title: longValue });
    expect(result).toBe("a".repeat(29) + "…");
    expect(result.length).toBe(30);
  });

  it("trims whitespace from values", () => {
    const schema = [field({ name: "title", label: "Title" })];
    const result = inferRunName(schema, { title: "  hello world  " });
    expect(result).toBe("hello world");
  });

  it("skips fields with missing input values", () => {
    const schema = [
      field({ name: "a", label: "A" }),
      field({ name: "b", label: "B" }),
    ];
    const result = inferRunName(schema, { b: "second" });
    expect(result).toBe("second");
  });

  it("does not skip non-URL text values that contain url-like substrings", () => {
    const schema = [field({ name: "desc", label: "Desc" })];
    const result = inferRunName(schema, { desc: "Fix the https issue" });
    expect(result).toBe("Fix the https issue");
  });

  it("returns empty string when all text values are URLs", () => {
    const schema = [
      field({ name: "a", label: "A" }),
      field({ name: "b", label: "B" }),
    ];
    const result = inferRunName(schema, {
      a: "https://example.com",
      b: "http://localhost:3000",
    });
    expect(result).toBe("");
  });

  it("accepts textarea fields and uses the first line as run name", () => {
    const schema = [
      field({ name: "feature", label: "Feature", type: "textarea" }),
    ];
    const result = inferRunName(schema, {
      feature: "Add dark mode\nThis should support both light and dark themes\nAlso handle system preference",
    });
    expect(result).toBe("Add dark mode");
  });

  it("handles textarea with only one line", () => {
    const schema = [
      field({ name: "feature", label: "Feature", type: "textarea" }),
    ];
    const result = inferRunName(schema, { feature: "Simple one-liner" });
    expect(result).toBe("Simple one-liner");
  });

  it("skips textarea fields with empty first line and picks next field", () => {
    const schema = [
      field({ name: "feature", label: "Feature", type: "textarea" }),
      field({ name: "title", label: "Title", type: "text" }),
    ];
    const result = inferRunName(schema, {
      feature: "\nSecond line content",
      title: "Fallback title",
    });
    expect(result).toBe("Fallback title");
  });

  it("skips textarea fields whose first line is a URL", () => {
    const schema = [
      field({ name: "feature", label: "Feature", type: "textarea" }),
      field({ name: "title", label: "Title", type: "text" }),
    ];
    const result = inferRunName(schema, {
      feature: "https://github.com/example/repo\nSome description",
      title: "My feature",
    });
    expect(result).toBe("My feature");
  });

  it("trims whitespace from textarea first line", () => {
    const schema = [
      field({ name: "feature", label: "Feature", type: "textarea" }),
    ];
    const result = inferRunName(schema, {
      feature: "  Add dark mode  \nMore details here",
    });
    expect(result).toBe("Add dark mode");
  });
});
