/**
 * The DinD run `specs/workload-lifetime.spec.ts` watches being reclaimed. `workload-lifetime.setup.ts`
 * starts it in the setup project, ahead of every other spec, and hands its id over in a file.
 */
import { mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { seededRepos } from "../fixtures";
import { uniqueName, type TestApiClient, type WorkflowRun } from "./api-client";

// Beside the setup project's auth state, under the same gitignored directory.
const RUN_FILE = join(dirname(fileURLToPath(import.meta.url)), "../.auth/workload-lifetime-run.json");

export async function startWorkloadLifetimeRun(api: TestApiClient): Promise<WorkflowRun> {
  const dindRepo = seededRepos((await api.listGitRepos()).content).find((r) => r.url.endsWith("/dind-repo"));
  if (!dindRepo) {
    throw new Error("E2eTestDataSeeder must seed the DinD-enabled e2e-test/dind-repo for workload-lifetime.spec.ts");
  }
  const template = await api.getTemplateByName("e2e-dind-isolation");
  return api.startRun({
    graphTemplateId: template.id,
    name: uniqueName("dind-lifetime"),
    inputs: { git_repo_id: dindRepo.id },
  });
}

export function saveWorkloadLifetimeRunId(runId: string): void {
  mkdirSync(dirname(RUN_FILE), { recursive: true });
  writeFileSync(RUN_FILE, JSON.stringify({ runId }));
}

export function clearWorkloadLifetimeRunId(): void {
  rmSync(RUN_FILE, { force: true });
}

/** The run the setup project started, or null under a config whose setup project does not start it. */
export function loadWorkloadLifetimeRunId(): string | null {
  try {
    const { runId } = JSON.parse(readFileSync(RUN_FILE, "utf8")) as { runId?: unknown };
    return typeof runId === "string" ? runId : null;
  } catch {
    return null;
  }
}
