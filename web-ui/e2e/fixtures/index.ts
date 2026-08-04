/**
 * Custom Playwright fixtures for ChorusKube E2E tests.
 *
 * Extends the base test with:
 *   - `api`: a TestApiClient instance for backend orchestration
 *   - Page Objects for each major UI area
 */
import { test as base } from "@playwright/test";
import { TestApiClient, uniqueName, type GitRepo, type RepoGroupSummary } from "../helpers/api-client";
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

/** A GitRepo + RepoGroup dedicated to the current Playwright worker. */
export interface WorkerRepoFixture {
  gitRepo: GitRepo;
  repoGroup: RepoGroupSummary;
}

export interface WorkerFixtures {
  workerRepo: WorkerRepoFixture;
}

export const test = base.extend<TestFixtures, WorkerFixtures>({
  api: async ({}, use) => {
    const client = new TestApiClient();
    await use(client);
  },

  // Worker-scoped: created once per worker (Playwright caches worker-scoped
  // fixtures for the life of the worker process) and shared by every test
  // that runs in it, so specs needing "a repo of their own" don't each mint a
  // fresh GitRepo/RepoGroup pair. Fetch-or-create so a fixture re-used across
  // an aborted-then-retried worker slot doesn't fail on a name/URL conflict.
  workerRepo: [
    async ({}, use, workerInfo) => {
      const api = new TestApiClient();
      const name = uniqueName("e2e-worker-repo", workerInfo.parallelIndex);
      const url = `https://example.invalid/e2e-worker/${name}.git`;

      const existingRepos = await api.listGitRepos();
      let gitRepo = existingRepos.content.find((r) => r.url === url);
      if (!gitRepo) {
        gitRepo = await api.createGitRepo({ url });
      }

      const existingGroups = await api.listRepoGroups();
      let repoGroup = existingGroups.find((g) => g.name === name);
      if (!repoGroup) {
        repoGroup = await api.createRepoGroup({ name, memberRepoIds: [gitRepo.id] });
      }

      await use({ gitRepo, repoGroup });
    },
    { scope: "worker" },
  ],

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
