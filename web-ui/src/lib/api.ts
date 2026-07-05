import type { PaginationParams } from "./types";
import { config } from "@/config";
import { getToken } from "@/lib/oidc";
import { getImpersonation } from "@/lib/impersonation";

const BASE_URL = config.apiBaseUrl;

export class ApiError extends Error {
  status: number;
  body: unknown;
  constructor(status: number, body: unknown) {
    super(`API error ${status}`);
    this.status = status;
    this.body = body;
  }
}

function authHeaders(): Record<string, string> {
  const headers: Record<string, string> = {};
  const token = getToken();
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }
  // Platform-admin "Manage as": re-scope the request to the impersonated org.
  // Backend rejects the header (403) if the caller is not a platform admin.
  const impersonation = getImpersonation();
  if (impersonation) {
    headers["X-Impersonate-Org-Id"] = impersonation.orgId;
  }
  return headers;
}

// Backend error bodies are mixed shape: GlobalExceptionHandler returns plain strings for
// Conflict/BadRequest/NotFound/Forbidden, and JSON objects for QuotaExceededResponse /
// ValidationResponse. Read once as text, opportunistically parse as JSON, and fall back to
// the raw string so callers can surface a useful message either way. (Before: res.json() on
// a plain-text 409 threw and we silently dropped the backend's explanation to null.)
async function readErrorBody(res: Response): Promise<unknown> {
  let text: string;
  try {
    text = await res.text();
  } catch {
    return null;
  }
  if (!text) return null;
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const url = `${BASE_URL}${path}`;
  const headers: Record<string, string> = { ...authHeaders() };
  if (body) headers["Content-Type"] = "application/json";
  const res = await fetch(url, {
    method,
    headers,
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!res.ok) {
    throw new ApiError(res.status, await readErrorBody(res));
  }
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  if (!text) return undefined as T;
  return JSON.parse(text) as T;
}

async function postForm<T>(path: string, formData: FormData): Promise<T> {
  const url = `${BASE_URL}${path}`;
  const headers: Record<string, string> = { ...authHeaders() }; // no Content-Type — browser sets it with boundary
  const res = await fetch(url, { method: "POST", headers, body: formData });
  if (!res.ok) throw new ApiError(res.status, await readErrorBody(res));
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  if (!text) return undefined as T;
  return JSON.parse(text) as T;
}

async function requestText(path: string): Promise<string> {
  const url = `${BASE_URL}${path}`;
  const res = await fetch(url, { method: "GET", headers: authHeaders() });
  if (!res.ok) {
    throw new ApiError(res.status, await readErrorBody(res));
  }
  return res.text();
}

function buildPageParams(params?: PaginationParams): string {
  if (!params) return "";
  const parts: string[] = [];
  if (params.page != null) parts.push(`page=${params.page}`);
  if (params.size != null) parts.push(`size=${params.size}`);
  if (params.sort) {
    parts.push(`sort=${params.sort.field},${params.sort.direction}`);
  }
  return parts.length > 0 ? parts.join("&") : "";
}

function appendParams(path: string, extra: string): string {
  if (!extra) return path;
  return path.includes("?") ? `${path}&${extra}` : `${path}?${extra}`;
}

/**
 * Encode an artifact path for use in a URL. Each "/" remains a path separator;
 * special chars within each segment are percent-encoded.
 */
export function encodeArtifactPath(filename: string): string {
  return filename.split("/").map(encodeURIComponent).join("/");
}

/** Build an absolute URL for an artifact file (suitable for <img src> etc.). */
export function artifactUrl(runId: string, execId: string, filename: string): string {
  return `${BASE_URL}/runs/${runId}/node-executions/${execId}/artifacts/${encodeArtifactPath(filename)}`;
}

export const api = {
  get: <T>(path: string) => request<T>("GET", path),
  getPage: <T>(path: string, pagination?: PaginationParams) =>
    request<T>("GET", appendParams(path, buildPageParams(pagination))),
  getText: (path: string) => requestText(path),
  post: <T>(path: string, body?: unknown) => request<T>("POST", path, body),
  postForm: <T>(path: string, body: FormData) => postForm<T>(path, body),
  put: <T>(path: string, body?: unknown) => request<T>("PUT", path, body),
  patch: <T>(path: string, body?: unknown) => request<T>("PATCH", path, body),
  delete: (path: string) => request<void>("DELETE", path),
};
