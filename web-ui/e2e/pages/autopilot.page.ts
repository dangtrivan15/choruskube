import { type Page, type Locator } from "@playwright/test";

/**
 * Page object for the Autopilot control surface (/autopilot).
 *
 * `autopilot-next-up`/`autopilot-awaiting-you`/`autopilot-needs-attention` exist on the page too
 * but have no spec asserting on them yet, so they are left for one to add.
 */
export class AutopilotPage {
  readonly page: Page;

  readonly toggle: Locator;
  readonly maxParallelInput: Locator;
  readonly tickButton: Locator;
  readonly inFlight: Locator;
  readonly whyIdle: Locator;
  readonly disengagedBanner: Locator;
  /** Tasks left `in_progress` by a finished run — the Autopilot will not move these itself. */
  readonly heldTasks: Locator;

  constructor(page: Page) {
    this.page = page;

    this.toggle = page.getByTestId("autopilot-toggle");
    this.maxParallelInput = page.getByTestId("autopilot-max-parallel");
    this.tickButton = page.getByTestId("autopilot-tick");
    this.inFlight = page.getByTestId("autopilot-in-flight");
    this.whyIdle = page.getByTestId("autopilot-why-idle");
    this.disengagedBanner = page.getByTestId("autopilot-disengaged-banner");
    this.heldTasks = page.getByTestId("autopilot-held-tasks");
  }

  async goto() {
    await this.page.goto("/autopilot");
    await this.toggle.waitFor({ state: "visible" });
  }
}
