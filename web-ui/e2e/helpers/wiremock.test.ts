import { describe, it, expect, vi, afterEach } from "vitest";
import { findWireMockRequests, headerOf } from "./wiremock";

describe("findWireMockRequests", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  // WireMock's `/requests/find` answers with flat LoggedRequest objects — url/method/headers
  // sit at the top level, unlike `/requests` (all serve events), which nests the same fields
  // under a `request` key. Reading a nested `.request` here previously left every `headers`
  // lookup empty, which made `headerOf` always report `undefined` with no parse error to surface it.
  it("reads url, method and headers from the top level of each entry", async () => {
    const fetchMock = vi.fn(async () =>
      new Response(
        JSON.stringify({
          requests: [
            {
              url: "/repos/e2e-test/mock-repo/git/refs/heads/choruskube-run-abc",
              method: "DELETE",
              headers: { "User-Agent": "choruskube-worker", Authorization: "Bearer t" },
            },
          ],
        }),
        { status: 200 },
      ),
    );
    vi.stubGlobal("fetch", fetchMock);

    const [req] = await findWireMockRequests({ method: "DELETE", url: "/anything" });

    expect(req.url).toBe("/repos/e2e-test/mock-repo/git/refs/heads/choruskube-run-abc");
    expect(req.method).toBe("DELETE");
    expect(headerOf(req, "User-Agent")).toBe("choruskube-worker");
    expect(headerOf(req, "user-agent")).toBe("choruskube-worker");
  });

  it("answers an empty array when WireMock has no matching requests logged", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => new Response(JSON.stringify({}), { status: 200 })),
    );

    expect(await findWireMockRequests({ method: "GET", url: "/nope" })).toEqual([]);
  });

  it("throws when WireMock's admin API rejects the query", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => new Response("", { status: 500 })),
    );

    await expect(findWireMockRequests({ method: "GET", url: "/x" })).rejects.toThrow(/HTTP 500/);
  });

  it("translates bodyContains into WireMock's bodyPatterns query shape", async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ requests: [] }), { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);

    await findWireMockRequests({
      method: "POST",
      urlPathPattern: "/repos/[^/]+/[^/]+/issues",
      bodyContains: "choruskube-task:abc",
    });

    const [, init] = fetchMock.mock.calls[0];
    expect(JSON.parse(init.body as string)).toEqual({
      method: "POST",
      urlPathPattern: "/repos/[^/]+/[^/]+/issues",
      bodyPatterns: [{ contains: "choruskube-task:abc" }],
    });
  });
});

describe("headerOf", () => {
  it("returns undefined for a header the request never carried", () => {
    expect(headerOf({ url: "", method: "GET", headers: {} }, "User-Agent")).toBeUndefined();
  });
});
