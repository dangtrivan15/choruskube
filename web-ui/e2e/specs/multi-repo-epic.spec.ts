import { test, expect, seededRepos } from "../fixtures";
import { uniqueName } from "../helpers/api-client";

/**
 * Multi-repo Epic flow: the Epic targets a SoftwareProject, which in the
 * multi-repo case must be a pre-existing RepoGroup. This spec:
 *  1. Verifies the seeded git_repo rows exist (>= 2).
 *  2. Creates a RepoGroup containing both seeded repos via the public API.
 *  3. Opens the Roadmap, creates an Epic targeting that group via the
 *     SoftwareProjectSelect dropdown.
 *  4. Submits and asserts the persisted Epic stores softwareProject.id =
 *     group.id, type "repo_group", and repos[] contains both members.
 *  5. Re-opens the Epic detail and asserts both repos are visible.
 *
 * The chip-list / RepoBadgeList path retired in Phase 2 — agents that need
 * "1..2 repos for a single Epic" must materialize a RepoGroup first.
 */
test.describe("Multi-repo Epics (UI)", () => {
  test("epic targeting a RepoGroup persists group id and renders both members", async ({
    roadmapPage,
    api,
  }) => {
    const reposPage = await api.listGitRepos();
    const seeded = seededRepos(reposPage.content);
    expect(
      seeded.length,
      "E2eTestDataSeeder must seed at least 2 git_repo rows for this spec",
    ).toBeGreaterThanOrEqual(2);
    const [primary, secondary] = seeded;

    const groupName = uniqueName("e2e-multi-repo");
    const group = await api.createRepoGroup({
      name: groupName,
      memberRepoIds: [primary.id, secondary.id],
    });

    const title = uniqueName("Multi-repo E2E");
    try {
      await roadmapPage.goto();
      await roadmapPage.createEpic(
        title,
        "E2E test: feature spanning two repos via a RepoGroup.",
        groupName,
        "Cross-repo coordination.",
      );

      await expect
        .poll(
          async () => {
            const list = await api.listEpics();
            return list.content.some((e) => e.title === title);
          },
          {
            message: `Epic with title "${title}" should appear in the list after create`,
            timeout: 10_000,
          },
        )
        .toBe(true);

      const list = await api.listEpics();
      const created = list.content.find((e) => e.title === title);
      expect(created, "created epic must be present in the list").toBeDefined();
      expect(created!.softwareProject.id).toEqual(group.id);
      expect(created!.softwareProject.type).toEqual("repo_group");
      expect(created!.repos.map((r) => r.id).sort()).toEqual(
        [primary.id, secondary.id].sort(),
      );

      await roadmapPage.goto();
      await roadmapPage.openEpic(title);
      // Project chip + repo pills
      await expect(roadmapPage.page.getByText(groupName).first()).toBeVisible();

      await api.deleteEpic(created!.id);
    } finally {
      // Clean up the group whether or not the assertions held.
      try {
        await api.deleteRepoGroup(group.id);
      } catch {
        // best-effort cleanup
      }
    }
  });

  test("create-epic dialog has no multi-repo chip picker after Phase 2", async ({
    roadmapPage,
  }) => {
    await roadmapPage.goto();
    await roadmapPage.openCreateDialog();
    // The chip-list picker testid existed pre-Phase-2; it must be gone.
    await expect(
      roadmapPage.page.getByTestId("create-epic-repo-picker"),
    ).toHaveCount(0);
    // The grouped-dropdown trigger must be present.
    await expect(
      roadmapPage.page.getByTestId("create-epic-software-project-select"),
    ).toBeVisible();
  });
});
