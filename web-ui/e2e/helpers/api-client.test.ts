import { describe, it, expect, vi, afterEach } from "vitest";
import { TestApiClient, uniqueName } from "./api-client";

// `uniqueName` normally derives its worker index from Playwright's own
// `test.info().parallelIndex`, which is unavailable here — this suite runs under
// Vitest, not a live Playwright test run. Every call below passes the index
// explicitly, exercising the same code path `uniqueName` uses internally once the
// index is known, without needing a running Playwright test.
describe("uniqueName", () => {
  it("puts each worker's index in the name, so two workers can never produce the same one", () => {
    // Asserted on the worker segment rather than on Set size: the module-global
    // per-call counter alone makes every name in a loop distinct, so a size check
    // would still pass if the worker index were dropped from the format entirely.
    // The worker segment is the part that actually separates concurrent workers.
    const segments = [0, 1, 2, 3, 4, 5, 6, 7].map((worker) => {
      const name = uniqueName("epic", worker);
      expect(name).toContain(`-w${worker}-`);
      return `-w${worker}-`;
    });
    expect(new Set(segments).size).toBe(8);
  });

  it("repeated calls from the same worker are distinguishable, not colliding", () => {
    // A spec may call uniqueName more than once from the same worker to mint
    // several distinct resource names — those calls must not collide.
    const first = uniqueName("run", 2);
    const second = uniqueName("run", 2);
    expect(first).not.toBe(second);
  });

  it("keeps the worker component stable across repeated calls from the same worker", () => {
    // Only the trailing per-call suffix varies, so multiple names minted by one
    // worker stay correlatable back to it when reading logs or the database.
    const a = uniqueName("story", 3);
    const b = uniqueName("story", 3);
    expect(a).toContain("-w3-");
    expect(b).toContain("-w3-");
  });

  it("includes the prefix verbatim so failures remain readable", () => {
    const name = uniqueName("My Readable Prefix", 0);
    expect(name.startsWith("My Readable Prefix")).toBe(true);
  });
});

describe("TestApiClient.getGateStamps", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("reads the run's gate stamps from the gate-stamps endpoint", async () => {
    const stamps = [{ id: "stamp-1", status: "consumed", recheckRounds: 1 }];
    const fetchMock = vi.fn(async (url: string) =>
      url.includes("/openid-connect/token")
        ? new Response("", { status: 401 })
        : new Response(JSON.stringify(stamps), { status: 200 }),
    );
    vi.stubGlobal("fetch", fetchMock);

    const client = new TestApiClient("http://api.test", "http://auth.test");
    const result = await client.getGateStamps("run-1");

    expect(result).toEqual(stamps);
    expect(fetchMock).toHaveBeenCalledWith("http://api.test/api/v1/runs/run-1/gate-stamps", expect.anything());
  });
});

describe("TestApiClient Story spec helpers", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  type Route = (url: string, init?: RequestInit) => Response | undefined;

  /** Stubs fetch with the first route that answers each request; the token endpoint is refused. */
  function stubFetch(...routes: Route[]) {
    const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
      if (url.includes("/openid-connect/token")) return new Response("", { status: 401 });
      for (const route of routes) {
        const res = route(url, init);
        if (res) return res;
      }
      throw new Error(`unexpected ${init?.method ?? "GET"} ${url}`);
    });
    vi.stubGlobal("fetch", fetchMock);
    return fetchMock;
  }

  const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
  const run = (status: string, gate?: string) => ({
    id: "run-1",
    status,
    nodeExecutions: gate ? [{ id: "gate-1", label: "approve_story_spec", status: gate, iteration: 1 }] : [],
  });

  /** Answers successive calls to one URL from `responses`, repeating the last. */
  function sequence(urlEnd: string, method: string, responses: (() => Response)[]): Route {
    let call = 0;
    return (url, init) => {
      if (!url.endsWith(urlEnd) || (init?.method ?? "GET") !== method) return undefined;
      return responses[Math.min(call++, responses.length - 1)]();
    };
  }

  it("reads a missing spec as null", async () => {
    stubFetch(sequence("/api/v1/stories/s-1/spec", "GET", [() => new Response("not found", { status: 404 })]));

    const client = new TestApiClient("http://api.test", "http://auth.test");

    expect(await client.getStorySpec("s-1")).toBeNull();
  });

  it("designs the Story, approves its gate, and waits for the recorded version", async () => {
    const fetchMock = stubFetch(
      sequence("/api/v1/stories/s-1/design", "POST", [() => json({ id: "run-1" }, 201)]),
      sequence("/api/v1/runs/run-1", "GET", [
        () => json(run("running")),
        () => json(run("awaiting_human", "awaiting_human")),
      ]),
      sequence("/api/v1/runs/run-1/nodes/gate-1/signal", "POST", [() => new Response("", { status: 200 })]),
      sequence("/api/v1/stories/s-1/spec", "GET", [
        () => new Response("", { status: 404 }),
        () => json({ storyId: "s-1", version: 1, content: "spec", approvedAt: "2026-11-01T00:00:00Z", runId: "run-1" }),
      ]),
    );

    const client = new TestApiClient("http://api.test", "http://auth.test");
    const spec = await client.approveStorySpec("s-1", 5_000, 0);

    expect(spec.version).toBe(1);
    const signal = fetchMock.mock.calls.find(([url]) => url.endsWith("/nodes/gate-1/signal"));
    expect(JSON.parse(String(signal?.[1]?.body))).toEqual({ decision: "approved" });
  });

  it("joins the design run already open on the Story when designing is refused", async () => {
    const fetchMock = stubFetch(
      sequence("/api/v1/stories/s-1/design", "POST", [() => new Response("open design run", { status: 409 })]),
      sequence("/api/v1/stories/s-1", "GET", [() => json({ id: "s-1", openDesignRunId: "run-1" })]),
      sequence("/api/v1/runs/run-1", "GET", [() => json(run("awaiting_human", "awaiting_human"))]),
      sequence("/api/v1/runs/run-1/nodes/gate-1/signal", "POST", [() => new Response("", { status: 200 })]),
      sequence("/api/v1/stories/s-1/spec", "GET", [
        () => json({ storyId: "s-1", version: 1, content: "spec", approvedAt: "2026-11-01T00:00:00Z", runId: "run-1" }),
      ]),
    );

    const client = new TestApiClient("http://api.test", "http://auth.test");
    await client.approveStorySpec("s-1", 5_000, 0);

    expect(fetchMock.mock.calls.some(([url]) => url.endsWith("/nodes/gate-1/signal"))).toBe(true);
  });

  it("rethrows a refused design when no run is open on the Story", async () => {
    stubFetch(
      sequence("/api/v1/stories/s-1/design", "POST", [() => new Response("already approved", { status: 409 })]),
      sequence("/api/v1/stories/s-1", "GET", [() => json({ id: "s-1", openDesignRunId: null })]),
    );

    const client = new TestApiClient("http://api.test", "http://auth.test");

    await expect(client.approveStorySpec("s-1", 5_000, 0)).rejects.toThrow(/409: already approved/);
  });

  it("fails fast, naming the likely cause, when the design run dead-ends before its gate", async () => {
    stubFetch(sequence("/api/v1/runs/run-1", "GET", [() => json(run("awaiting_retry"))]));

    const client = new TestApiClient("http://api.test", "http://auth.test");

    await expect(client.approveDesignRun("run-1", 5_000, 0)).rejects.toThrow(/cloneableProject/);
  });
});
