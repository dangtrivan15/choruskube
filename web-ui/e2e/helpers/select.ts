import type { Locator } from "@playwright/test";

/**
 * Selects an option by focusing it and pressing Enter, never a click: in a long list the popup's
 * scroll arrows overlay the edge options and take the pointer. The pointer is parked off the popup
 * first, or a row scrolling under it takes the highlight and Enter commits that row instead.
 */
export async function clickOption(option: Locator): Promise<void> {
  await option.page().mouse.move(0, 0);
  await option.press("Enter");
}
