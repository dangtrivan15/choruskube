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
 * Dependency-readiness signal for a Story/Task node in the Roadmap Graph View
 * (Decision 2) — computed at read time from that item's direct incoming
 * "blocking" edges, never persisted. `null` on responses assembled outside
 * the graph view (plain Story/Task CRUD reads have no reason to join
 * dependency edges).
 */
export type Readiness = "READY" | "BLOCKED";

export interface StoryResponse {
  id: string;
  epicId: string;
  title: string;
  description: string;
  status: "backlog" | "in_progress" | "done";
  readiness: Readiness | null;
  progress: WorkItemProgress;
  createdAt: string;
  updatedAt: string;
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
 * Task in another Epic" without a follow-up lookup.
 */
export interface ExternalBlockerRef {
  itemType: BlockableItemType;
  itemId: string;
  title: string;
  epicId: string;
  epicTitle: string;
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

/** Matches the backend CreateDependencyRequest record — POST /api/v1/dependencies body. */
export interface CreateDependencyRequest {
  blockingItemType: BlockableItemType;
  blockingItemId: string;
  blockedItemType: BlockableItemType;
  blockedItemId: string;
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
