package activity

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/google/uuid"
	temporalactivity "go.temporal.io/sdk/activity"
	"go.temporal.io/sdk/temporal"

	"github.com/dangtrivan15/choruskube/orchestrator/internal/apiclient"
	"github.com/dangtrivan15/choruskube/orchestrator/internal/config"
	"github.com/dangtrivan15/choruskube/orchestrator/internal/objectstore"
	"github.com/dangtrivan15/choruskube/orchestrator/internal/prompt"
	"github.com/dangtrivan15/choruskube/orchestrator/internal/state"
)

type Activities struct {
	client            *apiclient.Client
	resolver          *prompt.Resolver
	config            *config.Config
	objectStoreClient objectstore.ObjectStore
}

func NewActivities(client *apiclient.Client, resolver *prompt.Resolver, cfg *config.Config, objectStoreClient objectstore.ObjectStore) *Activities {
	return &Activities{client: client, resolver: resolver, config: cfg, objectStoreClient: objectStoreClient}
}

// --- Run authority guard ---
//
// A run's workflow can execute in a namespace whose Worker is operated by the tenant that
// owns the run. Temporal has no finer-grained permission than namespace-wide write access, so
// that Worker can answer a workflow task with a scheduled activity call carrying any RunID it
// chooses. guardRun confines such a call to the run that actually scheduled it.

const workflowIDPrefix = "choruskube-run-"

func runIDFromWorkflowID(workflowID string) (uuid.UUID, error) {
	if !strings.HasPrefix(workflowID, workflowIDPrefix) {
		return uuid.Nil, fmt.Errorf("workflow id %q is not a run's", workflowID)
	}
	id, err := uuid.Parse(strings.TrimPrefix(workflowID, workflowIDPrefix))
	if err != nil {
		return uuid.Nil, fmt.Errorf("workflow id %q carries no run id: %w", workflowID, err)
	}
	return id, nil
}

// requireRunMatches rejects params naming a run other than the one whose workflow scheduled this
// activity. The workflow id is assigned by the api-server and fixed in history, so a worker
// answering a workflow task cannot forge it -- which is what keeps a namespace-wide write claim
// from reaching another tenant's run.
func requireRunMatches(workflowID string, claimed uuid.UUID) error {
	actual, err := runIDFromWorkflowID(workflowID)
	if err != nil {
		return err
	}
	if actual != claimed {
		return fmt.Errorf("activity claims run %s but was scheduled by run %s", claimed, actual)
	}
	return nil
}

// activityInfo is temporalactivity.GetInfo behind a seam: activity.GetInfo panics outside a
// real Temporal activity context, and the SDK's test harness cannot pin a chosen workflow id,
// so tests substitute this var instead of a live worker. Never reassigned outside _test.go.
var activityInfo = temporalactivity.GetInfo

// guardRun is called first by every activity taking a RunID on its params.
func guardRun(ctx context.Context, claimed uuid.UUID) error {
	return requireRunMatches(activityInfo(ctx).WorkflowExecution.ID, claimed)
}

// --- Activity: CreateNodeExecution ---

type CreateNodeExecParams struct {
	WorkflowRunID  uuid.UUID
	TemplateNodeID uuid.UUID
	GraphVersion   int
	Iteration      int // 0 means use DB default (1)
	Label          string
}

func (a *Activities) CreateNodeExecution(ctx context.Context, params CreateNodeExecParams) (uuid.UUID, error) {
	if err := guardRun(ctx, params.WorkflowRunID); err != nil {
		return uuid.Nil, err
	}
	exec, err := a.client.CreateNodeExecution(ctx, params.WorkflowRunID, state.CreateNodeExecutionParams{
		WorkflowRunID:  params.WorkflowRunID,
		TemplateNodeID: params.TemplateNodeID,
		GraphVersion:   params.GraphVersion,
		Iteration:      params.Iteration,
		Label:          params.Label,
	})
	if err != nil {
		// Detect 429 (quota exceeded) and wrap as non-retryable to prevent retry storm.
		// Monthly quota exhaustion won't resolve for days — retrying is wasteful.
		var apiErr *apiclient.APIError
		if errors.As(err, &apiErr) && apiErr.StatusCode == http.StatusTooManyRequests {
			return uuid.Nil, temporal.NewNonRetryableApplicationError(
				fmt.Sprintf("organization quota exceeded: %s", apiErr.Body),
				"QUOTA_EXCEEDED",
				err,
			)
		}
		return uuid.Nil, fmt.Errorf("create node execution: %w", err)
	}
	return exec.ID, nil
}

// CallbackResult is the result type returned when the activity is completed externally.
// Kept for Temporal replay: existing workflow histories reference this type.
type CallbackResult struct {
	Status              string    `json:"status"`
	Result              string    `json:"result"`
	ArtifactRefs        string    `json:"artifact_refs"`
	ErrorMessage        string    `json:"error_message"`
	ResumeAt            time.Time `json:"resume_at"`
	SessionID           string    `json:"session_id"`
	SessionArtifactPath string    `json:"session_artifact_path"`
}

// --- Activity: UpdateWorkflowRunStatus ---

type UpdateRunStatusParams struct {
	RunID  uuid.UUID
	Status string
}

func (a *Activities) UpdateWorkflowRunStatus(ctx context.Context, params UpdateRunStatusParams) error {
	if err := guardRun(ctx, params.RunID); err != nil {
		return err
	}
	return a.client.UpdateWorkflowRunStatus(ctx, params.RunID, params.Status)
}

// --- Activity: GetGraphRuntime ---

func (a *Activities) GetGraphRuntime(ctx context.Context, runID uuid.UUID) (string, error) {
	if err := guardRun(ctx, runID); err != nil {
		return "", err
	}
	return a.client.GetGraphRuntime(ctx, runID)
}

// --- Activity stubs: dispatched to the Worker's task queue, never executed here ---
//
// The workflow function references these methods to derive the Temporal activity type name.
// All calls route to params.WorkerTaskQueue, so the orchestrator's worker never receives
// them. The bodies exist solely so the code compiles and the activity type name resolves.

// ExecuteAINodeFromSnapshotParams is kept for the workflow to reference.
type ExecuteAINodeFromSnapshotParams struct {
	NodeExecutionID        uuid.UUID
	RunID                  uuid.UUID
	TemplateNodeID         uuid.UUID
	Label                  string
	ExecutorType           string
	PromptTemplate         string
	InputArtifacts         map[string]string
	RequiredInputArtifacts []string
	Variables              map[string]string
	LoopGroup              string
	Iteration              int
	RepoURL                string
	WorkingBranch          string
	Command                string
	RunLogPath             string
	OrgSlug                string
	NeedDecision           bool
	NeedsPR                bool
	OutputSpec             string
	SupervisorLabel        string
	Repos                  []map[string]interface{} `json:"repos,omitempty"`
	Model                  string                   `json:"model,omitempty"`
	Effort                 string                   `json:"effort,omitempty"`
	MaxTurns               string                   `json:"max_turns,omitempty"`
	MaxRetries             string                   `json:"max_retries,omitempty"`
	TaskID                 string
	TaskTitle              string
	StoryID                string
	StoryTitle             string
	EpicID                 string
	EpicTitle              string
	OpenBlockers           []OpenBlockerParam
	SessionID              string `json:"session_id,omitempty"`
	SessionArtifactPath    string `json:"session_artifact_path,omitempty"`
}

type OpenBlockerParam struct {
	ItemType string
	ItemID   string
	Title    string
	Status   string
}

func (a *Activities) ExecuteAINodeFromSnapshot(_ context.Context, _ ExecuteAINodeFromSnapshotParams) (CallbackResult, error) {
	return CallbackResult{}, fmt.Errorf("activity dispatched to Worker task queue, not the orchestrator")
}

// --- Activity: WriteReviewHistory ---

type WriteReviewHistoryParams struct {
	WorkflowRunID   uuid.UUID
	LoopGroup       string
	Iteration       int
	ReviewerType    string
	ArtifactRefs    string
	NodeExecutionID uuid.UUID
}

func (a *Activities) WriteReviewHistory(ctx context.Context, params WriteReviewHistoryParams) error {
	if err := guardRun(ctx, params.WorkflowRunID); err != nil {
		return err
	}
	return a.client.CreateReviewHistory(ctx, params.WorkflowRunID, state.CreateReviewHistoryParams{
		WorkflowRunID:   params.WorkflowRunID,
		LoopGroup:       params.LoopGroup,
		Iteration:       params.Iteration,
		ReviewerType:    params.ReviewerType,
		ArtifactRefs:    params.ArtifactRefs,
		NodeExecutionID: params.NodeExecutionID,
	})
}

// --- Activity: LoadPredecessorInputs ---

type LoadPredecessorInputsParams struct {
	RunID           uuid.UUID
	NodeExecutionID uuid.UUID
}

func (a *Activities) LoadPredecessorInputs(ctx context.Context, params LoadPredecessorInputsParams) (map[string]string, error) {
	if err := guardRun(ctx, params.RunID); err != nil {
		return nil, err
	}
	if params.NodeExecutionID == uuid.Nil {
		return map[string]string{}, nil
	}

	preds, err := a.client.GetCompletedPredecessors(ctx, params.RunID, params.NodeExecutionID)
	if err != nil {
		return nil, fmt.Errorf("load predecessor artifacts: %w", err)
	}

	vars := map[string]string{}
	for _, pred := range preds {
		label := pred.Label

		// Always use namespaced form: input.<label>.result
		// With transitive resolution, prompts consistently reference by label
		if pred.Result != "" {
			vars[fmt.Sprintf("input.%s.result", label)] = pred.Result
		}

		var refs map[string]string
		if err := json.Unmarshal([]byte(pred.ArtifactRefs), &refs); err != nil {
			temporalactivity.GetLogger(ctx).Warn("skipping artifact_refs for predecessor: unmarshal failed",
				"label", label, "error", err)
			continue
		}
		for key, val := range refs {
			vars[fmt.Sprintf("input.%s.%s", label, key)] = val
		}
	}
	return vars, nil
}

// --- Activity: LoadRequiredInputArtifacts ---

type LoadRequiredInputArtifactsParams struct {
	RunID           uuid.UUID
	NodeExecutionID uuid.UUID
}

// LoadRequiredInputArtifactsResult mirrors state.InputArtifactManifest. It is a distinct type
// because Temporal serialises activity results into workflow history: keeping the wire shape here
// means a later change to the internal model cannot silently alter replay of existing histories.
type LoadRequiredInputArtifactsResult struct {
	Artifacts map[string]string
	Required  []string
}

// LoadRequiredInputArtifacts resolves the files to place under /workspace/in/ for this node.
//
// This is separate from LoadPredecessorInputs, which produces prompt *variables*. That path can
// only name a predecessor's output directory, never a file inside it, so it leaves the agent to
// guess the filename. This one resolves exact object paths.
func (a *Activities) LoadRequiredInputArtifacts(
	ctx context.Context,
	params LoadRequiredInputArtifactsParams,
) (LoadRequiredInputArtifactsResult, error) {
	if err := guardRun(ctx, params.RunID); err != nil {
		return LoadRequiredInputArtifactsResult{}, err
	}
	if params.NodeExecutionID == uuid.Nil {
		return LoadRequiredInputArtifactsResult{}, nil
	}

	manifest, err := a.client.GetInputArtifacts(ctx, params.RunID, params.NodeExecutionID)
	if err != nil {
		return LoadRequiredInputArtifactsResult{}, fmt.Errorf("load input artifact manifest: %w", err)
	}
	return LoadRequiredInputArtifactsResult{Artifacts: manifest.Artifacts, Required: manifest.Required}, nil
}

// --- Activity: LoadReviewHistoryJSON ---

type LoadReviewHistoryJSONParams struct {
	RunID     uuid.UUID
	LoopGroup string
}

func (a *Activities) LoadReviewHistoryJSON(ctx context.Context, params LoadReviewHistoryJSONParams) (string, error) {
	if err := guardRun(ctx, params.RunID); err != nil {
		return "", err
	}
	reviews, err := a.client.GetReviewHistory(ctx, params.RunID, params.LoopGroup)
	if err != nil {
		return "[]", fmt.Errorf("load review history: %w", err)
	}
	if len(reviews) == 0 {
		return "[]", nil
	}
	data, err := json.Marshal(reviews)
	if err != nil {
		return "[]", fmt.Errorf("marshal review history: %w", err)
	}
	return string(data), nil
}

// --- Activity: WriteExecutionLog ---

type WriteExecutionLogParams struct {
	RunID           uuid.UUID
	NodeExecutionID uuid.UUID
	Level           string
	Message         string
}

func (a *Activities) WriteExecutionLog(ctx context.Context, params WriteExecutionLogParams) error {
	if err := guardRun(ctx, params.RunID); err != nil {
		return err
	}
	return a.client.WriteExecutionLog(ctx, params.RunID, params.NodeExecutionID, params.Level, params.Message)
}

// --- Activity: UpdateNodeExecutionStatus ---

type UpdateNodeExecStatusParams struct {
	RunID           uuid.UUID
	NodeExecutionID uuid.UUID
	Status          string
	Result          *string
	ErrorMessage    *string
	ArtifactRefs    *string // NEW: nil → field omitted from PUT body; set for completed human gate nodes
}

func (a *Activities) UpdateNodeExecutionStatus(ctx context.Context, params UpdateNodeExecStatusParams) error {
	if err := guardRun(ctx, params.RunID); err != nil {
		return err
	}
	return a.client.UpdateNodeExecution(ctx, params.RunID, params.NodeExecutionID, state.UpdateNodeExecutionParams{
		Status:       params.Status,
		Result:       params.Result,
		ErrorMessage: params.ErrorMessage,
		ArtifactRefs: params.ArtifactRefs, // NEW
	})
}

type DeleteAgentJobParams struct {
	RunID           uuid.UUID
	NodeExecutionID uuid.UUID
}

func (a *Activities) DeleteAgentJob(_ context.Context, _ DeleteAgentJobParams) error {
	return fmt.Errorf("activity dispatched to Worker task queue, not the orchestrator")
}

type FetchPodLogsParams struct {
	RunID           uuid.UUID
	NodeExecutionID uuid.UUID
	TailLines       int
}

func (a *Activities) FetchPodLogs(_ context.Context, _ FetchPodLogsParams) (string, error) {
	return "", fmt.Errorf("activity dispatched to Worker task queue, not the orchestrator")
}

// --- Activity: GetNodeDecision ---

type GetNodeDecisionParams struct {
	RunID           uuid.UUID
	NodeExecutionID uuid.UUID
}

func (a *Activities) GetNodeDecision(ctx context.Context, params GetNodeDecisionParams) (string, error) {
	if err := guardRun(ctx, params.RunID); err != nil {
		return "", err
	}
	return a.client.GetNodeDecision(ctx, params.RunID, params.NodeExecutionID)
}

// --- Activity: SetNodeDecision ---

type SetNodeDecisionParams struct {
	RunID           uuid.UUID
	NodeExecutionID uuid.UUID
	Decision        string
}

func (a *Activities) SetNodeDecision(ctx context.Context, params SetNodeDecisionParams) error {
	if err := guardRun(ctx, params.RunID); err != nil {
		return err
	}
	return a.client.SetNodeDecision(ctx, params.RunID, params.NodeExecutionID, params.Decision)
}

// --- Activity: SetTraversedEdges ---

type SetTraversedEdgesParams struct {
	RunID           uuid.UUID
	NodeExecutionID uuid.UUID
	EdgeIDs         []uuid.UUID
}

func (a *Activities) SetTraversedEdges(ctx context.Context, params SetTraversedEdgesParams) error {
	if err := guardRun(ctx, params.RunID); err != nil {
		return err
	}
	return a.client.SetTraversedEdges(ctx, params.RunID, params.NodeExecutionID, params.EdgeIDs)
}

// --- Activity: DeleteStaleBranches ---

// DeleteStaleBranchesParams identifies the run whose per-repo run branches should be best-effort
// deleted once it has reached "completed" (see DAGExecutorWorkflow's Step 6).
type DeleteStaleBranchesParams struct {
	RunID uuid.UUID
}

func (a *Activities) DeleteStaleBranches(ctx context.Context, params DeleteStaleBranchesParams) error {
	if err := guardRun(ctx, params.RunID); err != nil {
		return err
	}
	return a.client.CleanupBranches(ctx, params.RunID)
}
