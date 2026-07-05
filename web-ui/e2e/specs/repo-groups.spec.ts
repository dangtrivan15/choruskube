import { test, expect } from "../fixtures";

/**
 * RepoGroup-launched run end-to-end flow.
 *
 *   1. Open the Software Projects page → Repo Groups tab.
 *   2. Create a new RepoGroup that combines the two seeded git_repo rows.
 *   3. Verify it appears in the list.
 *   4. Navigate to Runs → Start Run dialog.
 *   5. Pick the v17 "Feature Development" template (uses software_project_id
 *      input) — `latestOnly=true` (the StartRunDialog default) returns v17, so
 *      the picker label is just "Feature Development".
 *   6. From the grouped dropdown, pick the newly-created group from the
 *      "Repo Groups" section (which appears above "Repositories").
 *   7. Submit. Verify a run was created via the API.
 *
 * The seeder (E2eTestDataSeeder) already provisions:
 *   - Two git_repo rows on SYSTEM_ORG ({@code e2e-test/mock-repo} +
 *     {@code e2e-test/mock-frontend}).
 *   - A "demo-stack" RepoGroup combining both repos.
 *   - The v17 "Feature Development" system template.
 *
 * The UI test creates its own group ("e2e-group-…") on top of the seeds and
 * cleans it up in {@code afterEach} — the seeded "demo-stack" stays put.
 */

const E2E_GROUP_PREFIX = "e2e-group-";

test.describe("Repo Groups (UI) launches a run via software_project_id", () => {
  test.afterEach(async ({ api }) => {
    // Best-effort cleanup. Leave the seeded "demo-stack" alone; only delete
    // groups this spec produced (prefix-matched). Wrap the whole block so a
    // teardown error never masks the real test failure.
    try {
      const groups = await api.listRepoGroups();
      for (const g of groups) {
        if (g.name.startsWith(E2E_GROUP_PREFIX)) {
          await api.deleteRepoGroup(g.id).catch(() => {});
        }
      }
    } catch {
      // Listing/network blip — ignore.
    }
  });

  test("creates a Repo Group via UI and launches a run from it", async ({
    page,
    api,
  }) => {
    // Fail-fast: the seeded fixtures the UI flow depends on must be present.
    const reposPage = await api.listGitRepos();
    expect(
      reposPage.content.length,
      "E2eTestDataSeeder must seed at least 2 git_repo rows for this spec",
    ).toBeGreaterThanOrEqual(2);
    // The two seeded URLs render as `e2e-test/mock-repo` /
    // `e2e-test/mock-frontend` via {@code repoDisplayName}; the form uses each
    // display name as the {@code aria-label} of its checkbox.
    const repoLabels = reposPage.content.map((r) =>
      r.url.replace(/^https?:\/\/[^/]+\//, "").replace(/\.git$/, ""),
    );

    const groupName = `${E2E_GROUP_PREFIX}${Date.now()}`;
    const featureRequest = `E2E test feature ${Date.now()}`;
    // Run names are capped at 30 chars server-side, so use a base-36 timestamp
    // suffix to keep this unique fixture under the limit.
    const runName = `e2e-rg-run-${Date.now().toString(36)}`;

    // 1. Navigate to Software Projects via the sidebar.
    await page.goto("/runs");
    await page.getByRole("link", { name: /software projects/i }).click();
    await page.waitForURL("**/git-repos");

    // 2. Switch to the Repo Groups tab. (It is the default tab, but click
    //    explicitly so a future default-flip doesn't silently break the spec.)
    await page.getByRole("button", { name: /^repo groups$/i }).click();

    // 3. Open the create dialog. The CTA is rendered as `<Plus> New Group`.
    await page.getByRole("button", { name: /new group/i }).click();
    await expect(
      page.getByRole("heading", { name: /new repo group/i }),
    ).toBeVisible();

    // 4. Fill name + check both members.
    await page.getByLabel(/^name/i).fill(groupName);
    for (const label of repoLabels) {
      await page.getByLabel(label, { exact: true }).check();
    }

    // 5. Click Create. The dialog closes on success.
    await page.getByRole("button", { name: /^create$/i }).click();
    await expect(
      page.getByRole("heading", { name: /new repo group/i }),
    ).not.toBeVisible();

    // 6. The new group should appear in the table. Use exact-name match: the
    //    Members cell next to it has an "Expand {name} members" button whose
    //    accessible name also contains the group name, so a substring match
    //    would resolve to two cells under strict mode.
    await expect(
      page.getByRole("cell", { name: groupName, exact: true }),
    ).toBeVisible();

    // 7. Navigate to Runs and open the Start Run dialog. The sidebar nav link's
    //    accessible name is "Runs g r" (label + shortcut hint kbd children),
    //    so prefer the test-id for stability.
    await page.getByTestId("nav-runs").click();
    await page.waitForURL("**/runs");
    await page.getByTestId("start-run-button").click();
    await expect(page.getByText("Start a New Run")).toBeVisible();

    // 8. Pick the v17 "Feature Development" template. With `latestOnly=true`
    //    (the dialog's default), only v17 is in the list.
    await page.getByTestId("start-run-template-select").click();
    await page
      .getByRole("option", { name: /Feature Development/i })
      .first()
      .click();

    // 9. The schema-driven inputs render once the template is selected. The
    //    SoftwareProject Select is the v17 marker; wait for it before opening.
    const softwareProjectTrigger = page.getByTestId(
      "start-run-software-project-select",
    );
    await expect(softwareProjectTrigger).toBeVisible();
    await softwareProjectTrigger.click();

    // 10. Verify the grouped dropdown puts "Repo Groups" before "Repositories".
    const repoGroupsLabel = page.getByText("Repo Groups", { exact: true });
    const repositoriesLabel = page.getByText("Repositories", { exact: true });
    await expect(repoGroupsLabel).toBeVisible();
    await expect(repositoriesLabel).toBeVisible();
    const repoGroupsBox = await repoGroupsLabel.boundingBox();
    const repositoriesBox = await repositoriesLabel.boundingBox();
    expect(
      repoGroupsBox && repositoriesBox && repoGroupsBox.y < repositoriesBox.y,
      `"Repo Groups" group label should render above "Repositories"`,
    ).toBe(true);

    // 11. Pick the newly-created group.
    await page.getByRole("option", { name: groupName }).click();

    // 12. Fill the feature_request textarea.
    await page.locator("#input-feature_request").fill(featureRequest);

    // 13. Override the auto-inferred name with a deterministic one for lookup.
    const nameInput = page.getByTestId("start-run-name-input");
    await nameInput.fill(runName);

    // 14. Submit.
    await page.getByTestId("start-run-submit").click();
    await expect(page.getByText("Start a New Run")).not.toBeVisible();

    // 15. Confirm via the public API that a run with our name was created and
    //     that it is bound to the v17 "Feature Development" template.
    await expect
      .poll(
        async () => {
          const runs = await api.listRuns();
          return runs.content.some(
            (r) =>
              r.name === runName && r.templateName === "Feature Development",
          );
        },
        {
          message: `Run "${runName}" should appear after Start Run submit`,
          timeout: 10_000,
        },
      )
      .toBe(true);

    // Best-effort: cancel the run so it doesn't keep churning the mock-agent
    // worker after the test resolves. The afterEach above handles the group.
    const runs = await api.listRuns();
    const created = runs.content.find((r) => r.name === runName);
    if (created) {
      await api.cancelRun(created.id).catch(() => {
        // The run may already be in a terminal state by the time we get here
        // (mock-agent is fast). Cancellation is a courtesy, not a contract.
      });
    }
  });

  test("edits a Repo Group via UI: rename and toggle off a member", async ({
    page,
    api,
  }) => {
    // Seed a group via the API so the test starts from a known state. The
    // Edit affordance is what we're exercising here, not the Create flow.
    const reposPage = await api.listGitRepos();
    expect(
      reposPage.content.length,
      "E2eTestDataSeeder must seed at least 2 git_repo rows for this spec",
    ).toBeGreaterThanOrEqual(2);
    const repoLabels = reposPage.content.map((r) =>
      r.url.replace(/^https?:\/\/[^/]+\//, "").replace(/\.git$/, ""),
    );
    const repoIds = reposPage.content.slice(0, 2).map((r) => r.id);
    const originalName = `${E2E_GROUP_PREFIX}edit-${Date.now()}`;
    const renamed = `${originalName}-renamed`;
    const seeded = await api.createRepoGroup({
      name: originalName,
      memberRepoIds: [repoIds[0], repoIds[1]],
    });

    // 1. Navigate to the Repo Groups tab.
    await page.goto("/git-repos");
    await page.getByRole("button", { name: /^repo groups$/i }).click();

    // 2. Confirm the seeded group appears, then open Edit for its row. Use
    //    exact-name match — the adjacent "Expand {name} members" button cell
    //    would otherwise also match a substring lookup.
    await expect(
      page.getByRole("cell", { name: originalName, exact: true }),
    ).toBeVisible();
    const row = page.getByRole("row").filter({ hasText: originalName });
    await row.getByTestId("repo-group-edit-button").click();
    await expect(
      page.getByRole("heading", { name: /edit repo group/i }),
    ).toBeVisible();

    // 3. The form should be seeded — name pre-populated, both members checked.
    await expect(page.getByLabel(/^name/i)).toHaveValue(originalName);
    for (const label of repoLabels.slice(0, 2)) {
      await expect(page.getByLabel(label, { exact: true })).toBeChecked();
    }

    // 4. Rename and uncheck the second member, then save.
    await page.getByLabel(/^name/i).fill(renamed);
    await page.getByLabel(repoLabels[1], { exact: true }).uncheck();
    await page.getByRole("button", { name: /^save$/i }).click();
    await expect(
      page.getByRole("heading", { name: /edit repo group/i }),
    ).not.toBeVisible();

    // 5. Verify the change via the public API.
    await expect
      .poll(
        async () => {
          const groups = await api.listRepoGroups();
          const updated = groups.find((g) => g.id === seeded.id);
          if (!updated) return null;
          return {
            name: updated.name,
            memberCount: updated.members.length,
          };
        },
        {
          message: `Group ${seeded.id} should be renamed to "${renamed}" with 1 member`,
          timeout: 10_000,
        },
      )
      .toEqual({ name: renamed, memberCount: 1 });
  });
});
