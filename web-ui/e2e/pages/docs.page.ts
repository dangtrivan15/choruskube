import { type Page, type Locator, expect } from "@playwright/test";

/**
 * Page object for the Documentation section (/docs, /docs/:slug).
 */
export class DocsPage {
  readonly page: Page;
  readonly heading: Locator;
  readonly docListItems: Locator;
  readonly pageTitle: Locator;
  readonly pageContent: Locator;
  // data-testid locator — follows constructor-level convention of using getByTestId().
  readonly mermaidDiagrams: Locator;
  // Inline markdown links have no data-testid; page.locator() targets specific href values.
  readonly internalLink: Locator;
  readonly externalLink: Locator;

  constructor(page: Page) {
    this.page = page;
    this.heading         = page.getByTestId("docs-heading");
    this.docListItems    = page.getByTestId("docs-list-item");
    this.pageTitle       = page.getByTestId("docs-page-title");
    this.pageContent     = page.getByTestId("docs-page-content");
    this.mermaidDiagrams = page.getByTestId("mermaid-diagram");
    this.internalLink    = page.locator('a[href="/docs/features"]');
    this.externalLink    = page.locator('a[href^="https://github.com"]');
  }

  async goto() {
    await this.page.goto("/docs");
    await expect(this.heading).toBeVisible();
  }

  async selectDoc(title: string) {
    const item = this.docListItems.filter({ hasText: title });
    await item.click();
    await expect(this.pageTitle).toContainText(title);
  }

  async getPageTitle(): Promise<string> {
    return this.pageTitle.textContent() ?? "";
  }

}
