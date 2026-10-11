import type { Locator } from "@playwright/test";

/**
 * Clicks an option in a select popup that may scroll. A default scroll-into-view parks the
 * option at the popup's edge, under its scroll arrow, which then takes the click — so a list
 * that grows past the popup's height fails the click. Centring the option first avoids it.
 */
export async function clickOption(option: Locator): Promise<void> {
  await option.evaluate((el) => el.scrollIntoView({ block: "center" }));
  await option.click();
}
