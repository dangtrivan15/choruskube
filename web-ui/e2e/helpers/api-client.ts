/**
 * TestApiClient — REST client for E2E test orchestration.
 *
 * Used for test data setup and verification. The web UI itself is tested
 * through Playwright browser interactions; this client drives the backend
 * directly for speed.
 *
 * When auth is enabled, obtains a token via the direct access grant
 * (Resource Owner Password Credentials flow) using the E2E test user.
 */

import { createRequire } from "node:module";
import type { RepoRef, SoftwareProjectRef } from "../../src/lib/types";

// E2e-mode defaults (2xxxx range). Local-mode runs on 1xxxx — override via
// API_URL / AUTH_URL env vars if pointing Playwright at the local stack.
const DEFAULT_API_URL = "http://localhost:28080";
const DEFAULT_AUTH_URL = "http://localhost:28081";

// Monotonic within-process counter. On top of the worker/shard namespacing
// below, this guarantees that two `uniqueName` calls made back-to-back from
// the *same* worker in the *same* shard (e.g. a spec that creates several
// named resources) still never collide.
let uniqueNameCounter = 0;

const nodeRequire = createRequire(import.meta.url);

/**
 * Resolves the current Playwright worker's `parallelIndex` via
 * `test.info()`. `@playwright/test` is loaded lazily (via `require`, not a
 * static import) so that this module can be imported — and `uniqueName` unit
 * tested with explicit indices — under Vitest without pulling in
 * `@playwright/test`'s own runtime at module-evaluation time.
 */
function currentParallelIndex(): number {
  const { test } = nodeRequire("@playwright/test") as typeof import("@playwright/test");
  return test.info().parallelIndex;
}

/**
 * Derives a name that's unique across concurrent Playwright workers *and*
 * across CI shards, for specs that create a named resource (Run/Epic/Task
 * title, GitRepo, RepoGroup, ...) and later locate it in the UI via
 * `getByText` or similar.
 *
 * Playwright's own `test.info().parallelIndex` distinguishes workers within
 * one shard, but resets to 0 independently in every shard's own process — so
 * it alone can't distinguish shard 1's worker 0 from shard 2's worker 0. The
 * `SHARD_INDEX` env var (set per CI matrix job — see playwright.config.ts /
 * scripts/e2e-pipeline.sh; defaults to "0" for local, unsharded runs) closes
 * that gap.
 *
 * `parallelIndex`/`shardIndex` are overridable so this function can be unit
 * tested with simulated worker/shard indices outside of a running Playwright
 * test, where `test.info()` is unavailable — production callers should omit
 * both and let them default.
 */
export function uniqueName(
  prefix: string,
  parallelIndex: number = currentParallelIndex(),
  shardIndex: number = Number(process.env.SHARD_INDEX ?? "0"),
): string {
  const suffix = `${Date.now().toString(36)}${(uniqueNameCounter++).toString(36)}`;
  return `${prefix}-s${shardIndex}w${parallelIndex}-${suffix}`;
}

export interface NodeDefinition {
  id: string;
  name: string;
  executorType: string;
  image: string;
  timeoutSeconds: number;
}

export interface GraphTemplate {
  id: string;
  graphId: string;
  version: number;
  name: string;
  description: string | null;
  system: boolean;
}

export interface WorkflowRun {
  id: string;
  graphTemplateId: string;
  templateName: string;
  name: string | null;
  status: string;
  nodeExecutions: NodeExecution[];
}

export interface NodeExecution {
  id: string;
  templateNodeId: string;
  status: string;
  result: string | null;
  decision: string | null;
  iteration: number;
  label: string | null;
  errorMessage: string | null;
}

export interface GitRepo {
  id: string;
  url: string;
  defaultBranch: string;
}

export interface Epic {
  id: string;
  title: string;
  description: string;
  motivation: string | null;
  status: string;
  progress: { totalTasks: number; doneTasks: number };
  softwareProject: SoftwareProjectRef;
  // Single source of truth for the shape — avoids drift with the production type.
  repos: RepoRef[];
}

export interface Story {
  id: string;
  epicId: string;
  title: string;
  description: string;
  status: string;
  progress: { totalTasks: number; doneTasks: number };
}

export interface Task {
  id: string;
  storyId: string;
  title: string;
  description: string;
  status: string;
  softwareProject: SoftwareProjectRef;
  repos: RepoRef[];
  latestRunId: string | null;
  latestRunStatus: string | null;
}

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
}

export interface RepoGroupSummary {
  id: string;
  name: string;
  members: { gitRepoId: string; name: string; position: number }[];
}

// Roadmap Graph View (Part 2) — mirrors the backend DTOs 1:1; see
// src/lib/types.ts for the production-code equivalents.
export interface DependencyEdge {
  id: string;
  blockingItemType: string;
  blockingItemId: string;
  blockedItemType: string;
  blockedItemId: string;
  createdAt: string;
}

export interface ExternalBlocker {
  itemType: string;
  itemId: string;
  title: string;
  epicId: string;
  epicTitle: string;
}

export interface RoadmapGraphSnapshot {
  epic: Epic;
  stories: Story[];
  tasks: Task[];
  dependencies: DependencyEdge[];
  externalBlockers: ExternalBlocker[];
}

export class TestApiClient {
  private readonly baseUrl: string;
  private readonly authUrl: string;
  private token: string | null = null;

  constructor(baseUrl?: string, authUrl?: string) {
    this.baseUrl = baseUrl ?? process.env.E2E_API_URL ?? DEFAULT_API_URL;
    this.authUrl = authUrl ?? process.env.E2E_AUTH_URL ?? DEFAULT_AUTH_URL;
  }

  // ── Auth ─────────────────────────────────────────────────────────

  private async ensureToken(): Promise<void> {
    if (this.token) return;

    try {
      const res = await fetch(
        `${this.authUrl}/realms/choruskube/protocol/openid-connect/token`,
        {
          method: "POST",
          headers: { "Content-Type": "application/x-www-form-urlencoded" },
          body: new URLSearchParams({
            grant_type: "password",
            client_id: "choruskube-ui",
            username: "admin",
            password: "admin",
          }),
        },
      );
      if (res.ok) {
        const data = await res.json();
        this.token = data.access_token;
        return;
      }
    } catch {
      // OIDC provider not available — fall back to no-auth
    }
  }

  private async authHeaders(): Promise<Record<string, string>> {
    await this.ensureToken();
    if (this.token) {
      return { Authorization: `Bearer ${this.token}` };
    }
    return {};
  }

  // ── HTTP primitives ──────────────────────────────────────────────

  private async get<T>(path: string): Promise<T> {
    const headers = await this.authHeaders();
    const res = await fetch(`${this.baseUrl}${path}`, { headers });
    if (!res.ok) {
      throw new Error(`GET ${path} → ${res.status}: ${await res.text()}`);
    }
    return res.json() as Promise<T>;
  }

  private async post<T>(path: string, body?: unknown): Promise<T> {
    const headers: Record<string, string> = await this.authHeaders();
    if (body) headers["Content-Type"] = "application/json";
    const res = await fetch(`${this.baseUrl}${path}`, {
      method: "POST",
      headers,
      body: body ? JSON.stringify(body) : undefined,
    });
    if (!res.ok) {
      throw new Error(`POST ${path} → ${res.status}: ${await res.text()}`);
    }
    const text = await res.text();
    return text ? (JSON.parse(text) as T) : (undefined as T);
  }

  private async put<T>(path: string, body?: unknown): Promise<T> {
    const headers: Record<string, string> = await this.authHeaders();
    if (body) headers["Content-Type"] = "application/json";
    const res = await fetch(`${this.baseUrl}${path}`, {
      method: "PUT",
      headers,
      body: body ? JSON.stringify(body) : undefined,
    });
    if (!res.ok) {
      throw new Error(`PUT ${path} → ${res.status}: ${await res.text()}`);
    }
    const text = await res.text();
    return text ? (JSON.parse(text) as T) : (undefined as T);
  }

  private async patch<T>(path: string, body?: unknown): Promise<T> {
    const headers: Record<string, string> = await this.authHeaders();
    if (body) headers["Content-Type"] = "application/json";
    const res = await fetch(`${this.baseUrl}${path}`, {
      method: "PATCH",
      headers,
      body: body ? JSON.stringify(body) : undefined,
    });
    if (!res.ok) {
      throw new Error(`PATCH ${path} → ${res.status}: ${await res.text()}`);
    }
    const text = await res.text();
    return text ? (JSON.parse(text) as T) : (undefined as T);
  }

  private async delete(path: string): Promise<void> {
    const headers = await this.authHeaders();
    const res = await fetch(`${this.baseUrl}${path}`, { method: "DELETE", headers });
    if (!res.ok && res.status !== 404) {
      throw new Error(`DELETE ${path} → ${res.status}: ${await res.text()}`);
    }
  }

  // ── Health ───────────────────────────────────────────────────────

  async waitForHealthy(timeoutMs = 30_000): Promise<void> {
    const start = Date.now();
    while (Date.now() - start < timeoutMs) {
      try {
        const res = await fetch(`${this.baseUrl}/actuator/health`);
        if (res.ok) return;
      } catch {
        // Server not yet up — retry
      }
      await sleep(1000);
    }
    throw new Error(`API server not healthy after ${timeoutMs}ms`);
  }

  // ── Templates ────────────────────────────────────────────────────

  async listTemplates(latestOnly = true): Promise<PageResponse<GraphTemplate>> {
    return this.get(`/api/v1/graph-templates?size=100&latestOnly=${latestOnly}`);
  }

  async getTemplate(id: string): Promise<GraphTemplate> {
    return this.get(`/api/v1/graph-templates/${id}`);
  }

  async getTemplateByName(name: string): Promise<GraphTemplate> {
    const page = await this.listTemplates();
    const match = page.content.find((t) => t.name === name);
    if (!match) {
      const available = page.content.map((t) => t.name).join(", ") || "<none>";
      throw new Error(
        `E2E template "${name}" was not seeded by E2eTestDataSeeder. ` +
          `Available templates: ${available}. ` +
          `Check api-server/core/src/main/java/com/choruskube/core/config/e2e/E2eTestDataSeeder.java.`,
      );
    }
    return match;
  }

  // ── Git Repos ────────────────────────────────────────────────────

  async listGitRepos(): Promise<PageResponse<GitRepo>> {
    return this.get("/api/v1/git-repos?size=100");
  }

  /**
   * Note: `GitRepoRequest` (backend DTO) has no `name` field — a repo's
   * display name is always derived server-side from its URL
   * (`RepoNameUtil.deriveOwnerRepoName`, see `GitRepoService`). `url` is the
   * only required field; give each E2E-created repo a `uniqueName`-derived
   * URL so its derived display name is worker/shard-unique too.
   */
  async createGitRepo(body: {
    url: string;
    defaultBranch?: string | null;
    testCommand?: string | null;
    agentImage?: string | null;
    secrets?: string | null;
    enableDocker?: boolean | null;
  }): Promise<GitRepo> {
    return this.post("/api/v1/git-repos", {
      url: body.url,
      defaultBranch: body.defaultBranch ?? null,
      testCommand: body.testCommand ?? null,
      agentImage: body.agentImage ?? null,
      secrets: body.secrets ?? null,
      enableDocker: body.enableDocker ?? null,
    });
  }

  async deleteGitRepo(id: string): Promise<void> {
    return this.delete(`/api/v1/git-repos/${id}`);
  }

  // ── Runs ─────────────────────────────────────────────────────────

  async listRuns(status?: string): Promise<PageResponse<WorkflowRun>> {
    const qs = status ? `?status=${status}` : "";
    return this.get(`/api/v1/runs${qs}`);
  }

  async getRun(id: string): Promise<WorkflowRun> {
    return this.get(`/api/v1/runs/${id}`);
  }

  async startRun(body: {
    graphTemplateId: string;
    inputs?: Record<string, string>;
    name?: string;
  }): Promise<WorkflowRun> {
    return this.post("/api/v1/runs", body);
  }

  async cancelRun(id: string): Promise<void> {
    return this.post(`/api/v1/runs/${id}/cancel`);
  }

  async signalNode(
    runId: string,
    nodeExecId: string,
    body: { decision: string; feedback?: string },
  ): Promise<void> {
    return this.post(`/api/v1/runs/${runId}/nodes/${nodeExecId}/signal`, body);
  }

  // ── Epics / Stories / Tasks ────────────────────────────────────────

  async listEpics(): Promise<PageResponse<Epic>> {
    return this.get("/api/v1/epics?size=100");
  }

  async createEpic(body: {
    title: string;
    description: string;
    motivation?: string | null;
    softwareProjectId: string;
  }): Promise<Epic> {
    return this.post("/api/v1/epics", {
      title: body.title,
      description: body.description,
      motivation: body.motivation ?? null,
      softwareProjectId: body.softwareProjectId,
    });
  }

  async deleteEpic(id: string): Promise<void> {
    return this.delete(`/api/v1/epics/${id}`);
  }

  async listStories(epicId: string): Promise<Story[]> {
    return this.get(`/api/v1/epics/${epicId}/stories`);
  }

  async createStory(
    epicId: string,
    body: { title: string; description: string },
  ): Promise<Story> {
    return this.post(`/api/v1/epics/${epicId}/stories`, body);
  }

  async listTasks(storyId: string): Promise<Task[]> {
    return this.get(`/api/v1/stories/${storyId}/tasks`);
  }

  async createTask(
    storyId: string,
    body: { title: string; description: string },
  ): Promise<Task> {
    return this.post(`/api/v1/stories/${storyId}/tasks`, body);
  }

  async startTask(id: string): Promise<Task> {
    return this.post(`/api/v1/tasks/${id}/start`);
  }

  /**
   * Flips an `in_progress` Task to `done` (mirrors `TaskController#complete`'s
   * `PATCH /complete`). Only valid once the Task's most recent linked run has
   * reached a terminal status — pair with `waitForRunStatus` after `startTask`.
   */
  async completeTask(id: string): Promise<Task> {
    return this.patch(`/api/v1/tasks/${id}/complete`);
  }

  // ── Roadmap Graph View (Part 2) ────────────────────────────────────

  async getGraph(epicId: string): Promise<RoadmapGraphSnapshot> {
    return this.get(`/api/v1/epics/${epicId}/graph`);
  }

  async createDependency(body: {
    blockingItemType: string;
    blockingItemId: string;
    blockedItemType: string;
    blockedItemId: string;
  }): Promise<DependencyEdge> {
    return this.post("/api/v1/dependencies", body);
  }

  async deleteDependency(id: string): Promise<void> {
    return this.delete(`/api/v1/dependencies/${id}`);
  }

  // ── Repo Groups ──────────────────────────────────────────────────

  /**
   * Lists every RepoGroup in the active org. Used by E2E specs to
   * verify creation via the UI and to clean up after tests run.
   */
  async listRepoGroups(): Promise<RepoGroupSummary[]> {
    return this.get("/api/v1/repo-groups");
  }

  async createRepoGroup(body: {
    name: string;
    agentImage?: string | null;
    description?: string | null;
    memberRepoIds: string[];
  }): Promise<RepoGroupSummary> {
    return this.post("/api/v1/repo-groups", {
      name: body.name,
      agentImage: body.agentImage ?? null,
      description: body.description ?? null,
      memberRepoIds: body.memberRepoIds,
    });
  }

  async updateRepoGroup(
    id: string,
    body: {
      name: string;
      agentImage?: string | null;
      description?: string | null;
      memberRepoIds: string[];
    },
  ): Promise<RepoGroupSummary> {
    return this.put(`/api/v1/repo-groups/${id}`, {
      name: body.name,
      agentImage: body.agentImage ?? null,
      description: body.description ?? null,
      memberRepoIds: body.memberRepoIds,
    });
  }

  async deleteRepoGroup(id: string): Promise<void> {
    return this.delete(`/api/v1/repo-groups/${id}`);
  }

  // ── Organizations ────────────────────────────────────────────────

  async listOrganizations(
    search?: string,
  ): Promise<PageResponse<{ id: string; slug: string; displayName: string }>> {
    const qs = search ? `?search=${encodeURIComponent(search)}&size=100` : "?size=100";
    return this.get(`/api/v1/organizations${qs}`);
  }

  async createOrganization(body: {
    slug: string;
    displayName: string;
    description?: string | null;
  }): Promise<{ id: string; slug: string; displayName: string }> {
    const result = await this.post<{
      organization: { id: string; slug: string; displayName: string };
    }>("/api/v1/organizations", {
      slug: body.slug,
      displayName: body.displayName,
      description: body.description ?? null,
    });
    return result.organization;
  }

  async deleteOrganization(id: string): Promise<void> {
    return this.delete(`/api/v1/organizations/${id}`);
  }

  async deleteOrgGitHubCredential(orgId: string): Promise<void> {
    return this.delete(`/api/v1/organizations/${orgId}/github-credential`);
  }

  async deleteOrgAiCredential(orgId: string): Promise<void> {
    return this.delete(`/api/v1/organizations/${orgId}/ai-credential`);
  }

  // ── Polling helpers ──────────────────────────────────────────────

  async waitForRunStatus(
    runId: string,
    targetStatuses: string[],
    timeoutMs = 60_000,
    pollIntervalMs = 2_000,
  ): Promise<WorkflowRun> {
    const start = Date.now();
    while (Date.now() - start < timeoutMs) {
      const run = await this.getRun(runId);
      if (targetStatuses.includes(run.status)) return run;
      await sleep(pollIntervalMs);
    }
    throw new Error(
      `Run ${runId} did not reach status [${targetStatuses.join(", ")}] within ${timeoutMs}ms`,
    );
  }

  async waitForNodeStatus(
    runId: string,
    nodeLabel: string,
    targetStatuses: string[],
    timeoutMs = 60_000,
    pollIntervalMs = 2_000,
  ): Promise<NodeExecution> {
    const start = Date.now();
    while (Date.now() - start < timeoutMs) {
      const run = await this.getRun(runId);
      const exec = run.nodeExecutions.find(
        (ne) => ne.label === nodeLabel && targetStatuses.includes(ne.status),
      );
      if (exec) return exec;
      await sleep(pollIntervalMs);
    }
    throw new Error(
      `Node "${nodeLabel}" in run ${runId} did not reach [${targetStatuses.join(", ")}] within ${timeoutMs}ms`,
    );
  }
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
