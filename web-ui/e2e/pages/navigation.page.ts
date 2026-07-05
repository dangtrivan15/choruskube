import { type Page, type Locator, expect } from "@playwright/test";

/**
 * Page object for the global layout: sidebar navigation, theme toggle, etc.
 */
export class NavigationPage {
  readonly page: Page;

  // Sidebar navigation links
  readonly runsLink: Locator;
  readonly approvalsLink: Locator;
  readonly roadmapLink: Locator;
  readonly analyticsLink: Locator;
  readonly approvalsBadge: Locator;

  constructor(page: Page) {
    this.page = page;

    this.runsLink = page.getByTestId("nav-runs");
    this.approvalsLink = page.getByTestId("nav-approvals");
    this.roadmapLink = page.getByTestId("nav-roadmap");
    this.analyticsLink = page.getByTestId("nav-analytics");
    this.approvalsBadge = page.getByTestId("nav-approvals-badge");
  }

  async goto(path: string) {
    await this.page.goto(path);
  }

  async navigateToRuns() {
    await this.runsLink.click();
    await this.page.waitForURL("**/runs");
  }

  async navigateToApprovals() {
    await this.approvalsLink.click();
    await this.page.waitForURL("**/approvals");
  }

  async navigateToRoadmap() {
    await this.roadmapLink.click();
    await this.page.waitForURL("**/roadmap");
  }

  async navigateToAnalytics() {
    await this.analyticsLink.click();
    await this.page.waitForURL("**/analytics");
  }

  async expectActiveLink(name: string) {
    const link = this.page.getByTestId(`nav-${name}`);
    await expect(link).toHaveClass(/bg-sidebar-accent/);
  }
}
