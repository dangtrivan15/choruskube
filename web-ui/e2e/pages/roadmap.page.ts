import { type Page, type Locator, expect } from "@playwright/test";

/**
 * Page object for the Roadmap drill-down: Epic list (/roadmap) -> Epic detail
 * (Story list) -> Story detail (Task list) -> Task detail (/tasks/:id).
 */
export class RoadmapPage {
  readonly page: Page;

  // Epic list (/roadmap)
  readonly heading: Locator;
  readonly newEpicButton: Locator;
  readonly epicList: Locator;
  readonly epicItems: Locator;
  readonly readyToStartToggle: Locator;
  readonly priorityFilter: Locator;

  // Epic detail
  readonly epicDetailTitle: Locator;
  readonly epicDetailDescription: Locator;
  readonly epicDetailStatus: Locator;
  readonly epicDetailPriorityBadge: Locator;
  readonly epicDetailPrioritySelect: Locator;
  readonly storyDetailPriorityBadge: Locator;
  readonly storyDetailPrioritySelect: Locator;
  readonly epicDetailTargetDate: Locator;
  readonly epicDetailTargetDateInput: Locator;
  readonly storyDetailTargetDate: Locator;
  readonly storyDetailTargetDateInput: Locator;
  readonly epicEditButton: Locator;
  readonly epicDeleteButton: Locator;
  readonly newStoryButton: Locator;
  readonly storyList: Locator;
  readonly storyItems: Locator;

  // Story detail
  readonly storyDetailTitle: Locator;
  readonly storyDeleteButton: Locator;
  readonly newTaskButton: Locator;
  readonly taskList: Locator;
  readonly taskItems: Locator;

  // Task detail
  readonly taskDetailTitle: Locator;
  readonly taskDetailStatus: Locator;
  readonly taskStartButton: Locator;
  readonly taskRestartButton: Locator;
  readonly taskCompleteButton: Locator;
  readonly taskDeleteButton: Locator;
  readonly taskRunHistoryList: Locator;
  readonly backToStoryLink: Locator;

  // Create Epic dialog
  readonly createDialogTitle: Locator;
  readonly createTitleInput: Locator;
  readonly createDescriptionInput: Locator;
  readonly createMotivationInput: Locator;
  readonly createSoftwareProjectSelect: Locator;
  readonly createSubmitButton: Locator;

  // Edit Epic dialog
  readonly editTitleInput: Locator;
  readonly editDescriptionInput: Locator;
  readonly editSaveButton: Locator;

  // Create Story dialog
  readonly createStoryTitleInput: Locator;
  readonly createStoryDescriptionInput: Locator;
  readonly createStorySubmitButton: Locator;

  // Create Task dialog
  readonly createTaskTitleInput: Locator;
  readonly createTaskDescriptionInput: Locator;
  readonly createTaskSubmitButton: Locator;

  // Confirmation dialogs
  readonly deleteEpicConfirmButton: Locator;
  readonly deleteStoryConfirmButton: Locator;
  readonly deleteTaskConfirmButton: Locator;
  readonly startTaskConfirmButton: Locator;

  constructor(page: Page) {
    this.page = page;

    this.heading = page.getByTestId("roadmap-heading");
    this.newEpicButton = page.getByTestId("new-epic-button");
    this.epicList = page.getByTestId("epic-list");
    this.epicItems = page.getByTestId("epic-item");
    // Shared "Ready to start" toggle (RoadmapReadyToggle) — rendered on each of the Epic
    // list, Epic detail, and Story detail views this page object covers; only one is ever
    // mounted at a time since they're separate routes.
    this.readyToStartToggle = page.getByTestId("ready-to-start-toggle");
    // All/High/Medium/Low segmented filter (PriorityFilter) — rendered on the
    // Epic list and Epic detail (Story list) toolbars.
    this.priorityFilter = page.getByTestId("priority-filter");

    this.epicDetailTitle = page.getByTestId("epic-detail-title");
    this.epicDetailDescription = page.getByTestId("epic-detail-description");
    this.epicDetailStatus = page.getByTestId("epic-detail-status");
    this.epicDetailPriorityBadge = page.getByTestId("epic-detail-priority-badge");
    this.epicDetailPrioritySelect = page.getByTestId("epic-detail-priority-select");
    this.storyDetailPriorityBadge = page.getByTestId("story-detail-priority-badge");
    this.storyDetailPrioritySelect = page.getByTestId("story-detail-priority-select");
    this.epicDetailTargetDate = page.getByTestId("epic-detail-target-date");
    this.epicDetailTargetDateInput = page.getByTestId("epic-detail-target-date-input");
    this.storyDetailTargetDate = page.getByTestId("story-detail-target-date");
    this.storyDetailTargetDateInput = page.getByTestId("story-detail-target-date-input");
    this.epicEditButton = page.getByTestId("epic-edit-button");
    this.epicDeleteButton = page.getByTestId("epic-delete-button");
    this.newStoryButton = page.getByTestId("new-story-button");
    this.storyList = page.getByTestId("story-list");
    this.storyItems = page.getByTestId("story-item");

    this.storyDetailTitle = page.getByTestId("story-detail-title");
    this.storyDeleteButton = page.getByTestId("story-delete-button");
    this.newTaskButton = page.getByTestId("new-task-button");
    this.taskList = page.getByTestId("task-list");
    this.taskItems = page.getByTestId("task-item");

    this.taskDetailTitle = page.getByTestId("task-detail-title");
    this.taskDetailStatus = page.getByTestId("task-detail-status");
    this.taskStartButton = page.getByTestId("task-start-button");
    this.taskRestartButton = page.getByTestId("task-restart-button");
    this.taskCompleteButton = page.getByTestId("task-complete-button");
    this.taskDeleteButton = page.getByTestId("task-delete-button");
    this.taskRunHistoryList = page.getByTestId("task-run-history-list");
    this.backToStoryLink = page.getByRole("link", { name: "Back to Story" });

    // Create Epic dialog
    //
    // Scoped to the dialog heading (role=heading), not getByText("New Epic"):
    // the roadmap empty state ("No epics yet. Click "New Epic" to create
    // one.") and the "New Epic" button both also contain that text, so a
    // plain text locator is a Playwright strict-mode violation once the
    // dialog is open (three matches instead of one).
    this.createDialogTitle = page.getByRole("heading", { name: "New Epic" });
    this.createTitleInput = page.getByTestId("create-epic-title");
    this.createDescriptionInput = page.getByTestId("create-epic-description");
    this.createMotivationInput = page.getByTestId("create-epic-motivation");
    this.createSoftwareProjectSelect = page.getByTestId(
      "create-epic-software-project-select",
    );
    this.createSubmitButton = page.getByTestId("create-epic-submit");

    // Edit Epic dialog
    this.editTitleInput = page.getByTestId("edit-epic-title");
    this.editDescriptionInput = page.getByTestId("edit-epic-description");
    this.editSaveButton = page.getByTestId("edit-epic-save");

    // Create Story dialog
    this.createStoryTitleInput = page.getByTestId("create-story-title");
    this.createStoryDescriptionInput = page.getByTestId("create-story-description");
    this.createStorySubmitButton = page.getByTestId("create-story-submit");

    // Create Task dialog
    this.createTaskTitleInput = page.getByTestId("create-task-title");
    this.createTaskDescriptionInput = page.getByTestId("create-task-description");
    this.createTaskSubmitButton = page.getByTestId("create-task-submit");

    // Confirmation dialogs
    this.deleteEpicConfirmButton = page.getByTestId("delete-epic-confirm");
    this.deleteStoryConfirmButton = page.getByTestId("delete-story-confirm");
    this.deleteTaskConfirmButton = page.getByTestId("delete-task-confirm");
    this.startTaskConfirmButton = page.getByTestId("start-task-confirm");
  }

  async goto() {
    await this.page.goto("/roadmap");
    await expect(this.heading).toBeVisible();
  }

  async openEpic(title: string) {
    const item = this.epicItems.filter({ hasText: title });
    await item.click();
    await expect(this.epicDetailTitle).toContainText(title);
  }

  async openStory(title: string) {
    const item = this.storyItems.filter({ hasText: title });
    await item.click();
    await expect(this.storyDetailTitle).toContainText(title);
  }

  async openTask(title: string) {
    const item = this.taskItems.filter({ hasText: title });
    await item.click();
    await expect(this.taskDetailTitle).toContainText(title);
  }

  /** The priority badge on the Epic row (Roadmap list) titled `title`. */
  epicItemPriorityBadge(title: string): Locator {
    return this.epicItems.filter({ hasText: title }).getByTestId("epic-priority-badge");
  }

  /** The priority badge on the Story row (Epic detail page) titled `title`. */
  storyItemPriorityBadge(title: string): Locator {
    return this.storyItems.filter({ hasText: title }).getByTestId("story-item-priority-badge");
  }

  /** Filter the Epic/Story list by priority via the segmented control ("all" clears it). */
  async filterByPriority(level: "all" | "high" | "medium" | "low") {
    await this.priorityFilter.getByTestId(`priority-filter-${level}`).click();
  }

  /**
   * Pick a sort option from a `SortDropdown` by its visible label (e.g.
   * "Priority (High→Low)"). The dropdown has no test id, so it's reached via the
   * combobox role — pass the specific trigger locator when a view mounts more
   * than one (Epic detail mounts the Story sort dropdown).
   */
  async selectSort(label: string | RegExp, trigger?: Locator) {
    await (trigger ?? this.page.getByRole("combobox")).click();
    await this.page.getByRole("option", { name: label }).click();
  }

  /** Re-prioritize via the inline detail-page PrioritySelect (Base UI Select). */
  async setPriorityViaSelect(trigger: Locator, level: "high" | "medium" | "low") {
    await trigger.click();
    await this.page.getByTestId(`priority-option-${level}`).click();
  }

  /** Set the target date via the inline detail-page native date input (`"YYYY-MM-DD"`). */
  async fillTargetDate(input: Locator, date: string) {
    await input.fill(date);
    await input.blur();
  }

  /** Clear the target date via the inline detail-page native date input. */
  async clearTargetDate(input: Locator) {
    await input.fill("");
    await input.blur();
  }

  /** The readiness "Blocked" badge on the Story row (Epic detail page) titled `title`, if present. */
  storyItemReadinessBadge(title: string): Locator {
    return this.storyItems.filter({ hasText: title }).getByTestId("story-item-readiness-badge");
  }

  /** The readiness "Blocked" badge on the Task row (Story detail page) titled `title`, if present. */
  taskItemReadinessBadge(title: string): Locator {
    return this.taskItems.filter({ hasText: title }).getByTestId("task-item-readiness-badge");
  }

  async openCreateDialog() {
    await this.newEpicButton.click();
    await expect(this.createDialogTitle).toBeVisible();
  }

  async createEpic(
    title: string,
    description: string,
    projectName: string,
    motivation?: string,
  ) {
    await this.openCreateDialog();

    await this.createTitleInput.fill(title);
    await this.createDescriptionInput.fill(description);

    if (motivation) {
      await this.createMotivationInput.fill(motivation);
    }

    await this.selectSoftwareProjectInCreateDialog(projectName);

    await this.createSubmitButton.click();
    await expect(this.createDialogTitle).not.toBeVisible();
  }

  /**
   * Selects a SoftwareProject (a single git_repo or a repo_group) in the
   * Create Epic dialog's grouped dropdown by visible name.
   *
   * Uses an exact match: option labels are `SoftwareProject.name` verbatim
   * (see SoftwareProjectSelect.tsx), and a substring/regex match is
   * ambiguous whenever one repo's full name is a prefix of another's (e.g.
   * "acme/widget" vs. "acme/widget-api"), which throws a Playwright
   * strict-mode violation.
   */
  async selectSoftwareProjectInCreateDialog(projectName: string) {
    await this.createSoftwareProjectSelect.click();
    await this.page
      .getByRole("option", { name: projectName, exact: true })
      .click();
  }

  async createStory(epicTitle: string, title: string, description: string) {
    await this.goto();
    await this.openEpic(epicTitle);
    await this.newStoryButton.click();
    await this.createStoryTitleInput.fill(title);
    await this.createStoryDescriptionInput.fill(description);
    await this.createStorySubmitButton.click();
  }

  async createTask(
    epicTitle: string,
    storyTitle: string,
    title: string,
    description: string,
  ) {
    await this.goto();
    await this.openEpic(epicTitle);
    await this.openStory(storyTitle);
    await this.newTaskButton.click();
    await this.createTaskTitleInput.fill(title);
    await this.createTaskDescriptionInput.fill(description);
    await this.createTaskSubmitButton.click();
  }

  async deleteEpic(title: string) {
    await this.openEpic(title);
    await this.epicDeleteButton.click();
    await this.deleteEpicConfirmButton.click();
  }
}
