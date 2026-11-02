import type { BrowserContext } from "@playwright/test";

/**
 * Makes every toast in `context` click-through. Other workers' toasts stack bottom-right and, left
 * hittable, intercept clicks, take hover from what lies under them and stop dismissing (e2e/PARALLELISM.md).
 * The `context` fixture already calls this; a context a spec creates itself, such as with
 * `browser.newContext()`, does not go through the fixture and must call it.
 */
export async function installToastClickThrough(context: BrowserContext): Promise<void> {
  await context.addInitScript(() => {
    // An adopted sheet, not a <style>: `document.head` is still null when init scripts run.
    const sheet = new CSSStyleSheet();
    sheet.replaceSync("[data-sonner-toaster], [data-sonner-toaster] * { pointer-events: none !important; }");
    document.adoptedStyleSheets = [...document.adoptedStyleSheets, sheet];
  });
}
