import { type Page, type Locator, expect } from "@playwright/test";

/**
 * Page object for an Epic's detail page (/roadmap/epics/:epicId), covering its
 * Dependencies section (EpicDependenciesSection): the "Blocked by" and "Blocks"
 * lists and the per-side Epic picker.
 */
export class EpicDetailPage {
  readonly page: Page;

  readonly title: Locator;
  readonly dependenciesSection: Locator;
  readonly blockedByList: Locator;
  readonly blocksList: Locator;
  readonly blockedBySelect: Locator;
  readonly blockedByAdd: Locator;
  readonly blocksSelect: Locator;
  readonly blocksAdd: Locator;

  constructor(page: Page) {
    this.page = page;

    this.title = page.getByTestId("epic-detail-title");
    this.dependenciesSection = page.getByTestId("epic-dependencies-section");
    this.blockedByList = page.getByTestId("epic-blocked-by-list");
    this.blocksList = page.getByTestId("epic-blocks-list");
    this.blockedBySelect = page.getByTestId("epic-blocked-by-select");
    this.blockedByAdd = page.getByTestId("epic-blocked-by-add");
    this.blocksSelect = page.getByTestId("epic-blocks-select");
    this.blocksAdd = page.getByTestId("epic-blocks-add");
  }

  /** Opens the page and waits until the dependency lists (not their loading skeleton) render. */
  async goto(epicId: string) {
    await this.page.goto(`/roadmap/epics/${epicId}`);
    await expect(this.title).toBeVisible({ timeout: 15_000 });
    await expect(this.blockedByList).toBeAttached({ timeout: 15_000 });
  }

  /** The row in `list` whose other item carries `title`. */
  row(list: Locator, title: string): Locator {
    return list.getByTestId("epic-dependency-row").filter({ hasText: title });
  }

  /**
   * Adds `epicTitle` as an Epic that blocks this one. Options are labelled
   * "<title> · <project>", and the accessible-name match is a substring, so
   * the bare title resolves.
   */
  async addBlockedBy(epicTitle: string) {
    await this.pickEpic(this.blockedBySelect, epicTitle);
    await this.blockedByAdd.click();
  }

  /** Adds `epicTitle` as an Epic this one blocks — see {@link addBlockedBy}. */
  async addBlocks(epicTitle: string) {
    await this.pickEpic(this.blocksSelect, epicTitle);
    await this.blocksAdd.click();
  }

  async remove(list: Locator, title: string) {
    await this.row(list, title).getByTestId("epic-dependency-remove").click();
  }

  private async pickEpic(select: Locator, epicTitle: string) {
    await select.click();
    await this.page.getByRole("option", { name: epicTitle }).click();
  }
}
