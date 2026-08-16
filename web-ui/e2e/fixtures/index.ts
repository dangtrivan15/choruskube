/**
 * Custom Playwright fixtures for ChorusKube E2E tests.
 *
 * Extends the base test with:
 *   - `api`: a TestApiClient instance for backend orchestration
 *   - Page Objects for each major UI area
 */
import { test as base } from "@playwright/test";
import { TestApiClient, type GitRepo, type RepoGroupSummary } from "../helpers/api-client";
import { NavigationPage } from "../pages/navigation.page";
import { RunListPage } from "../pages/run-list.page";
import { RunMonitorPage } from "../pages/run-monitor.page";
import { RoadmapPage } from "../pages/roadmap.page";
import { MilestonesPage } from "../pages/milestones.page";
import { RoadmapBoardPage } from "../pages/roadmap-board.page";
import { TaskBoardPage } from "../pages/task-board.page";
import { StoryBoardPage } from "../pages/story-board.page";
import { RoadmapGraphPage } from "../pages/roadmap-graph.page";
import { ApprovalsPage } from "../pages/approvals.page";
import { DocsPage } from "../pages/docs.page";
import { AutopilotPage } from "../pages/autopilot.page";

export interface TestFixtures {
  api: TestApiClient;
  navigationPage: NavigationPage;
  runListPage: RunListPage;
  runMonitorPage: RunMonitorPage;
  roadmapPage: RoadmapPage;
  milestonesPage: MilestonesPage;
  roadmapBoardPage: RoadmapBoardPage;
  taskBoardPage: TaskBoardPage;
  storyBoardPage: StoryBoardPage;
  roadmapGraphPage: RoadmapGraphPage;
  approvalsPage: ApprovalsPage;
  docsPage: DocsPage;
  autopilotPage: AutopilotPage;
}

/** A GitRepo + RepoGroup dedicated to the current Playwright worker. */
export interface WorkerRepoFixture {
  gitRepo: GitRepo;
  repoGroup: RepoGroupSummary;
}

/**
 * URL prefix every `workerRepo` GitRepo is minted under.
 *
 * `GET /api/v1/git-repos` sorts by `url` ascending, and "example.invalid" sorts
 * ahead of the seeded "github.com" rows — so worker repos occupy the head of
 * that list as soon as any worker materializes one. Specs that need the *seeded*
 * repos specifically must exclude this prefix rather than index into the list.
 */
export const WORKER_REPO_URL_PREFIX = "https://example.invalid/e2e-worker/";

/**
 * Narrows a `listGitRepos()` page to the rows `E2eTestDataSeeder` seeded,
 * dropping every worker's `workerRepo`. Use this in specs that need a *stable*
 * repo set — ones that index into the list, slice it, or drive a checkbox per
 * entry — since how many worker repos exist depends on which tests have run.
 */
export function seededRepos<T extends { url: string }>(repos: T[]): T[] {
  return repos.filter((r) => !r.url.startsWith(WORKER_REPO_URL_PREFIX));
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
  //
  // Deliberately NOT uniqueName(): that helper mints a fresh Date.now()+counter
  // suffix on every call, so two calls for the *same* worker slot would never
  // resolve to the same name/URL and the fetch-or-create lookup below could never
  // hit. The name only needs to be stable per slot and distinct across slots, so
  // it's built directly from the worker index — the same thing uniqueName() uses
  // for its own collision-safety, minus the per-call suffix.
  workerRepo: [
    async ({}, use, workerInfo) => {
      const api = new TestApiClient();
      const name = `e2e-worker-repo-w${workerInfo.parallelIndex}`;
      const url = `${WORKER_REPO_URL_PREFIX}${name}.git`;

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

  milestonesPage: async ({ page }, use) => {
    await use(new MilestonesPage(page));
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

  autopilotPage: async ({ page }, use) => {
    await use(new AutopilotPage(page));
  },
});

export { expect } from "@playwright/test";
