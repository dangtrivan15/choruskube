/**
 * agent-presign.ts — mints a presigned URL the way an agent does, with its own `JOB_SECRET`, for
 * specs that then send it from inside that agent's container (`docker.ts#sendFromWorkload`).
 */
import { expandPath, EXECUTION_SCOPED_PREFIX } from "./agent-routes";

const API_BASE_URL = process.env.E2E_API_URL ?? "http://localhost:28080";

/** Plain `fetch`, never Playwright's `request` fixture, whose traces would record the secret. */
export async function presign(
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
