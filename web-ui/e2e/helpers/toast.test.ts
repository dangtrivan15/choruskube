import { describe, it, expect, vi, afterEach } from "vitest";
import type { BrowserContext } from "@playwright/test";
import { installToastClickThrough } from "./toast";

/** A context whose only capability is recording the init scripts it was handed. */
function recordingContext(): { context: BrowserContext; scripts: Array<() => void> } {
  const scripts: Array<() => void> = [];
  const context = {
    addInitScript: async (script: () => void) => {
      scripts.push(script);
    },
  } as unknown as BrowserContext;
  return { context, scripts };
}

describe("installToastClickThrough", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("registers one init script on the context", async () => {
    const { context, scripts } = recordingContext();

    await installToastClickThrough(context);

    expect(scripts).toHaveLength(1);
  });

  // Playwright serialises the script and runs it in the page, so it can close over nothing from
  // this module. Running it here against stand-ins for the two browser globals it touches proves
  // it is self-contained, and shows what it adopts.
  it("adopts a pointer-events: none sheet for the Sonner stack, keeping the sheets already adopted", async () => {
    const { context, scripts } = recordingContext();
    await installToastClickThrough(context);

    const css: string[] = [];
    class FakeSheet {
      replaceSync(text: string): void {
        css.push(text);
      }
    }
    const existing = new FakeSheet();
    const doc = { adoptedStyleSheets: [existing] as FakeSheet[] };
    vi.stubGlobal("CSSStyleSheet", FakeSheet);
    vi.stubGlobal("document", doc);

    scripts[0]();

    expect(doc.adoptedStyleSheets).toHaveLength(2);
    expect(doc.adoptedStyleSheets[0]).toBe(existing);
    expect(css).toEqual([
      "[data-sonner-toaster], [data-sonner-toaster] * { pointer-events: none !important; }",
    ]);
  });
});
