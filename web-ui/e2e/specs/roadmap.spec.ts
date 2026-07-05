import { test, expect } from "../fixtures";

test.describe("Roadmap Proposals", () => {
  test("displays roadmap page with heading", async ({ roadmapPage }) => {
    await roadmapPage.goto();
    await expect(roadmapPage.heading).toHaveText("Roadmap");
    await expect(roadmapPage.newProposalButton).toBeVisible();
  });

  test("opens create proposal dialog", async ({ roadmapPage }) => {
    await roadmapPage.goto();
    await roadmapPage.openCreateDialog();

    await expect(roadmapPage.createTitleInput).toBeVisible();
    await expect(roadmapPage.createDescriptionInput).toBeVisible();
    await expect(roadmapPage.createSoftwareProjectSelect).toBeVisible();
    await expect(roadmapPage.createSubmitButton).toBeVisible();
  });

  test("create proposal submit is disabled without required fields", async ({
    roadmapPage,
  }) => {
    await roadmapPage.goto();
    await roadmapPage.openCreateDialog();

    // Submit should be disabled initially
    await expect(roadmapPage.createSubmitButton).toBeDisabled();
  });

  test("create and select a proposal", async ({ roadmapPage, api }) => {
    // Find a git repo to associate with
    const repos = await api.listGitRepos();
    if (repos.content.length === 0) {
      test.skip();
      return;
    }
    const repo = repos.content[0];
    // Single-repo SoftwareProjects share the git_repo's id; the dropdown
    // labels them by `repoDisplayName(url)` (last 2 path segments).
    const projectName = repo.url
      .replace(/^https?:\/\/[^/]+\//, "")
      .replace(/\.git$/, "");
    const uniqueTitle = `E2E Proposal ${Date.now()}`;

    await roadmapPage.goto();
    await roadmapPage.createProposal(
      uniqueTitle,
      "This is an E2E test proposal description",
      projectName,
      "E2E test motivation",
    );

    // Wait for the proposal to appear in the list
    await roadmapPage.page.waitForTimeout(1000);
    await roadmapPage.goto();

    // Select the proposal and verify details
    await roadmapPage.selectProposal(uniqueTitle);
    await expect(roadmapPage.detailTitle).toContainText(uniqueTitle);
    await expect(roadmapPage.detailDescription).toBeVisible();

    // Clean up
    await api.listProposals().then(async (proposals) => {
      const created = proposals.content.find((p) => p.title === uniqueTitle);
      if (created) await api.deleteProposal(created.id);
    });
  });

  test("proposal detail shows action buttons for backlog status", async ({
    roadmapPage,
    api,
  }) => {
    const repos = await api.listGitRepos();
    if (repos.content.length === 0) {
      test.skip();
      return;
    }

    const uniqueTitle = `E2E Actions ${Date.now()}`;
    // git_repo.id IS software_project.id post-V45 — pass it directly.
    const proposal = await api.createProposal({
      title: uniqueTitle,
      description: "Testing action buttons",
      softwareProjectId: repos.content[0].id,
    });

    await roadmapPage.goto();
    await roadmapPage.selectProposal(uniqueTitle);

    // Backlog proposals should have Edit, Start, Delete buttons
    await expect(roadmapPage.editButton).toBeVisible();
    await expect(roadmapPage.startButton).toBeVisible();
    await expect(roadmapPage.deleteButton).toBeVisible();

    // Clean up
    await api.deleteProposal(proposal.id);
  });

  test("delete confirmation dialog works", async ({ roadmapPage, api }) => {
    const repos = await api.listGitRepos();
    if (repos.content.length === 0) {
      test.skip();
      return;
    }

    const uniqueTitle = `E2E Delete ${Date.now()}`;
    await api.createProposal({
      title: uniqueTitle,
      description: "Will be deleted",
      softwareProjectId: repos.content[0].id,
    });

    await roadmapPage.goto();
    await roadmapPage.selectProposal(uniqueTitle);

    // Click delete
    await roadmapPage.deleteButton.click();

    // Confirmation dialog should appear
    await expect(roadmapPage.deleteConfirmButton).toBeVisible();
    await roadmapPage.deleteConfirmButton.click();

    // Wait and verify it's gone
    await roadmapPage.page.waitForTimeout(2000);
    await roadmapPage.goto();

    // The proposal should no longer appear
    const items = roadmapPage.proposalItems.filter({ hasText: uniqueTitle });
    await expect(items).toHaveCount(0);
  });
});
