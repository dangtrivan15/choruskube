# CLAUDE.md — ChorusKube Core Conventions

Project-wide instructions for Claude Code agents working in this repository.

> **YOU MUST read [CONTRIBUTING.md](CONTRIBUTING.md) before writing any code.** It contains the project structure, per-component build/test commands, and the DCO sign-off requirement. Read [README.md](README.md) for what ChorusKube is, the local stack, and the port map. Skipping these will lead to wrong assumptions and broken changes.

This is the **open-source core** of ChorusKube. It runs **single-tenant** with no external identity provider: the API is open (`AUTH_ENABLED=false`) and every request is scoped to a seeded "system" organization (`SystemOrgSeeder`, `SingleTenantResolver`, `SingleTenantUserInfoProvider`).

## Quick Reference: Build & Test Commands

`./gradlew test` at the repo root runs the three unit suites in parallel; `-Pe2e` adds the full stack chain (`e2eImages` → `e2eStackUp` → `e2eSmoke` → `e2eSeed` → `e2ePlaywright`, teardown as a finalizer). A bare `./gradlew test` no longer boots the stack. Every build prints a per-task timing table on success and failure; `-Dtest.reports.dir=<abs path>` collects all suites' reports under one repo-named tree. Per-component commands below remain the fast-iteration path — see [CONTRIBUTING.md](CONTRIBUTING.md#components-and-per-component-commands).

| Component    | Build (from its dir)        | Test                | Lint / Format                  |
|--------------|-----------------------------|---------------------|--------------------------------|
| API Server   | `cd api-server && ./gradlew build` | `./gradlew test` | `./gradlew spotlessApply`      |
| Orchestrator | `cd orchestrator && go build ./...` | `go test ./...`  | `gofmt -w .`                   |
| Web UI       | `cd web-ui && npm run build` | `npm run test`      | `npm run lint`                 |

**The reliability gate is `./gradlew test -Pe2e` plus the published-image smoke test**, `scripts/oss-smoke.sh` — the latter boots the canonical Docker Compose stack from published images and drives one feature-dev run end to end (with a throwaway dummy token, no real Claude call). Run both before declaring complete any change that crosses a component boundary. Unit tests alone are for fast iteration, not sign-off.

`./gradlew test -Pe2e`'s Playwright stage is serial unless `-Pworkers=N` (or the `E2E_WORKERS` env var) is set — parallel workers against the one stack, which CI (`.github/workflows/e2e.yml`) sets. Use `-Pe2eNoTeardown` to keep a failed stack alive for inspection — you then own tearing it down (`./scripts/e2e-down.sh --volumes`). New specs must name created resources via `uniqueName()` (`web-ui/e2e/helpers/api-client.ts`), not a static literal, or they'll collide across workers; take the `workerRepo` fixture rather than indexing `listGitRepos()`, and scope any list assertion via `listEpicsForProject()`. See [CONTRIBUTING.md](CONTRIBUTING.md#running-the-playwright-suite-in-parallel) and [web-ui/e2e/PARALLELISM.md](web-ui/e2e/PARALLELISM.md).

The api-server enforces **60% line coverage** via JaCoCo, separate from `test` so local iteration stays fast:

```bash
cd api-server && ./gradlew jacocoTestCoverageVerification
```

## Java Conventions (api-server)

- **Java 25**, Spring Boot, Gradle (Groovy DSL)
- Formatter: Palantir Java Format (Spotless) — run `./gradlew spotlessApply` before committing
- Package root: `com.choruskube`. This OSS slice contains only `com.choruskube.core.*`. The single `@SpringBootApplication` (`com.choruskube.ChorusKubeApplication`) scans `com.choruskube.*` and, finding only the core slice, boots in single-tenant (no-auth) mode
- Package layout under `core/`: `controller/`, `service/`, `repository/`, `model/`, `dto/`, `config/`, `executor/`, `reconciler/`, `specification/`, `exception/`, `util/`
- Use Spring Data JPA repositories (interfaces extending `JpaRepository`)
- DTOs are Java records — prefer records for all new request/response types
- Tests use JUnit 5 + TestContainers (PostgreSQL) — no mocking the database
- JVM test flag required: `-Dnet.bytebuddy.experimental=true` (already in `build.gradle`)
- Dependencies: add to `api-server/build.gradle`

## Go Conventions (orchestrator)

- **Go 1.25**, module `github.com/dangtrivan15/choruskube/orchestrator`
- All internal packages live under `internal/` — nothing is exported outside the module
- Temporal workflows must be **deterministic**: no `time.Now()`, no `rand`, no network calls inside workflow functions — use activities for side effects
- Temporal signals use per-execution names: `human-decision-{execID}` (not global)
- The orchestrator does **not** create containers — it dispatches each node as a Temporal activity that a Worker picks up and executes
- Tests use `github.com/stretchr/testify` — prefer `assert` and `require`
- Error handling: return errors, don't panic; wrap with `fmt.Errorf("context: %w", err)`

## TypeScript Conventions (web-ui)

- **TypeScript**, React 19, Vite, Tailwind CSS
- Path alias: `@/` maps to `src/` (e.g., `import { api } from '@/lib/api'`)
- State management: TanStack React Query for server state, React state for UI state
- WebSocket: STOMP over WebSocket via `@stomp/stompjs` — see `src/lib/stomp.ts`
- Graph visualization: `@xyflow/react` — see `src/components/` for node rendering
- UI components: Base UI + shadcn patterns with Tailwind
- Tests: Vitest + React Testing Library
- No `any` types — use `unknown` and narrow, or define proper interfaces in `src/lib/types.ts`
- Run `npm ci` before the first build to install deps

## Shell Conventions (agent-images, scripts)

- Use `set -euo pipefail` in all bash scripts
- Agent entrypoint is `agent-images/claude-code/entrypoint.sh` — changes affect all agent pods and ship via the published agent image
- The dev image (`agent-images/choruskube-dev`) builds on top of `claude-code` with a fuller toolchain
- Agent-facing CLI scripts (on PATH in the agent image): `report-result`, `fetch-github-token`, `check-decision`, `list-decisions`, `artifact`, `create-proposal`, `list-proposals`, `update-proposal`, `register-pr`, `check-prs`, `run-all-tests`, `get-roadmap-graph`, `update-task-status`, `create-dependency`, `create-milestone`
- Entrypoint-only scripts: `send-heartbeat`, `send-callback`
- All scripts use `JOB_SECRET` Bearer auth for API calls
- Agents access object storage through **presigned URLs** issued by the API server (`artifact` CLI) — object-store credentials never enter the pod

## Database Migrations

- Flyway migrations in `api-server/src/main/resources/db/migration/`
- To find the latest version, list the migration directory and use the next sequential number
- Naming: `V{next}__{snake_case_description}.sql`
- NEVER modify an existing migration file — always create a new one
- NEVER use Flyway undo/rollback — write forward-only migrations
- Custom enum types exist: `executor_type`, `workflow_run_status`, `node_execution_status`, `log_level`, `reviewer_type`, `provisioning_status`, `work_item_status`, `invitation_status`, `github_credential_health_status`, `work_item_priority` — add new values with `ALTER TYPE ... ADD VALUE`.
- Migrations run automatically on API server startup

## Agent Pod Workspace Layout

When running inside a ChorusKube agent container, the filesystem is:

```
/workspace/
├── config.json          # Node execution configuration (read-only)
├── in/                  # Input artifacts from predecessor nodes
│   └── run_log.md       # Accumulated results from all prior nodes
├── out/                 # Output artifacts (write here, uploaded to object storage)
└── repo/                # Single-repo run: the clone itself
    ├── <repo-a>/        # Multi-repo run: one clone per repo, all peers
    └── <repo-b>/
```

- Read `config.json` for run context: `run_id`, `node_execution_id`, `prompt`, `system_prompt`
- Write outputs to `/workspace/out/` — they are automatically uploaded to object storage
- The run log at `/workspace/in/run_log.md` contains all prior node results
- The agent's working directory is `/workspace/repo`. In a multi-repo run that is the *parent* of the clones, not a git repository — use absolute paths and `cd` into a repo before running git commands
- Repos in a multi-repo run are **peers**; there is no primary or target repo. Branches, tests, and PRs are all per-repo

## Per-Repo Agent Configuration

The entrypoint passes every clone to Claude Code as a working directory (`--add-dir`), so each repo supplies its own agent configuration from its own tree:

| Path in the repo | Effect |
|---|---|
| `CLAUDE.md` | Loaded as project memory |
| `.claude/skills/<name>/SKILL.md` | Registered as a skill (needs YAML frontmatter: `name`, `description`) |
| `.claude/agents/<name>.md` | Registered as a subagent |

- Repo-specific agent guidance belongs in the repo, not in the agent image — that is how it reaches only the repos it applies to
- Only generic, repo-agnostic skills are baked into the image at `agent-images/claude-code/skills/`
- Claude Code does **not** descend into subdirectories of the working directory on its own; a repo is discovered only because the entrypoint names it explicitly
- Loading `CLAUDE.md` from an added directory requires `CLAUDE_CODE_ADDITIONAL_DIRECTORIES_CLAUDE_MD=1` (set in the agent Dockerfile); skills and subagents are discovered without it

## Local Stack

`docker-compose.yaml` is the reference environment: API server, orchestrator, worker, web UI, PostgreSQL, Temporal, and MinIO-compatible object storage. A Worker runs the Docker executor, launching agent containers as siblings on the host Docker socket, so the local stack exercises the real workload path, not a stub. Bring it up with `./scripts/up.sh` and down (wiping volumes) with `./scripts/down.sh` — see [QUICKSTART.md](QUICKSTART.md#quick-start).

**Keep `docker-compose.yaml` in sync when touching wiring:**
- **New API env var / secret** → supply a working default in `docker-compose.yaml` so the stack boots from a clean volume with **zero configuration** (the OSS promise: no OIDC, no GitHub App, no Claude token required just to come up)
- **Temporal version** → compose image tag + matching Go SDK in `orchestrator/go.mod`
- **Object storage** → the `OBJECT_STORE_*` env vars (`OBJECT_STORE_ENDPOINT`, `_ACCESS_KEY`, `_SECRET_KEY`, `_BUCKET`) configure the API server; the agent never sees them (presigned URLs only)
- **Agent entrypoint contract** (`agent-images/claude-code/entrypoint.sh`: env vars set, CLI tools on PATH, workspace layout) changes ripple to every agent run

**Before declaring a stack/wiring change complete:** `./scripts/oss-smoke.sh` boots the published images and runs a full feature-dev run. Run it even for one-line compose / env / image changes — unit tests alone don't exercise Temporal, object storage, or the agent workload path.

## Security Rules

- **NEVER** bypass or hardcode `JOB_SECRET`, `ORCHESTRATOR_SECRET`, or `CLAUDE_CODE_OAUTH_TOKEN` — they are injected via environment
- **NEVER** log or print `JOB_SECRET`, `ORCHESTRATOR_SECRET`, `CLAUDE_CODE_OAUTH_TOKEN`, object-store access/secret keys, or GitHub tokens
- **NEVER** commit credentials, API keys, IP addresses, or secrets to the repository
- **NEVER** disable TLS verification or security middleware
- **NEVER** modify `SecurityConfig.java`, `InternalAuthFilter.java`, or `WorkerAuthFilter.java` without explicit approval
- **NEVER** expose internal API endpoints (`/internal/*`) to unauthenticated callers
- **NEVER** store GitHub tokens at rest — they are provisioned dynamically (1-hour TTL via GitHub App installation tokens)
- Three-tier internal auth (always on, regardless of `AUTH_ENABLED`): `ORCHESTRATOR_SECRET` (shared secret) for the orchestrator → full `/internal/**` access; `JOB_SECRET` (per-execution) for agents → scoped to their own execution; and a **Fleet-scoped Worker credential** (`ckw_`-prefixed, minted at Worker registration, rotated on a TTL+grace cycle) authorizing `/worker/**` calls per-request via `WorkerAuthorizer` against a live Fleet→run pin — so revoking a Worker takes effect on its next request. Workers no longer present `ORCHESTRATOR_SECRET`.
- `InternalAuthFilter` enforces Bearer token validation on all `/internal/**` endpoints via SHA-256 hash comparison; mode is set by `INTERNAL_AUTH_MODE` (`enforce` rejects, `warn` logs only)
- The public `/api/**` surface is **open in the OSS core** (`AUTH_ENABLED=false`); `SecurityConfig` delegates `/api/**` to an `AuthConfigurer` that permits all in single-tenant mode. Do not assume a logged-in principal — every request resolves to the seeded system org
- **Claude OAuth token (per-org)**: the system org's `CLAUDE_CODE_OAUTH_TOKEN` (long-lived, ~1-year TTL from `claude setup-token`) is seeded from the env var at startup and stored encrypted. Executors resolve it at pod-launch time and inject it into the per-execution secret. Without a token the stack still boots and serves the full API — only AI nodes fail soft

## Architecture Rules

- The **Worker owns the workload executor** — it creates and manages the agent containers (a Docker container locally, a Kubernetes Job in a cluster) for each node execution. The orchestrator dispatches node work as Temporal activities; it never creates containers directly
- **The workload executor is tenant-agnostic** — it launches into a namespace, service account, and credentials it is *given*, and never resolves an organization, namespace, or credential itself. The API server resolves per-run credentials (workload `prepare`) and records execution state (workload `complete`); it is the source of truth for state but is out of the execution hot path
- Workflows are Temporal workflows — all side effects must be in activities, not workflow functions
- The API server is the single source of truth for run/execution state (PostgreSQL)
- WebSocket events (STOMP) broadcast state changes — the web UI subscribes, never polls
- Conditional routing uses the `decision` column on `node_execution` — edges with matching conditions are followed
- **Templates are base-only** — no parent/child template inheritance. `git_repo_id` is a run input, not a template field. Templates are immutable seed data with no mutation endpoints
- **GitRepo is a first-class entity** — each org gets its own Kubernetes namespace with provisioned RBAC, network policies, and optional Docker registry mirrors
- **Single-tenant identity** — there is no OIDC provider in the OSS core. `SingleTenantResolver` / `SingleTenantUserInfoProvider` resolve every request to the seeded system org; `SystemOrgSeeder` materializes it (and its credentials) at boot
- Each component has its own Dockerfile with multi-stage builds — keep build and runtime stages separate

## Contribution Workflow

- **Sign off every commit** (DCO): `git commit -s`. Commits without a `Signed-off-by` line are not accepted — see [CONTRIBUTING.md](CONTRIBUTING.md#sign-your-commits-dco)
- Licensed under [AGPL-3.0](LICENSE)

## Code Comments

A comment states the constraint that fires if you edit **this line**. Target ≤3 lines. Apply in order:

1. **Consequence.** Acting without this knowledge — does the world change wrongly (outage, data loss, silent corruption, unrepeatable state), or does it just cost time / fail loudly? The second goes **entirely**: no relocation, no pointer.
2. **Redundancy.** If the sentence still reads correctly with the clause deleted, delete it.
3. **No run-scoped references.** Never cite a run's spec by ordinal — `Decision N`, `§N.N`, `Caveat N`, "the plan". Those identifiers are scoped to one run; the next agent resolves them against its own spec and gets a different answer. State the constraint instead. Durable references — a repo-relative file path, a type name, an issue URL — are fine.

Enforced by `scripts/check-comment-refs.sh`, which runs as part of the root `./gradlew test`.

## Documentation Conventions

- Keep `CLAUDE.md` concise — it is loaded into every agent's context window
- Do not duplicate API docs here — the API server has Swagger/OpenAPI at `/swagger-ui.html`
- `README.md` and `CONTRIBUTING.md` are the human-facing entry points; reference them rather than restating their content here
