import { describe, it, expect } from "vitest";
import { dialogContentVariants } from "../dialog";

describe("dialogContentVariants", () => {
  it("includes base flex-col layout classes", () => {
    const classes = dialogContentVariants();
    expect(classes).toContain("flex");
    expect(classes).toContain("flex-col");
  });

  it("includes max-h-[85vh] and overflow-y-auto in base", () => {
    const classes = dialogContentVariants();
    expect(classes).toContain("max-h-[85vh]");
    expect(classes).toContain("overflow-y-auto");
  });

  it("defaults to sm width", () => {
    const classes = dialogContentVariants();
    expect(classes).toContain("sm:max-w-sm");
  });

  it("applies md size variant", () => {
    const classes = dialogContentVariants({ size: "md" });
    expect(classes).toContain("sm:max-w-md");
    expect(classes).not.toContain("sm:max-w-sm");
  });

  it("applies lg size variant", () => {
    const classes = dialogContentVariants({ size: "lg" });
    expect(classes).toContain("sm:max-w-lg");
  });

  it("applies 3xl size variant", () => {
    const classes = dialogContentVariants({ size: "3xl" });
    expect(classes).toContain("sm:max-w-3xl");
  });

  it("appends custom className", () => {
    const classes = dialogContentVariants({ size: "sm", className: "my-custom" });
    expect(classes).toContain("my-custom");
  });
});
