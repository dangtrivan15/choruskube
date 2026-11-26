/**
 * From inside a real agent container, the object store's S3 port is the only way in: an unsigned
 * request to it is refused by the store itself (403), a presigned transfer still succeeds, and
 * every other port the store's SeaweedFS process opens -- filer, master, volume, their gRPC ports
 * and both catalog servers -- refuses the connection outright, because nothing listens there off
 * loopback. The store's own listener table and both containers' published ports are asserted
 * directly, so a future store release adding a new network-facing listener fails this spec rather
 * than going unnoticed. See ARCHITECTURE.md#where-an-agent-can-reach-the-object-store.
 */
import { test, expect } from "../fixtures";
import { uniqueName } from "../helpers/api-client";
import { agentWorkload, sendFromWorkload } from "../helpers/docker";
import {
  composeServiceContainer,
  connectFromWorkload,
  containerIdOf,
  listenersOf,
  networkModeOf,
  publishedPortsOf,
} from "../helpers/docker";
import { offLoopback } from "../helpers/socket-table";
import { expandPath, EXECUTION_SCOPED_PREFIX } from "../helpers/agent-routes";

const API_BASE_URL = process.env.E2E_API_URL ?? "http://localhost:28080";

// The store's other listeners, measured against the pinned image behind the forwarder: S3 gRPC,
// filer (HTTP + gRPC), master (HTTP + gRPC), volume (HTTP + gRPC), the Iceberg and Lance catalogs.
const REFUSED_STORE_PORTS = [18333, 8888, 18888, 9333, 19333, 8080, 18080, 8181, 9101];

/** Plain `fetch`, never Playwright's `request` fixture, whose traces would record the secret. */
async function presign(
  runId: string,
  execId: string,
  bearer: string,
  objectPath: string,
  method: "GET" | "PUT",
  size?: number,
): Promise<string> {
  const query = new URLSearchParams({ path: objectPath, method });
  if (size !== undefined) {
    query.set("size", String(size));
  }
  const presignPath = expandPath(`${EXECUTION_SCOPED_PREFIX}/presign`, runId, execId);
  const res = await fetch(`${API_BASE_URL}${presignPath}?${query.toString()}`, {
    headers: { Authorization: `Bearer ${bearer}` },
  });
  const json = (await res.json().catch(() => ({}))) as { url?: string };
  if (res.status !== 200 || typeof json.url !== "string") {
    throw new Error(`presign failed with status ${res.status}`);
  }
  return json.url;
}

test.describe("Object store reach, from inside an agent container", () => {
  test("reaches the store only on its S3 port, from the network and at the socket table", async ({ api }) => {
    test.setTimeout(120_000);

    const template = await api.getTemplateByName("e2e-parallel-fanout");
    const run = await api.startRun({ graphTemplateId: template.id, name: uniqueName("object-store-reach") });

    try {
      const branchB = await api.waitForNodeStatus(run.id, "branch_b", ["running"], 60_000);
      const workload = await agentWorkload(branchB.id);

      // An unsigned request to the store's S3 port is refused by the store itself.
      const unsigned = await sendFromWorkload(workload.container, "http://object-storage:9000/", { method: "GET" });
      expect(unsigned.status).toBe(403);

      // A presigned round trip through the forwarder still succeeds, byte for byte.
      const key = `${workload.outputPath}reach-check.txt`;
      const content = "object-store-reach round trip";
      const putUrl = await presign(run.id, branchB.id, workload.jobSecret, key, "PUT", content.length);
      const put = await sendFromWorkload(workload.container, putUrl, { method: "PUT", body: content });
      expect(put.status).toBeGreaterThanOrEqual(200);
      expect(put.status).toBeLessThan(300);

      const getUrl = await presign(run.id, branchB.id, workload.jobSecret, key, "GET");
      const get = await sendFromWorkload(workload.container, getUrl, { method: "GET" });
      expect(get.status).toBe(200);
      expect(get.body).toBe(content);

      // Every other store port refuses the connection outright -- nothing listens there off loopback.
      const connectResults = await Promise.all(
        REFUSED_STORE_PORTS.map((port) => connectFromWorkload(workload.container, "object-storage", port)),
      );
      connectResults.forEach((result, i) => {
        expect(result, `port ${REFUSED_STORE_PORTS[i]}`).toBe("refused");
      });

      // The store's service joined the forwarder's own network namespace.
      const forwarderContainer = await composeServiceContainer("object-storage");
      const storeContainer = await composeServiceContainer("object-storage-server");
      const [forwarderId, storeNetworkMode] = await Promise.all([
        containerIdOf(forwarderContainer),
        networkModeOf(storeContainer),
      ]);
      expect(storeNetworkMode).toBe(`container:${forwarderId}`);

      // The only listener off loopback, anywhere in that shared namespace, is the forwarder's own
      // S3 port -- exhaustively, so a future store release that opens a new listener fails here.
      const listeners = await listenersOf(storeContainer);
      const offLoop = offLoopback(listeners).map((l) => ({ ...l, proto: l.proto === "tcp6" ? "tcp" : l.proto }));
      for (const listener of offLoop) {
        expect(listener.port, JSON.stringify(listener)).toBe(9000);
        expect(listener.proto, JSON.stringify(listener)).toBe("tcp");
      }
      expect(offLoop.length).toBeGreaterThan(0);

      // Published ports match the compose wiring exactly: only the forwarder faces the host.
      expect(await publishedPortsOf(forwarderContainer)).toEqual(["9000/tcp"]);
      expect(await publishedPortsOf(storeContainer)).toEqual([]);
      const setupContainer = await composeServiceContainer("object-storage-setup").catch(() => null);
      if (setupContainer) {
        expect(await publishedPortsOf(setupContainer)).toEqual([]);
      }
    } finally {
      await api.cancelRun(run.id).catch(() => {});
    }
  });
});
