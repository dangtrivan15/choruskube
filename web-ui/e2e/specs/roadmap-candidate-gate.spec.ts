import { test, expect } from "../fixtures";
import { RoadmapCandidateGatePage } from "../pages/roadmap-candidate-gate.page";

// Exercises the Roadmap Provisioner's structured candidate-breakdown gate: the
// analyzer emits `roadmap_candidates.json` alongside its markdown, the reviewer
// sees it as an editable Epic/Story/Task tree on the Approvals page
// (RoadmapCandidateBreakdown.tsx via GateCard), and Approve rides the (possibly
// edited) breakdown along on the signal payload as `editedCandidates` for
// deterministic, non-AI materialization.
//
// Assumes an "e2e-roadmap-candidate-gate" template is seeded by
// E2eTestDataSeeder (api-server), modeled after "e2e-human-gate" /
// "e2e-spec-and-plan-gate": an analyzer-equivalent node whose mock-agent run
// uploads a `roadmap_candidates.json` artifact matching CandidateEpicProposal,
// declared in the gate's requiredInputArtifacts, feeding into a human gate
// node ("review_candidates") with approved/rejected edges — see
// PendingGateService.resolveCandidateBreakdown for the read-side contract this
// spec depends on.
test.describe("Roadmap Provisioner candidate gate", () => {
  test("approvals page shows an editable breakdown before any decision", async ({
    page,
    api,
  }) => {
    const template = await api.getTemplateByName("e2e-roadmap-candidate-gate");
    const runName = `candgate-view-${Date.now().toString(36)}`;
    const run = await api.startRun({ graphTemplateId: template.id, name: runName });

    await api.waitForNodeStatus(run.id, "review_candidates", ["awaiting_human"], 60_000);

    const gatePage = new RoadmapCandidateGatePage(page);
    await gatePage.goto();

    const card = await gatePage.waitForGateCard(runName);
    await expect(gatePage.breakdown(card)).toBeVisible();
    // The editor renders inputs seeded with the analyzer's proposed values —
    // not a plain markdown blob.
    await expect(gatePage.epicTitleInput(card)).toBeVisible();
    await expect(gatePage.epicTitleInput(card)).not.toHaveValue("");
  });

  test("editing a field and approving carries the edit through to the created Epic", async ({
    page,
    api,
  }) => {
    const template = await api.getTemplateByName("e2e-roadmap-candidate-gate");
    const runName = `candgate-edit-${Date.now().toString(36)}`;
    const run = await api.startRun({ graphTemplateId: template.id, name: runName });

    await api.waitForNodeStatus(run.id, "review_candidates", ["awaiting_human"], 60_000);

    const gatePage = new RoadmapCandidateGatePage(page);
    await gatePage.goto();
    const card = await gatePage.waitForGateCard(runName);

    const editedTitle = `Edited via reviewer ${Date.now().toString(36)}`;
    await gatePage.editEpicTitle(card, editedTitle);
    await gatePage.approve(card, "Looks good, approved with one title edit");

    // Approval materializes the (edited) breakdown deterministically — no second
    // AI agent — so the run completes and an Epic with the edited title exists.
    const finished = await api.waitForRunStatus(run.id, ["completed"], 60_000);
    expect(finished.status).toBe("completed");

    const epics = await api.listEpics();
    const created = epics.content.find((e) => e.title === editedTitle);
    expect(created).toBeTruthy();
  });

  test("reject loop still works, with no roadmap items created", async ({
    page,
    api,
  }) => {
    const template = await api.getTemplateByName("e2e-roadmap-candidate-gate");
    const runName = `candgate-reject-${Date.now().toString(36)}`;
    const run = await api.startRun({ graphTemplateId: template.id, name: runName });

    await api.waitForNodeStatus(run.id, "review_candidates", ["awaiting_human"], 60_000);

    const gatePage = new RoadmapCandidateGatePage(page);
    await gatePage.goto();
    const card = await gatePage.waitForGateCard(runName);

    const beforeReject = await api.listEpics();
    const titleBeforeReject = await gatePage.epicTitleInput(card).inputValue();

    await gatePage.reject(card, "Not ready — send back for another pass");

    // Rejecting loops back to the analyzer (same "rejected" edge convention as
    // e2e-human-gate) rather than materializing anything — no Epic/Story/Task
    // rows should exist for this candidate breakdown.
    await api.waitForNodeStatus(run.id, "review_candidates", ["awaiting_human"], 60_000, 2_000);

    const afterReject = await api.listEpics();
    expect(afterReject.content.length).toBe(beforeReject.content.length);
    expect(afterReject.content.some((e) => e.title === titleBeforeReject)).toBe(false);
  });
});
