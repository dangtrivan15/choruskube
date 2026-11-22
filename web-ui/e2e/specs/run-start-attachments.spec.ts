// Covers the run-start attachment seam: an honest browser upload still starts a run with its
// file moved into the run's own tree, and a forged ref (naming a key outside the caller's own
// staging area, or under a name that is not a plain file name) is refused with a fixed 400
// before anything is written. See docs/decisions/2026-11-22---01-a-run-start-holds-its-input-refs-to-the-callers-staging-area.md.
import { randomUUID } from "node:crypto";
import { test, expect } from "../fixtures";
import { uniqueName } from "../helpers/api-client";

test.describe("Run Start — attachments", () => {
  test("starting with an attached file moves it into the run's own tree", async ({
    runListPage,
    api,
  }) => {
    await api.getTemplateByName("e2e-linear-pipeline");

    const runName = uniqueName("e2e-attach");
    await runListPage.goto();
    await runListPage.startRun("e2e-linear-pipeline", runName, [
      { name: "notes.md", mimeType: "text/markdown", buffer: Buffer.from("# Notes") },
    ]);

    let started: { id: string; inputArtifactRefs?: string } | null = null;
    await expect
      .poll(
        async () => {
          const runs = await api.listRuns(undefined, runName);
          started = runs.content.find((r) => r.name === runName) ?? null;
          return started;
        },
        { timeout: 15_000, message: "run started via the UI dialog should be listed" },
      )
      .not.toBeNull();

    const run = await api.getRun(started!.id);
    const refs = JSON.parse(run.inputArtifactRefs ?? "{}") as Record<string, string>;
    expect(refs["notes.md"]).toMatch(new RegExp(`^[^/]+/runs/${run.id}/inputs/notes\\.md$`));

    await api.cancelRun(run.id);
  });

  test("a ref naming a key outside the caller's own staging area is refused with a fixed 400", async ({
    api,
  }) => {
    const template = await api.getTemplateByName("e2e-linear-pipeline");

    const uploaded = await api.uploadStagingAttachments([{ name: "x.txt", content: "x" }]);
    const ownKey = JSON.parse(uploaded.stagingRefs)["x.txt"] as string;
    const prefix = ownKey.split("/staging/")[0];

    const foreignPrefixName = uniqueName("e2e-attach-foreign-prefix");
    const foreignPrefixRes = await api.startRunRaw({
      graphTemplateId: template.id,
      name: foreignPrefixName,
      inputAttachmentRefs: JSON.stringify({
        "x.txt": `${prefix}-other/staging/${randomUUID()}/x.txt`,
      }),
    });
    expect(foreignPrefixRes.status).toBe(400);

    const runTreeName = uniqueName("e2e-attach-run-tree");
    const runTreeRes = await api.startRunRaw({
      graphTemplateId: template.id,
      name: runTreeName,
      inputAttachmentRefs: JSON.stringify({
        "x.txt": `${prefix}/runs/${randomUUID()}/inputs/x.txt`,
      }),
    });
    expect(runTreeRes.status).toBe(400);
    expect(runTreeRes.body).toBe(foreignPrefixRes.body);

    const foreignPrefixRuns = await api.listRuns(undefined, foreignPrefixName);
    expect(foreignPrefixRuns.content).toHaveLength(0);
    const runTreeRuns = await api.listRuns(undefined, runTreeName);
    expect(runTreeRuns.content).toHaveLength(0);
  });
});
