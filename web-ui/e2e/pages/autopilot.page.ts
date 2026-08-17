import { type Page, type Locator } from "@playwright/test";

/**
 * Page object for the Autopilot control surface (/autopilot).
 *
 * Locators cover only the six `data-testid`s the feature plan fixed in advance (spec Task 6/8
 * cross-check) — `autopilot-next-up`/`autopilot-awaiting-you`/`autopilot-needs-attention` exist
 * on the page too but aren't part of that contract, so they're left for a future spec to add.
 */
export class AutopilotPage {
  readonly page: Page;

  readonly toggle: Locator;
  readonly maxParallelInput: Locator;
  readonly tickButton: Locator;
  readonly inFlight: Locator;
  readonly whyIdle: Locator;
  readonly disengagedBanner: Locator;

  constructor(page: Page) {
    this.page = page;

    this.toggle = page.getByTestId("autopilot-toggle");
    this.maxParallelInput = page.getByTestId("autopilot-max-parallel");
    this.tickButton = page.getByTestId("autopilot-tick");
    this.inFlight = page.getByTestId("autopilot-in-flight");
    this.whyIdle = page.getByTestId("autopilot-why-idle");
    this.disengagedBanner = page.getByTestId("autopilot-disengaged-banner");
  }

  async goto() {
    await this.page.goto("/autopilot");
    await this.toggle.waitFor({ state: "visible" });
  }
}
