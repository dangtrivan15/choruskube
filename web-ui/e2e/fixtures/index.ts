/**
 * Custom Playwright fixtures for ChorusKube E2E tests.
 *
 * Extends the base test with:
 *   - `api`: a TestApiClient instance for backend orchestration
 *   - Page Objects for each major UI area
 */
import { test as base } from "@playwright/test";
import { TestApiClient } from "../helpers/api-client";
import { NavigationPage } from "../pages/navigation.page";
import { RunListPage } from "../pages/run-list.page";
import { RunMonitorPage } from "../pages/run-monitor.page";
import { RoadmapPage } from "../pages/roadmap.page";
import { RoadmapBoardPage } from "../pages/roadmap-board.page";
import { TaskBoardPage } from "../pages/task-board.page";
import { StoryBoardPage } from "../pages/story-board.page";
import { RoadmapGraphPage } from "../pages/roadmap-graph.page";
import { ApprovalsPage } from "../pages/approvals.page";
import { DocsPage } from "../pages/docs.page";

export interface TestFixtures {
  api: TestApiClient;
  navigationPage: NavigationPage;
  runListPage: RunListPage;
  runMonitorPage: RunMonitorPage;
  roadmapPage: RoadmapPage;
  roadmapBoardPage: RoadmapBoardPage;
  taskBoardPage: TaskBoardPage;
  storyBoardPage: StoryBoardPage;
  roadmapGraphPage: RoadmapGraphPage;
  approvalsPage: ApprovalsPage;
  docsPage: DocsPage;
}

export const test = base.extend<TestFixtures>({
  api: async ({}, use) => {
    const client = new TestApiClient();
    await use(client);
  },

  navigationPage: async ({ page }, use) => {
    await use(new NavigationPage(page));
  },

  runListPage: async ({ page }, use) => {
    await use(new RunListPage(page));
  },

  runMonitorPage: async ({ page }, use) => {
    await use(new RunMonitorPage(page));
  },

  roadmapPage: async ({ page }, use) => {
    await use(new RoadmapPage(page));
  },

  roadmapBoardPage: async ({ page }, use) => {
    await use(new RoadmapBoardPage(page));
  },

  taskBoardPage: async ({ page }, use) => {
    await use(new TaskBoardPage(page));
  },

  storyBoardPage: async ({ page }, use) => {
    await use(new StoryBoardPage(page));
  },

  roadmapGraphPage: async ({ page }, use) => {
    await use(new RoadmapGraphPage(page));
  },

  approvalsPage: async ({ page }, use) => {
    await use(new ApprovalsPage(page));
  },

  docsPage: async ({ page }, use) => {
    await use(new DocsPage(page));
  },
});

export { expect } from "@playwright/test";
