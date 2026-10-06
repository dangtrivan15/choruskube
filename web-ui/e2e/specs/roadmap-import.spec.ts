import type { Page } from "@playwright/test";
import { test, expect } from "../fixtures";
import { uniqueName } from "../helpers/api-client";

/** Single-repo SoftwareProjects are labelled by their repo's last two path segments. */
function projectLabel(url: string): string {
  return url.replace(/^https?:\/\/[^/]+\//, "").replace(/\.git$/, "");
}

async function openImportDialog(page: Page, projectName: string, document: unknown) {
  await page.getByTestId("import-roadmap-button").click();
  await expect(page.getByTestId("import-roadmap-dialog")).toBeVisible();
  await page.getByTestId("import-roadmap-project").click();
  await page.getByRole("option", { name: projectName, exact: true }).click();
  await page.getByTestId("import-roadmap-json").fill(JSON.stringify(document, null, 2));
}

test.describe("Roadmap JSON import", () => {
  test("validates, previews, and imports a document that depends on an existing Epic", async ({
    roadmapPage,
    api,
    workerRepo,
  }) => {
    const page = roadmapPage.page;
    const repo = workerRepo.gitRepo;
    const anchor = await api.createEpic({
      title: uniqueName("E2E import anchor"),
      description: "Already on the roadmap",
      softwareProjectId: repo.id,
    });
    const epicTitle = uniqueName("E2E imported epic");
    const document = {
      milestones: [{ key: "m", name: uniqueName("E2E import milestone") }],
      epics: [
        {
          key: "new",
          title: epicTitle,
          description: "Imported from JSON",
          priority: "High",
          milestone: "m",
          stories: [
            {
              title: "Imported story",
              description: "d",
              tasks: [{ title: "Imported task", description: "d" }],
            },
          ],
        },
        { existingId: anchor.id, key: "old" },
      ],
      dependencies: [{ blocking: "old", blocked: "new" }],
    };

    await roadmapPage.goto();
    await openImportDialog(page, projectLabel(repo.url), document);

    const submit = page.getByTestId("import-roadmap-submit");
    await expect(submit).toBeDisabled();
    await page.getByTestId("import-roadmap-validate").click();
    const preview = page.getByTestId("import-roadmap-preview");
    await expect(preview).toContainText("1 new epic, 1 story, 1 task, 1 milestone, 1 dependency");
    await expect(preview).toContainText("attaching to 1 existing item");
    await expect(preview).toContainText(epicTitle);

    await submit.click();
    await expect(page.getByTestId("import-roadmap-dialog")).toBeHidden();

    const imported = (await api.listEpicsForProject(repo.id)).find((e) => e.title === epicTitle);
    expect(imported).toBeDefined();
    const graph = await api.getGraph(imported!.id);
    expect(graph.stories.map((s) => s.title)).toEqual(["Imported story"]);
    expect(graph.tasks.map((t) => t.title)).toEqual(["Imported task"]);
    expect(graph.externalBlockers.map((b) => b.itemId)).toContain(anchor.id);
  });

  test("lists every problem in a rejected document and imports nothing", async ({
    roadmapPage,
    api,
    workerRepo,
  }) => {
    const page = roadmapPage.page;
    const repo = workerRepo.gitRepo;
    const epicTitle = uniqueName("E2E rejected import");

    await roadmapPage.goto();
    await openImportDialog(page, projectLabel(repo.url), {
      epics: [{ key: "a", title: epicTitle, description: "d", stories: [] }],
      dependencies: [{ blocking: "a", blocked: "missing" }],
    });
    await page.getByTestId("import-roadmap-validate").click();

    const errors = page.getByTestId("import-roadmap-errors");
    await expect(errors).toContainText("2 problems — nothing was imported");
    await expect(errors).toContainText("epics[0]: a new epic needs at least one story");
    await expect(errors).toContainText("dependencies[0]: unknown key 'missing'");
    await expect(page.getByTestId("import-roadmap-submit")).toBeDisabled();

    const epics = await api.listEpicsForProject(repo.id);
    expect(epics.map((e) => e.title)).not.toContain(epicTitle);
  });
});
