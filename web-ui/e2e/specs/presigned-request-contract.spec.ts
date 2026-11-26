/**
 * From inside a real agent container, with a URL the api-server actually minted: an unsigned
 * copy-source copies nothing, a forwarding-prefix retarget to another run's object is refused and
 * leaves that object unchanged, and the same URL sent unmodified still succeeds. This is the one
 * hop `PresignedRequestStoreContractTest` cannot reach -- a container's own network path to the
 * store -- so between the two, the contract is held both at the store and from where an agent
 * actually sends a request.
 */
import { test, expect } from "../fixtures";
import { uniqueName } from "../helpers/api-client";
import { agentWorkload, sendFromWorkload } from "../helpers/docker";
import { presign } from "../helpers/agent-presign";
import { bucketOf, retargetKey, forwardingPrefixFor, retargetUrl } from "../helpers/presigned-requests";

test.describe("Presigned request contract, from inside an agent container", () => {
  test("acts only on the signed object whatever headers or forwarding prefix are added", async ({ api }) => {
    // Covers waiting for two slow branch_b containers (E2eTestDataSeeder#seedParallelFanout's
    // `slow --delay 120`) plus every request this spec sends.
    test.setTimeout(150_000);

    const template = await api.getTemplateByName("e2e-parallel-fanout");
    const runA = await api.startRun({ graphTemplateId: template.id, name: uniqueName("contract-a") });
    const runB = await api.startRun({ graphTemplateId: template.id, name: uniqueName("contract-b") });

    try {
      const [branchBInA, branchBInB] = await Promise.all([
        api.waitForNodeStatus(runA.id, "branch_b", ["running"], 60_000),
        api.waitForNodeStatus(runB.id, "branch_b", ["running"], 60_000),
      ]);
      const [workloadA, workloadB] = await Promise.all([
        agentWorkload(branchBInA.id),
        agentWorkload(branchBInB.id),
      ]);

      // Seed: run B's agent PUTs a known body to its own key, from its own container.
      const bKey = `${workloadB.outputPath}contract-target.txt`;
      const bContent = "run B's contract target bytes";
      const bPutUrl = await presign(runB.id, branchBInB.id, workloadB.jobSecret, bKey, "PUT", bContent.length);
      const bucket = bucketOf(bPutUrl);
      const seedPut = await sendFromWorkload(workloadB.container, bPutUrl, { method: "PUT", body: bContent });
      expect(seedPut.status).toBeGreaterThanOrEqual(200);
      expect(seedPut.status).toBeLessThan(300);

      // Copy-source: run A PUTs to its own key with an unsigned copy-source naming B's key.
      const destKey = `${workloadA.outputPath}copy-dest.txt`;
      const destPutUrl = await presign(runA.id, branchBInA.id, workloadA.jobSecret, destKey, "PUT", 0);
      const copySourcePut = await sendFromWorkload(workloadA.container, destPutUrl, {
        method: "PUT",
        body: "",
        headers: { "x-amz-copy-source": `/${bucket}/${bKey}` },
      });
      expect(copySourcePut.status).toBeGreaterThanOrEqual(400);

      const destGetUrl = await presign(runA.id, branchBInA.id, workloadA.jobSecret, destKey, "GET");
      const destGet = await sendFromWorkload(workloadA.container, destGetUrl, { method: "GET" });
      expect(destGet.body).not.toContain(bContent);

      // The same URL and empty body without the header is stored, so the refusal above came from
      // the copy-source alone.
      const destPutUnmodified = await sendFromWorkload(workloadA.container, destPutUrl, { method: "PUT", body: "" });
      expect(destPutUnmodified.status).toBeGreaterThanOrEqual(200);
      expect(destPutUnmodified.status).toBeLessThan(300);

      // Retarget: run A presigns a key in its own tree whose tail spells B's path, then sends the
      // request to B's actual path with a forwarding prefix that rebuilds the signed path.
      const retargetPresignKey = retargetKey(workloadA.outputPath, bKey, bucket);
      const forwardingPrefix = forwardingPrefixFor(workloadA.outputPath, bucket);
      const actualTargetPath = `/${bucket}/${bKey}`;

      const retargetGetSignedUrl = await presign(
        runA.id,
        branchBInA.id,
        workloadA.jobSecret,
        retargetPresignKey,
        "GET",
      );
      const retargetGet = await sendFromWorkload(
        workloadA.container,
        retargetUrl(retargetGetSignedUrl, actualTargetPath),
        { method: "GET", headers: { "X-Forwarded-Prefix": forwardingPrefix } },
      );
      expect(retargetGet.status).toBeGreaterThanOrEqual(400);

      const attackerBody = "overwritten-from-run-a";
      const retargetPutSignedUrl = await presign(
        runA.id,
        branchBInA.id,
        workloadA.jobSecret,
        retargetPresignKey,
        "PUT",
        attackerBody.length,
      );
      const retargetPut = await sendFromWorkload(
        workloadA.container,
        retargetUrl(retargetPutSignedUrl, actualTargetPath),
        { method: "PUT", body: attackerBody, headers: { "X-Forwarded-Prefix": forwardingPrefix } },
      );
      expect(retargetPut.status).toBeGreaterThanOrEqual(400);

      // B's object, read from B's own container, is still exactly what was seeded.
      const bGetUrl = await presign(runB.id, branchBInB.id, workloadB.jobSecret, bKey, "GET");
      const bGet = await sendFromWorkload(workloadB.container, bGetUrl, { method: "GET" });
      expect(bGet.status).toBe(200);
      expect(bGet.body).toBe(bContent);

      // Control: the unmodified URLs still succeed, so the refusals above are not vacuous.
      const controlKey = `${workloadA.outputPath}control.txt`;
      const controlBody = "control-body";
      const controlPutUrl = await presign(
        runA.id,
        branchBInA.id,
        workloadA.jobSecret,
        controlKey,
        "PUT",
        controlBody.length,
      );
      const controlPut = await sendFromWorkload(workloadA.container, controlPutUrl, {
        method: "PUT",
        body: controlBody,
      });
      expect(controlPut.status).toBeGreaterThanOrEqual(200);
      expect(controlPut.status).toBeLessThan(300);

      const controlGetUrl = await presign(runA.id, branchBInA.id, workloadA.jobSecret, controlKey, "GET");
      const controlGet = await sendFromWorkload(workloadA.container, controlGetUrl, { method: "GET" });
      expect(controlGet.status).toBe(200);
      expect(controlGet.body).toBe(controlBody);
    } finally {
      await api.cancelRun(runA.id).catch(() => {});
      await api.cancelRun(runB.id).catch(() => {});
    }
  });
});
