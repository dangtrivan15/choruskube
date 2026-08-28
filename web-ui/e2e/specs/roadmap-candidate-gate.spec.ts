import { test, expect } from "../fixtures";
import { RoadmapCandidateGatePage } from "../pages/roadmap-candidate-gate.page";
import { uniqueName } from "../helpers/api-client";

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
    // Run names are capped at 30 chars server-side (RunService.RUN_NAME_MAX_LENGTH),
    // so keep the uniqueName() prefix short to stay under the limit even with a
    // 2-digit worker index and multi-digit monotonic counter.
    const runName = uniqueName("cg-view");
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
    workerRepo,
  }) => {
    const template = await api.getTemplateByName("e2e-roadmap-candidate-gate");
    // Materialization resolves software_project_id from the run's inputs (see
    // InternalRunService.resolveSoftwareProjectIdFromRun) — a real Roadmap Provisioner
    // run always supplies one (CLAUDE.md: "git_repo_id is a run input, not a template
    // field"). Without it, DefaultRoadmapCandidateMaterializer silently skips the
    // candidate (caught, logged, and rolled into the "N skipped" count) and the run
    // still completes via the terminal_decisions edge, so this must be supplied here or
    // the Epic below is never created. Single-repo SoftwareProjects share the git_repo's id.
    const runName = uniqueName("cg-edit");
    const run = await api.startRun({
      graphTemplateId: template.id,
      name: runName,
      inputs: { software_project_id: workerRepo.gitRepo.id },
    });

    await api.waitForNodeStatus(run.id, "review_candidates", ["awaiting_human"], 60_000);

    const gatePage = new RoadmapCandidateGatePage(page);
    await gatePage.goto();
    const card = await gatePage.waitForGateCard(runName);

    const editedTitle = uniqueName("Edited via reviewer");
    await gatePage.editEpicTitle(card, editedTitle);
    await gatePage.approve(card, "Looks good, approved with one title edit");

    // Approval materializes the (edited) breakdown deterministically — no second
    // AI agent — so the run completes and an Epic with the edited title exists.
    const finished = await api.waitForRunStatus(run.id, ["completed"], 60_000);
    expect(finished.status).toBe("completed");

    const epics = await api.listEpics();
    const created = epics.content.find((e) => e.title === editedTitle);
    expect(created).toBeTruthy();

    // Clean up. The mock "roadmap_candidates" analyzer scenario proposes this Epic
    // with priority "High", so a leftover row here would permanently
    // join the org-wide "high" tier — breaking roadmap.spec.ts's priority
    // filter/sort test, which relies on being the tier's sole occupant org-wide.
    if (created) {
      await api.deleteEpic(created.id);
    }
  });

  test("run page sidebar shows Approve for the edge-less terminal-decision gate and approving materializes the breakdown", async ({
    api,
    runMonitorPage,
    workerRepo,
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
    const template = await api.getTemplateByName("e2e-roadmap-candidate-gate");
    const runName = uniqueName("cg-sidebar");
    const run = await api.startRun({
      graphTemplateId: template.id,
      name: runName,
      inputs: { software_project_id: workerRepo.gitRepo.id },
    });

    await api.waitForNodeStatus(run.id, "review_candidates", ["awaiting_human"], 60_000);

    await runMonitorPage.goto(run.id);
    await runMonitorPage.selectNode("review_candidates");
    await expect(runMonitorPage.gateApproveButton).toBeVisible();
    await expect(runMonitorPage.gateRejectButton).toBeVisible();

    const beforeApprove = await api.listEpicsForProject(workerRepo.gitRepo.id);

    await runMonitorPage.approveGate("Looks good, approved from the run page sidebar");

    // Approval materializes the analyzer's candidate breakdown deterministically
    // (no second AI agent) — same materialization path the Approvals-page-driven
    // test above exercises, just reached via the previously-broken surface.
    const finished = await api.waitForRunStatus(run.id, ["completed"], 60_000);
    expect(finished.status).toBe("completed");

    const afterApprove = await api.listEpicsForProject(workerRepo.gitRepo.id);
    expect(afterApprove.length).toBeGreaterThan(beforeApprove.length);

    // Clean up the materialized Epic(s), same reasoning as the edit-and-approve test
    // above: the mock analyzer scenario proposes priority "High", and a
    // leftover row here would permanently join the org-wide "high" tier that
    // roadmap.spec.ts's priority filter/sort test assumes it has to itself.
    const beforeIds = new Set(beforeApprove.map((e) => e.id));
    for (const epic of afterApprove.filter((e) => !beforeIds.has(e.id))) {
      await api.deleteEpic(epic.id);
    }
  });

  test("reject loop still works, with no roadmap items created", async ({
    page,
    api,
    workerRepo,
  }) => {
    const template = await api.getTemplateByName("e2e-roadmap-candidate-gate");
    const runName = uniqueName("cg-reject");
    // Supply software_project_id even though nothing should be materialized: without
    // it DefaultRoadmapCandidateMaterializer skips every candidate for lack of a
    // resolvable target (see the edit-and-approve test above), so a regression that
    // wrongly materialized on reject would be masked by the missing input rather
    // than caught. With it, the only reason no Epic appears is that reject doesn't
    // materialize — which is what this test claims.
    const run = await api.startRun({
      graphTemplateId: template.id,
      name: runName,
      inputs: { software_project_id: workerRepo.gitRepo.id },
    });

    await api.waitForNodeStatus(run.id, "review_candidates", ["awaiting_human"], 60_000);

    const gatePage = new RoadmapCandidateGatePage(page);
    await gatePage.goto();
    const card = await gatePage.waitForGateCard(runName);

    const beforeReject = await api.listEpicsForProject(workerRepo.gitRepo.id);

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
    //
    // Both halves are read through the worker's own software project. The ID diff
    // is no safer than the count against a *concurrent* create — either one trips
    // on an Epic another worker made mid-window — so scoping has to wrap both.
    const afterReject = await api.listEpicsForProject(workerRepo.gitRepo.id);
    expect(afterReject.length).toBe(beforeReject.length);
    const beforeIds = new Set(beforeReject.map((e) => e.id));
    expect(afterReject.every((e) => beforeIds.has(e.id))).toBe(true);
  });
});
