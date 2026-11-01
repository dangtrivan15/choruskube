import { test, expect } from "../fixtures";
import { uniqueName } from "../helpers/api-client";

/**
 * Designing a Story from its page. The Design button starts a Story Design run on
 * E2eTestDataSeeder's mock copy: mock-agent.sh drafts the spec (story_spec_draft) and reviews it
 * (story_spec_review), and approving the run's gate records the spec as the Story's v1.
 */
test.describe("Story design", () => {
  test("designing a Story from its page and approving the gate shows Spec v1", async ({
    page,
    api,
    cloneableProject,
    roadmapPage,
    runMonitorPage,
  }) => {
    test.setTimeout(300_000);

    const epic = await api.createEpic({
      title: uniqueName("E2E Story Design Epic"),
      description: "Epic for story-design.spec.ts",
      softwareProjectId: cloneableProject.id,
    });
    const story = await api.createStory(epic.id, {
      title: uniqueName("E2E Story Design Story"),
      description: "Story for story-design.spec.ts",
    });
    const storyPath = `/roadmap/epics/${epic.id}/stories/${story.id}`;

    try {
      await page.goto(storyPath);
      await expect(roadmapPage.storySpecEmpty).toBeVisible();
      await roadmapPage.storySpecDesignButton.click();

      // The in-progress callout links the run, which is how a person reaches its gate.
      await expect(roadmapPage.storySpecDesignInProgress).toBeVisible();
      await expect(roadmapPage.storySpecDesignButton).toHaveCount(0);
      const runHref = await roadmapPage.storySpecDesignInProgress.getByRole("link").getAttribute("href");
      const runId = runHref?.split("/").pop();
      expect(runId).toBeTruthy();

      await api.waitForNodeStatus(runId!, "approve_story_spec", ["awaiting_human"], 180_000);
      await runMonitorPage.goto(runId!);
      await runMonitorPage.selectNode("approve_story_spec");
      await runMonitorPage.approveGate();

      // The reconciler records the version after the run completes; reloading reads it rather than
      // depending on the push that announces it.
      await api.waitForStorySpecVersion(story.id, 1, 90_000);
      await page.goto(storyPath);
      await expect(roadmapPage.storySpecVersionSelect).toContainText("v1");
      await expect(roadmapPage.storySpecPanel).toContainText("Mock component");
      await expect(roadmapPage.storySpecPanel.getByTestId("story-spec-run-link")).toHaveAttribute(
        "href",
        `/runs/${runId}`,
      );
      await expect(roadmapPage.storySpecDesignButton).toHaveCount(0);
      await expect(roadmapPage.storySpecReviseButton).toBeVisible();
    } finally {
      // No Task started under it, so the Epic can still be deleted.
      await api.deleteEpic(epic.id);
    }
  });
});
