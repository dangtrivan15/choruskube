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
    // Materialization resolves software_project_id from the run's inputs (see
    // InternalRunService.resolveSoftwareProjectIdFromRun) — a real Roadmap Provisioner
    // run always supplies one (CLAUDE.md: "git_repo_id is a run input, not a template
    // field"). Without it, DefaultRoadmapCandidateMaterializer silently skips the
    // candidate (caught, logged, and rolled into the "N skipped" count) and the run
    // still completes via the terminal_decisions edge, so this must be supplied here or
    // the Epic below is never created. Single-repo SoftwareProjects share the git_repo's id.
    const repos = await api.listGitRepos();
    expect(
      repos.content.length,
      "E2eTestDataSeeder must seed at least 1 git_repo row for this spec",
    ).toBeGreaterThanOrEqual(1);
    const runName = `candgate-edit-${Date.now().toString(36)}`;
    const run = await api.startRun({
      graphTemplateId: template.id,
      name: runName,
      inputs: { software_project_id: repos.content[0].id },
    });

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

  test("run page sidebar shows Approve for the edge-less terminal-decision gate and approving materializes the breakdown", async ({
    api,
    runMonitorPage,
  }) => {
    // Regression coverage for the bug this fix addresses: the run page's right
    // sidebar previously derived decision buttons from graph edges alone, so the
    // Roadmap Provisioner's edge-less "approved via terminal_decisions" gate
    // silently lost its Approve button here — while the Approvals page (which
    // trusts the server-computed decision_options from PendingGateService)
    // rendered it correctly. See human-gates.spec.ts for the equivalent
    // run-page-sidebar coverage against a gate with a real "approved" edge,
    // which could never have caught this: this template is the one that
    // mirrors the actual edge-less shape that was broken.
    const repos = await api.listGitRepos();
    expect(
      repos.content.length,
      "E2eTestDataSeeder must seed at least 1 git_repo row for this spec",
    ).toBeGreaterThanOrEqual(1);
    const template = await api.getTemplateByName("e2e-roadmap-candidate-gate");
    const runName = `candgate-sidebar-${Date.now().toString(36)}`;
    const run = await api.startRun({
      graphTemplateId: template.id,
      name: runName,
      inputs: { software_project_id: repos.content[0].id },
    });

    await api.waitForNodeStatus(run.id, "review_candidates", ["awaiting_human"], 60_000);

    await runMonitorPage.goto(run.id);
    await runMonitorPage.selectNode("review_candidates");
    await expect(runMonitorPage.gateApproveButton).toBeVisible();
    await expect(runMonitorPage.gateRejectButton).toBeVisible();

    const beforeApprove = await api.listEpics();

    await runMonitorPage.approveGate("Looks good, approved from the run page sidebar");

    // Approval materializes the analyzer's candidate breakdown deterministically
    // (no second AI agent) — same materialization path the Approvals-page-driven
    // test above exercises, just reached via the previously-broken surface.
    const finished = await api.waitForRunStatus(run.id, ["completed"], 60_000);
    expect(finished.status).toBe("completed");

    const afterApprove = await api.listEpics();
    expect(afterApprove.content.length).toBeGreaterThan(beforeApprove.content.length);
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

    await gatePage.reject(card, "Not ready — send back for another pass");

    // Rejecting loops back to the analyzer (same "rejected" edge convention as
    // e2e-human-gate) rather than materializing anything — no Epic/Story/Task
    // rows should exist for this candidate breakdown.
    await api.waitForNodeStatus(run.id, "review_candidates", ["awaiting_human"], 60_000, 2_000);

    // Scope the "nothing was created" check to this run's candidates by diffing
    // Epic IDs, not titles: the analyzer's mocked candidate breakdown proposes the
    // same default title on every run, and another spec in this file (and the run
    // page sidebar test above) legitimately materializes an Epic with that exact
    // unedited default title from a *different*, approved run. A title-existence
    // check would false-positive against that unrelated Epic; an ID-based diff
    // only fails if *this* rejection produced a new row.
    const afterReject = await api.listEpics();
    expect(afterReject.content.length).toBe(beforeReject.content.length);
    const beforeIds = new Set(beforeReject.content.map((e) => e.id));
    expect(afterReject.content.every((e) => beforeIds.has(e.id))).toBe(true);
  });
});
