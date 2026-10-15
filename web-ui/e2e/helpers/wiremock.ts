/**
 * wiremock — reads WireMock's own request journal, the only way an E2E spec can tell which
 * component made a GitHub call: the Worker and the API server send the same bearer token under
 * the `control-plane` credential source, but only the Worker's requests carry its fixed
 * `User-Agent: choruskube-worker` (see worker/github/client.go).
 */

const DEFAULT_WIREMOCK_URL = "http://localhost:28085";

export interface WireMockLoggedRequest {
  url: string;
  method: string;
  headers: Record<string, string>;
}

// `/requests/find`'s entries are flat LoggedRequest objects (url/method/headers at the top
// level) — unlike `/requests` (all serve events), which nests the same fields under a `request`
// key. Reading a nested `.request` here silently made every `headers` lookup empty.
interface WireMockFindRequestsResponse {
  requests?: { url?: string; method?: string; headers?: Record<string, string> }[];
}

/**
 * Queries WireMock's `/__admin/requests/find` with filter (a method plus `url`, `urlPattern`
 * or `urlPathPattern`, and optionally `bodyContains` to match on a request body substring —
 * e.g. a task's hidden issue key), returning the matching requests in the shape this helper's
 * callers assert on.
 */
export async function findWireMockRequests(filter: {
  method: string;
  url?: string;
  urlPattern?: string;
  urlPathPattern?: string;
  bodyContains?: string;
}): Promise<WireMockLoggedRequest[]> {
  const { bodyContains, ...rest } = filter;
  const query = {
    ...rest,
    ...(bodyContains !== undefined ? { bodyPatterns: [{ contains: bodyContains }] } : {}),
  };
  const base = process.env.E2E_WIREMOCK_URL ?? DEFAULT_WIREMOCK_URL;
  const res = await fetch(`${base}/__admin/requests/find`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(query),
  });
  if (!res.ok) {
    throw new Error(`WireMock request journal query failed: HTTP ${res.status}`);
  }
  const body = (await res.json()) as WireMockFindRequestsResponse;
  return (body.requests ?? []).map((r) => ({
    url: r.url ?? "",
    method: r.method ?? "",
    headers: r.headers ?? {},
  }));
}

/** Case-insensitive header lookup: HTTP header names are case-insensitive, but WireMock's
 * journal keys them as they arrived on the wire. */
export function headerOf(req: WireMockLoggedRequest, name: string): string | undefined {
  const lower = name.toLowerCase();
  for (const [key, value] of Object.entries(req.headers)) {
    if (key.toLowerCase() === lower) {
      return value;
    }
  }
  return undefined;
}
