import { test, expect } from "../fixtures";
import { uniqueName } from "../helpers/api-client";

// Covers the Supervisor's DAG rendering only. Driving a real escalate -> route round trip
// through this harness is out of scope: DecisionOptionsResolver grants the implicit `escalate`
// decision only to executor_type "ai" nodes, and entrypoint.sh only honours a template node's
// scripted `command` override for executor_type "script" — every other type launches a real
// Claude session. E2eTestDataSeeder deliberately never declares an "ai" node (it would need a
// live Claude call this stack doesn't provide), so no node in this template — or any other e2e
// template — can legally submit `escalate`. The escalate/route/force-ready orchestration itself
// is covered at the workflow level by the orchestrator's own Go tests instead.
test.describe("Supervisor node", () => {
  test("the Supervisor is visible in the DAG before it has ever run", async ({
    runMonitorPage,
    api,
  }) => {
    const template = await api.getTemplateByName("e2e-supervisor-node");

    const run = await api.startRun({
      graphTemplateId: template.id,
      name: uniqueName("e2e-supervisor-dag"),
    });

    await runMonitorPage.goto(run.id);
    await expect(runMonitorPage.dagNodes.first()).toBeVisible({ timeout: 15_000 });

    // The Supervisor (config_overrides.routing_hub) has no edges pointing to it, so nothing in
    // this template's own run ever dispatches it — it renders purely from the graph snapshot,
    // never from a NodeExecution row, and so starts (and stays) "pending".
    const supervisorNode = runMonitorPage.page.locator(
      '[data-testid="dag-node"][data-label="supervisor"]',
    );
    await expect(supervisorNode).toBeVisible();
    await expect(supervisorNode).toHaveAttribute("data-routing-hub", "true");
    await expect(supervisorNode).toContainText(/pending/i);

    // RunDag.tsx excludes the Supervisor from the ELK-laid-out flow and pins it beside the graph
    // instead; DagNode.tsx surfaces that as the "Out of graph" caption, dashed border aside.
    await expect(supervisorNode.getByTestId("dag-node-routing-hub-caption")).toBeVisible();
    await expect(supervisorNode.getByTestId("dag-node-routing-hub-caption")).toContainText(
      /out of graph/i,
    );
  });
});
