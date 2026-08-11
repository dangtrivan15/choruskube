export interface OrgRef {
  id: string;
  slug: string;
  displayName: string;
}

export interface UserInfoResponse {
  userId: string | null;
  activeOrg: OrgRef | null;
  memberships: OrgRef[];
  platformAdmin: boolean;
  onboardingCompleted: boolean;
  role: string | null;
}

export interface RunSummary {
  id: string;
  graphTemplateId: string;
  templateName: string;
  name: string | null;
  status: string;
  startedAt: string | null;
  completedAt: string | null;
  createdAt: string;
  softwareProject: SoftwareProjectRef | null;
}

export interface RunPullRequestResponse {
  id: string;
  workflowRunId: string;
  gitRepoId: string;
  nodeExecutionId: string | null;
  prUrl: string;
  prNumber: number | null;
  title: string | null;
  repoName: string | null;
  repoUrl: string;
  createdAt: string;
}

export interface RunTaskSummary {
  id: string;
  title: string;
  status: "backlog" | "in_progress" | "done";
  softwareProject: SoftwareProjectRef | null;
  storyId: string | null;
  storyTitle: string | null;
  epicId: string | null;
  epicTitle: string | null;
}

export interface RunResponse {
  id: string;
  graphTemplateId: string;
  templateName: string;
  name: string | null;
  status: string;
  externalRunId: string;
  graphVersion: number;
  graphSnapshot: GraphSnapshot | null;
  startedAt: string | null;
  completedAt: string | null;
  createdAt: string;
  nodeExecutions: NodeExecutionResponse[];
  pullRequests: RunPullRequestResponse[];
  promptText: string | null;
  task: RunTaskSummary | null;
  softwareProject: SoftwareProjectRef | null;
}

export interface GraphSnapshot {
  nodes: SnapshotNode[];
  edges: SnapshotEdge[];
}

export interface SnapshotNode {
  template_node_id: string;
  label: string;
  executor_type: "ai" | "human" | "both" | "script";
  is_entrypoint: boolean;
  prompt_template?: string;
  timeout_seconds?: number;
  config_overrides?: Record<string, unknown>;
  decision_options?: string[];
  effort?: string;
}

export interface SnapshotEdge {
  template_edge_id: string;
  source_node_id: string;
  target_node_id: string;
  condition: string | null;
}

export interface ResolvedArtifactEntry {
  name: string;
  description: string | null;
  required: boolean;
}

export interface ResolvedArtifactGroup {
  nodeExecutionId: string | null;
  nodeLabel: string;
  artifacts: ResolvedArtifactEntry[];
}

export interface NodeExecutionResponse {
  id: string;
  templateNodeId: string;
  status: string;
  result: string | null;
  decision: string | null;
  podName: string | null;
  iteration: number;
  startedAt: string | null;
  completedAt: string | null;
  errorMessage: string | null;
  graphVersion: number;
  artifactRefs: string;
  label: string | null;
  loopGroup: string | null;
  reviewerType: string | null;
  /**
   * template_edge IDs the orchestrator fired when this execution completed.
   * `null` means "not yet evaluated" (still running, or row predates V55).
   * `[]` means "evaluated and fired none" (terminal node).
   * Source of truth for edge highlighting — the UI never re-derives this.
   */
  traversedEdgeIds: string[] | null;
  requiredArtifacts: ResolvedArtifactGroup[] | null;
  /**
   * The Roadmap Provisioner analyzer's structured Epic/Story/Task breakdown for this
   * node, if any (mirrors `PendingGateResponse.candidateBreakdown` — only populated
   * while the node is `awaiting_human`/`live_chat`). Lets the Run Detail page's gate
   * surface (`HumanGatePanel` via `DetailPanel`) render the same editable breakdown
   * the Approvals dashboard does.
   */
  candidateBreakdown: CandidateEpicProposal[] | null;
}

export interface ExecutionLogResponse {
  id: string;
  level: string;
  message: string;
  timestamp: string;
}

export interface ReviewHistoryResponse {
  id: string;
  loopGroup: string;
  iteration: number;
  reviewerType: string;
  decision: string;
  result: string | null;
  status: string | null;
  artifactRefs: string;
  nodeLabel: string | null;
  timestamp: string;
}

export interface ArtifactEntry {
  name: string;
  size: number;
  lastModified: string;
}

export interface InputSchemaField {
  name: string;
  label: string;
  type: string;
  required: boolean;
  default?: string;
}

export type ProvisioningStatus = "pending" | "provisioning" | "ready" | "failed";

export interface GitRepoResponse {
  id: string;
  url: string;
  defaultBranch: string;
  testCommand: string | null;
  agentImage: string | null;
  secrets: unknown[];
  enableDocker: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface OrgProvisioningStatusEvent {
  orgId: string;
  provisioningStatus: ProvisioningStatus;
  namespace: string | null;
}

export interface GraphTemplateResponse {
  id: string;
  graphId: string;
  version: number;
  name: string;
  description: string | null;
  inputSchema: InputSchemaField[];
  system: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface RunEvent {
  type: "run_status_changed" | "node_status_changed" | "node_logs_updated" | "live_chat_status_changed" | "run_pull_request_created";
  runId: string;
  nodeExecutionId: string | null;
  status: string | null;
}

export interface PendingGatePredecessorOutput {
  templateNodeId: string;
  label: string;
  result: string | null;
  artifactRefs: string;
  nodeExecutionId: string | null;
}

/**
 * A candidate Task within a `CandidateStoryProposal` — matches the backend
 * CandidateTaskProposal record. `title`/`description` are reviewer-editable.
 */
export interface CandidateTaskProposal {
  title: string;
  description: string;
}

/**
 * A candidate Story within a `CandidateEpicProposal` — matches the backend
 * CandidateStoryProposal record. `title`/`description` are reviewer-editable;
 * `tasks` is capped at 8 entries server-side (mirrored as a soft UI limit).
 */
export interface CandidateStoryProposal {
  title: string;
  description: string;
  tasks: CandidateTaskProposal[];
}

/**
 * A candidate Epic proposed by the Roadmap Provisioner's analyzer step — matches
 * the backend CandidateEpicProposal record, itself the shape of an entry in the
 * `roadmap_candidates.json` artifact. `title`/`description`/`motivation` and the
 * nested `stories` are reviewer-editable; `repos`/`priority` are read-only
 * decomposition context (materialization silently drops them server-side — there
 * is no persisted destination for them). `stories` is capped at 8 entries
 * server-side (mirrored as a soft UI limit).
 */
export interface CandidateEpicProposal {
  title: string;
  description: string;
  motivation: string;
  repos: string[] | null;
  priority: string | null;
  stories: CandidateStoryProposal[];
}

export interface PendingGateResponse {
  nodeExecutionId: string;
  runId: string;
  runStatus: string;
  runName: string;
  nodeLabel: string;
  iteration: number;
  timeoutSeconds: number | null;
  waitingSince: string | null;
  status: string;
  predecessorOutputs: PendingGatePredecessorOutput[];
  requiredArtifacts: ResolvedArtifactGroup[] | null;
  /**
   * Outgoing edge conditions from this gate's template node. Drives which decision
   * buttons render. Optional because an older API pod during rolling deploy may
   * omit it — callers must fall back (typically to ["approved", "rejected"]).
   */
  decisionOptions?: string[];
  /**
   * The Roadmap Provisioner analyzer's structured Epic/Story/Task breakdown, parsed
   * from the `roadmap_candidates.json` artifact. `null` means no breakdown is
   * available (missing/malformed artifact, or a gate from a template that doesn't
   * produce one) — callers should fall back to the existing markdown/artifact
   * rendering in that case.
   */
  candidateBreakdown: CandidateEpicProposal[] | null;
}

export interface PendingGateCountResponse {
  count: number;
}

/**
 * Lightweight git repo reference embedded in an EpicResponse/TaskResponse. The
 * {@code name} is derived from {@code url} by the server (there is no
 * {@code git_repo.name} column).
 */
export interface RepoRef {
  id: string;
  url: string;
  name: string;
}

/**
 * Lightweight reference to the SoftwareProject (GitRepo or RepoGroup) backing an
 * Epic (and, denormalized, its Tasks).
 */
export interface SoftwareProjectRef {
  id: string;
  type: SoftwareProjectType;
  name: string;
}

/**
 * Rollup completion figure derived from descendant Tasks (Decision 2) — never
 * stored, recomputed on every read. Carried on both EpicResponse and StoryResponse.
 */
export interface WorkItemProgress {
  totalTasks: number;
  doneTasks: number;
}

/**
 * Roadmap board column an Epic sits in. Persisted separately from the
 * read-time {@code status} rollup (never "done" — a rolled-out Epic can
 * still have in-progress descendant Tasks). See PATCH /epics/{id}/stage.
 */
export type EpicStage = "backlog" | "in_progress" | "rolled_out";

export interface EpicResponse {
  id: string;
  title: string;
  description: string;
  motivation: string | null;
  status: "backlog" | "in_progress" | "done";
  stage: EpicStage;
  progress: WorkItemProgress;
  softwareProject: SoftwareProjectRef;
  repos: RepoRef[];
  createdAt: string;
  updatedAt: string;
  /**
   * Count of this Epic's descendant Stories/Tasks with `readiness === "READY"`
   * (roadmap "ready to start" filter) — computed at read time, never stored.
   * Populated on every EpicResponse, not just filtered ones.
   */
  readyItemCount: number;
}

export interface EpicStageUpdateRequest {
  stage: EpicStage;
}

export interface EpicRequest {
  title: string;
  description: string;
  motivation: string | null;
  softwareProjectId: string;
}

/**
 * Dependency-readiness signal for a Story/Task node, populated on the
 * Roadmap Graph View and the flat Story/Task list endpoints (`GET
 * .../stories`, `GET .../tasks`) — computed at read time by walking the full
 * chain of incoming "blocking" edges backward from the item, not just its
 * direct blocker(s); never persisted. `BLOCKED` if any item reachable that
 * way is not yet done, even when the direct blocker itself is done but
 * something further upstream in the chain is not. `null` on single-item
 * reads (create/get/update) — those have no reason to join dependency edges
 * just to return the one item just written.
 */
export type Readiness = "READY" | "BLOCKED";

/**
 * Roadmap board column a Story sits in. Persisted separately from the
 * read-time {@code status} rollup, mirroring {@code EpicStage} exactly. See
 * PATCH /stories/{id}/stage.
 */
export type StoryStage = "backlog" | "in_progress" | "rolled_out";

export interface StoryResponse {
  id: string;
  epicId: string;
  title: string;
  description: string;
  status: "backlog" | "in_progress" | "done";
  stage: StoryStage;
  readiness: Readiness | null;
  progress: WorkItemProgress;
  createdAt: string;
  updatedAt: string;
}

export interface StoryStageUpdateRequest {
  stage: StoryStage;
}

export interface StoryRequest {
  title: string;
  description: string;
}

export interface TaskResponse {
  id: string;
  storyId: string;
  title: string;
  description: string;
  status: "backlog" | "in_progress" | "done";
  softwareProject: SoftwareProjectRef;
  repos: RepoRef[];
  latestRunId: string | null;
  latestRunStatus: string | null;
  readiness: Readiness | null;
  /** Most recent runs, newest first, capped (Decision 3) — see `totalRunCount` for the true count. */
  recentRuns: RunSummary[];
  totalRunCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface TaskRequest {
  title: string;
  description: string;
}

/**
 * Matches the backend TaskStatusUpdateRequest record — body for the
 * validated-transition write `PATCH /api/v1/tasks/{id}/status`. `runId`/`note`
 * are optional: `runId` is the workflow run this outcome is being reported
 * for (must match the Task's most recent linked run when present); `note` is
 * a free-text outcome note recorded on the audit trail. There is no
 * `WorkItemStatus` TypeScript alias — that name is a Java enum on the backend
 * only; the frontend uses the same inline `"backlog" | "in_progress" |
 * "done"` literal union as `TaskResponse.status` everywhere it appears.
 */
export interface TaskStatusUpdateRequest {
  status: "backlog" | "in_progress" | "done";
  runId?: string;
  note?: string;
}

// --- Roadmap Graph View ---

/**
 * Matches the backend BlockableItemType enum — the only two work-item kinds
 * that can participate in a blocking dependency edge (Epics can't).
 */
export type BlockableItemType = "story" | "task";

/**
 * Matches the backend DependencyEdgeResponse record — a "blocking" dependency
 * edge with both endpoints inside the requested Epic's Story/Task tree.
 */
export interface DependencyEdgeResponse {
  id: string;
  blockingItemType: BlockableItemType;
  blockingItemId: string;
  blockedItemType: BlockableItemType;
  blockedItemId: string;
  createdAt: string;
}

/**
 * Matches the backend ExternalBlockerRef record — a reference to a Story/Task
 * OUTSIDE the requested Epic that participates in a dependency edge touching
 * this Epic's tree (e.g. a Task in another Epic that blocks one of this
 * Epic's Tasks). Carries enough context for the UI to render "blocked by a
 * Task in another Epic" without a follow-up lookup, plus which in-Epic item
 * (`internalItemId`) the connection touches and in which direction
 * (`direction`) — needed to render the edge as a real graph edge with a
 * source and a target, not just a sidebar mention.
 */
export interface ExternalBlockerRef {
  itemType: BlockableItemType;
  itemId: string;
  title: string;
  epicId: string;
  epicTitle: string;
  /**
   * The external item's role relative to the in-Epic item it connects to.
   * `BLOCKING` = the external item blocks the in-Epic item. `BLOCKED` = the
   * external item is blocked by the in-Epic item. Jackson's default enum
   * serialization (name-as-string) is the sync point with the backend
   * `BlockerDirection` enum — these string values must match verbatim.
   */
  direction: "BLOCKING" | "BLOCKED";
  /** The id of the specific in-Epic Story/Task this external blocker connects to. */
  internalItemId: string;
}

/**
 * Matches the backend RoadmapGraphSnapshot record — the full graph view of an
 * Epic's Story/Task tree plus its "blocking" dependency edges. Backing
 * GET /api/v1/epics/{epicId}/graph.
 */
export interface RoadmapGraphSnapshot {
  epic: EpicResponse;
  stories: StoryResponse[];
  tasks: TaskResponse[];
  dependencies: DependencyEdgeResponse[];
  externalBlockers: ExternalBlockerRef[];
}

// --- Roadmap Timeline View ---

/**
 * Matches the backend TimelineStorySummary record — one Story plotted on the Roadmap Timeline
 * View, nested under its owning `TimelineEpicSummary`. `stage` mirrors `StoryResponse.stage`
 * (the persisted board column), not the computed `status` rollup — Timeline has no `status`
 * field of its own since it never joins dependency edges or Task rollups. `readiness` and
 * `stalled` are the "blocked or stalled work" risk signals (visually flagged on the Timeline):
 * `readiness` reuses the exact same per-Epic dependency computation as the Roadmap Graph View
 * (`BLOCKED` iff an unfinished dependency blocks this Story); `stalled` is a separate,
 * Timeline-only signal — `true` iff `stage === "in_progress"` and `updatedAt` is more than 14
 * days in the past. The two are independent: a Story can be blocked without being stalled, or
 * stalled without being blocked.
 */
export interface TimelineStorySummary {
  id: string;
  epicId: string;
  title: string;
  stage: StoryStage;
  createdAt: string;
  updatedAt: string;
  readiness: Readiness;
  stalled: boolean;
}

/**
 * Matches the backend TimelineEpicSummary record — one Epic lane on the Roadmap Timeline View,
 * carrying its own Stories ordered ascending by `createdAt`. An Epic with no Stories is still
 * included, with `stories: []`. `stalled` is the same 14-day in-progress-staleness signal as
 * `TimelineStorySummary.stalled`, computed from this Epic's own `stage`/`updatedAt` — it does
 * NOT aggregate its Stories' `stalled` flags (see `deriveEpicRisk` in `@/lib/timelineRisk` for
 * that UI-layer aggregation). There is no Epic-level `readiness` field: readiness is a
 * dependency-graph concept and Epics do not participate in the Story/Task dependency graph.
 */
export interface TimelineEpicSummary {
  id: string;
  title: string;
  stage: EpicStage;
  createdAt: string;
  updatedAt: string;
  stories: TimelineStorySummary[];
  stalled: boolean;
}

/**
 * Matches the backend RoadmapTimelineResponse record — the full org roadmap for the Timeline
 * View: every scoped Epic (with its Stories nested), unpaginated. Backs
 * GET /api/v1/roadmap/timeline.
 */
export interface RoadmapTimelineResponse {
  epics: TimelineEpicSummary[];
}

/** Matches the backend CreateDependencyRequest record — POST /api/v1/dependencies body. */
export interface CreateDependencyRequest {
  blockingItemType: BlockableItemType;
  blockingItemId: string;
  blockedItemType: BlockableItemType;
  blockedItemId: string;
}

/**
 * Matches the backend BlockingChainNode record — one node in a blocking-chain
 * tree: an item that (transitively) blocks its parent, plus its own upstream
 * blockers, recursively.
 */
export interface BlockingChainNode {
  itemType: BlockableItemType;
  itemId: string;
  title: string;
  status: "backlog" | "in_progress" | "done";
  blockedBy: BlockingChainNode[];
}

/**
 * Matches the backend BlockingChainResponse record — the full blocking-chain
 * view for one Story/Task, rooted at the requested item. `truncated` is true
 * when the server-side walk hit its node/depth cap, meaning the tree may omit
 * some real blockers.
 */
export interface BlockingChainResponse {
  itemType: BlockableItemType;
  itemId: string;
  title: string;
  status: "backlog" | "in_progress" | "done";
  readiness: Readiness;
  blockedBy: BlockingChainNode[];
  truncated: boolean;
}

// --- WebSocket Events ---

/**
 * Matches the backend RoadmapItemEvent record.
 * - `itemType` is `"epic_changed"` / `"story_changed"` / `"task_changed"` for a
 *   lifecycle change on that item, `"run_status_changed"` for the bridge event
 *   published when a Task's linked run reaches a terminal status (`itemId` is
 *   `null` in that case — see RunEventPublisher.publishRunStatusChanged), or
 *   `"dependency_changed"` for a blocking-dependency edge create/delete (Roadmap
 *   Graph View) — in that case `itemId` is the `work_item_dependency` row's own
 *   id, not either endpoint's id.
 * - `status` is the item's new `work_item_status` (`backlog`/`in_progress`/`done`),
 *   `"deleted"` on delete, the run's terminal status for the bridge event, or
 *   `"created"`/`"deleted"` for a dependency-changed event.
 */
export interface RoadmapItemEvent {
  itemType: "epic_changed" | "story_changed" | "task_changed" | "run_status_changed" | "dependency_changed";
  itemId: string | null;
  status: string;
}

/** Union of all WebSocket event types that arrive via STOMP.
 *
 * Note: /topic/pending-gates also receives RunEvent objects,
 * so there is no separate PendingGateEvent type.
 */
export type WebSocketEvent = RunEvent | RoadmapItemEvent;

// --- Live Chat ---

export interface LiveChatSessionResponse {
  id: string;
  nodeExecutionId: string;
  workflowRunId: string;
  sourceNodeExecutionId: string | null;
  status: "pending" | "active" | "completed" | "failed";
  transcript: string | null;
  chatPodName: string | null;
  namespace: string | null;
  startedAt: string | null;
  completedAt: string | null;
  createdAt: string;
}

export interface LiveChatMessageEvent {
  type: "live_chat_message";
  sessionId: string;
  role: "user" | "assistant";
  content: string;
}

export interface ChatMessage {
  id: string;
  role: "user" | "assistant";
  content: string;
  timestamp: number;
}

export interface LiveChatMessageResponse {
  id: string;
  sessionId: string;
  role: "user" | "assistant";
  content: string;
  createdAt: string;
}

// --- Activity Feed ---

export interface ActivityFeedEntry {
  id: string;
  timestamp: number;
  message: string;
  variant: "info" | "success" | "warning" | "error";
  /** Optional link target for navigating to the relevant page */
  actionUrl?: string;
}

// --- Pagination & Sorting ---

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
  first: boolean;
  last: boolean;
  empty: boolean;
}

export interface SortParam {
  field: string;
  direction: "asc" | "desc";
}

export interface PaginationParams {
  page?: number;
  size?: number;
  sort?: SortParam | null;
}

// --- Analytics ---

export interface AnalyticsOverviewResponse {
  totalRuns: number;
  completedRuns: number;
  failedRuns: number;
  successRate: number;
  avgDurationSeconds: number | null;
  p50DurationSeconds: number | null;
  p95DurationSeconds: number | null;
}

export interface RunTrendPoint {
  date: string;
  total: number;
  completed: number;
  failed: number;
}

export interface RunTrendResponse {
  points: RunTrendPoint[];
}

export interface TemplateAnalytics {
  templateName: string;
  runCount: number;
  completedCount: number;
  failedCount: number;
  successRate: number;
}

export interface TemplateAnalyticsResponse {
  templates: TemplateAnalytics[];
}

export interface NodeAnalytics {
  label: string;
  executionCount: number;
  completedCount: number;
  failedCount: number;
  successRate: number;
}

export interface NodeAnalyticsResponse {
  nodes: NodeAnalytics[];
}

export interface BottleneckNode {
  label: string;
  avgDurationSeconds: number;
  p50DurationSeconds: number;
  p95DurationSeconds: number;
  sampleSize: number;
}

export interface BottleneckResponse {
  bottlenecks: BottleneckNode[];
}

export interface RoadmapStatusCount {
  status: string;
  count: number;
}

export interface RoadmapStatusCountsResponse {
  total: number;
  statuses: RoadmapStatusCount[];
}

export interface RoadmapThroughputPoint {
  date: string;
  count: number;
}

export interface RoadmapThroughputResponse {
  points: RoadmapThroughputPoint[];
}

// --- Quota & Usage ---

export interface UsageMetric {
  current: number;
  limit: number;
}

export interface MonthlyUsageMetric {
  current: number;
  limit: number;
  periodStart: string;
}

export interface KubernetesQuotaConfig {
  maxPodsPerNamespace: number;
  maxCpuPerNamespace: string;
  maxMemoryPerNamespace: string;
}

export interface K8sAggregateUsage {
  totalCpuAllocated: string;
  maxCpuPerOrg: string;
  totalMemoryAllocated: string;
  maxMemoryPerOrg: string;
  enforced: boolean;
}

export interface UsageSummaryResponse {
  organizationId: string;
  concurrentRuns: UsageMetric;
  repos: UsageMetric;
  monthlyRuns: MonthlyUsageMetric;
  monthlyNodeExecutions: MonthlyUsageMetric;
  k8s: KubernetesQuotaConfig;
  k8sAggregate?: K8sAggregateUsage;
}

export interface QuotaExceededResponse {
  error: string;
  quotaType: string;
  current: number;
  limit: number;
  message: string;
}

// --- Organization Management ---

export interface OrganizationSummaryResponse {
  id: string;
  slug: string;
  displayName: string;
  memberCount: number;
  repoCount: number;
  activeRunCount: number;
  namespace: string | null;
  provisioningStatus: ProvisioningStatus;
  createdAt: string;
}

export interface OrganizationDetailResponse {
  id: string;
  slug: string;
  displayName: string;
  tier: string;
  description: string | null;
  quotaConfig: Record<string, unknown> | null;
  memberCount: number;
  repoCount: number;
  activeRunCount: number;
  namespace: string | null;
  provisioningStatus: ProvisioningStatus;
  createdAt: string;
  updatedAt: string;
}

export type OrgRole = "viewer" | "operator" | "org-admin";

export interface OrganizationMemberResponse {
  id: string;
  email: string | null;
  displayName: string | null;
  role: OrgRole | null;
  createdAt: string;
}

export interface OrganizationStatsResponse {
  organizationId: string;
  totalRuns: number;
  completedRuns: number;
  failedRuns: number;
  activeRuns: number;
  totalRepos: number;
  totalMembers: number;
  monthlyRuns: number;
  monthlyNodeExecutions: number;
  periodStart: string;
}

// --- Audit Events ---

export interface AuditEventResponse {
  id: string;
  organizationId: string;
  actorId: string | null;
  actorEmail: string | null;
  action: string;
  resourceType: string;
  resourceId: string | null;
  detail: Record<string, unknown> | null;
  createdAt: string;
}

// --- Organization Invitations ---

export interface InvitationResponse {
  id: string;
  organizationId: string;
  email: string;
  role: string;
  status: "pending" | "accepted" | "expired" | "revoked";
  invitedByEmail: string | null;
  token: string | null;
  expiresAt: string;
  acceptedAt: string | null;
  createdAt: string;
}

export interface InvitationPreviewResponse {
  id: string;
  organizationName: string;
  email: string;
  status: "pending" | "accepted" | "expired" | "revoked";
  expiresAt: string;
}

export interface CreateInvitationRequest {
  email: string;
  role: string;
}

export interface OrganizationCreateRequest {
  slug: string;
  displayName: string;
  description?: string;
  inviteEmail?: string;
}

// POST /organizations returns the new org alongside the initial invitation (if any), so the
// admin UI can render the shareable deep-link right after create. `initialInvitation` is null
// when the request didn't include `inviteEmail`, or when the best-effort invite creation
// failed on the backend — the org is still created in either case.
export interface OrganizationCreateResponse {
  organization: OrganizationDetailResponse;
  initialInvitation: InvitationResponse | null;
}

export interface OrganizationSetupRequest {
  displayName: string;
  description?: string;
}

// --- Software Projects (GitRepo + RepoGroup hierarchy) ---

export type SoftwareProjectType = "git_repo" | "repo_group";

export interface RuntimeRequirements {
  agentImage: string | null;
  enableDocker: boolean;
}

export interface SoftwareProject {
  id: string;
  name: string;
  type: SoftwareProjectType;
  agentImage: string | null;
  description: string | null;
  runtimeRequirements: RuntimeRequirements;
  createdAt: string;
  updatedAt: string;
}

export interface RepoGroupMember {
  gitRepoId: string;
  name: string;
  position: number;
}

export interface RepoGroup {
  id: string;
  name: string;
  agentImage: string | null;
  description: string | null;
  runtimeRequirements: RuntimeRequirements;
  members: RepoGroupMember[];
  createdAt: string;
  updatedAt: string;
}

export interface RepoGroupRequest {
  name: string;
  agentImage?: string | null;
  description?: string | null;
  memberRepoIds: string[];
}

// --- GitHub Credential ---

export type CredentialHealthStatus = "VALID" | "EXPIRED" | "INSUFFICIENT_PERMISSIONS" | "UNREACHABLE";

// Who provisions and rotates a credential. Orthogonal to credentialType (the auth flavor).
//   "platform" — seeded by SystemOrgSeeder from deployment config; org-admin endpoints reject edits.
//   "org"      — saved by an org admin via the Admin UI.
export type CredentialManagedBy = "platform" | "org";

export interface OrgGitHubCredentialResponse {
  id: string;
  organizationId: string;
  credentialType: string;
  managedBy: CredentialManagedBy;
  tokenHint: string;
  createdAt: string;
  updatedAt: string;
  healthStatus?: CredentialHealthStatus | null;
  lastCheckedAt?: string | null;
  healthDetail?: string | null;
}

export interface ActiveRunsConflictResponse {
  message: string;
  activeRunCount: number;
}

export interface CredentialHealthResponse {
  status: CredentialHealthStatus;
  lastCheckedAt: string;
  detail: string;
  requiredScopes: string[] | null;
  remediationUrl: string | null;
}

export interface CheckRepoReachabilityResponse {
  reachable: boolean;
  detail: string;
}

export interface AttachmentRefsResponse {
  attachmentRefs: string; // JSON string, e.g. "{\"file.pdf\":\"org/runs/.../file.pdf\"}"
}

export interface StagingRefsResponse {
  stagingRefs: string; // JSON string, e.g. "{\"doc.txt\":\"org/staging/uuid/doc.txt\"}"
}

// --- Docs ---

export interface DocsIndexEntry {
  slug: string;
  title: string;
  order: number;
  description?: string;
}

export interface DocsPageResponse {
  slug: string;
  title: string;
  content: string;
}

export interface OrgAiCredentialResponse {
  id: string;
  organizationId: string;
  managedBy: CredentialManagedBy;
  tokenHint: string;
  healthStatus?: CredentialHealthStatus | null;
  lastCheckedAt?: string | null;
  healthDetail?: string | null;
  createdAt: string;
  updatedAt: string;
}
