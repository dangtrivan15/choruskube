package activity

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"sort"
	"strings"

	"github.com/google/uuid"
	"go.temporal.io/sdk/activity"
	"go.temporal.io/sdk/temporal"

	"github.com/dangtrivan15/choruskube/orchestrator/internal/apiclient"
	"github.com/dangtrivan15/choruskube/orchestrator/internal/config"
	"github.com/dangtrivan15/choruskube/orchestrator/internal/objectstore"
	"github.com/dangtrivan15/choruskube/orchestrator/internal/prompt"
	"github.com/dangtrivan15/choruskube/orchestrator/internal/state"
)

// NOTE: This package uses activity.ErrResultPending from the Temporal SDK
// (go.temporal.io/sdk/activity) to signal async activity completion.
// Do NOT define a custom ErrResultPending — the SDK's sentinel is intercepted
// at the framework level to keep the activity "open" in Temporal.

type Activities struct {
	client            *apiclient.Client
	resolver          *prompt.Resolver
	config            *config.Config
	objectStoreClient objectstore.ObjectStore
}

func NewActivities(client *apiclient.Client, resolver *prompt.Resolver, cfg *config.Config, objectStoreClient objectstore.ObjectStore) *Activities {
	return &Activities{client: client, resolver: resolver, config: cfg, objectStoreClient: objectStoreClient}
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

// --- Activity: ExecuteAINode (async completion) ---

type ExecuteAINodeParams struct {
	NodeExecutionID uuid.UUID
	RunID           uuid.UUID
	TemplateNodeID  uuid.UUID
	PromptTemplate  string
	Image           string
	InputArtifacts  map[string]string // key -> object storage path
	Variables       map[string]string // template variables
}

// CallbackResult is the result type returned when the activity is completed externally
type CallbackResult struct {
	Status       string `json:"status"`
	Result       string `json:"result"`
	ArtifactRefs string `json:"artifact_refs"`
	ErrorMessage string `json:"error_message"`
}

func (a *Activities) ExecuteAINode(ctx context.Context, params ExecuteAINodeParams) error {
	// Resolve prompt template
	vars := params.Variables
	if vars == nil {
		vars = map[string]string{}
	}
	resolvedPrompt, err := a.resolver.Resolve(params.PromptTemplate, vars)
	if err != nil {
		return fmt.Errorf("resolve prompt: %w", err)
	}

	// Build config.json content. Output path is keyed by NodeExecutionID so each
	// iteration of a self-looping or re-executed template node owns its own object storage
	// prefix and cannot overwrite a prior iteration's artifacts.
	outputPath := fmt.Sprintf("runs/%s/%s/out/", params.RunID, params.NodeExecutionID)
	configJSON := map[string]interface{}{
		"node_execution_id": params.NodeExecutionID.String(),
		"run_id":            params.RunID.String(),
		"prompt":            resolvedPrompt,
		"callback_url":      a.config.Callback.URL,
		"output_path":       outputPath,
		"input_artifacts":   params.InputArtifacts,
	}

	// Delegate workload creation to the API server.
	// The API server atomically creates the container AND updates the node execution
	// with pod_name, job_secret_hash, and status=running in a single transaction.
	result, err := a.client.CreateWorkload(ctx, apiclient.CreateWorkloadParams{
		RunID:          params.RunID,
		NodeExecID:     params.NodeExecutionID,
		TemplateNodeID: params.TemplateNodeID,
		ConfigJSON:     configJSON,
	})
	if err != nil {
		return fmt.Errorf("create workload: %w", err)
	}

	a.client.WriteExecutionLog(ctx, params.RunID, params.NodeExecutionID, "info",
		fmt.Sprintf("Agent launched: %s", result.ExecutionHandle))

	return activity.ErrResultPending
}

// --- Activity: UpdateWorkflowRunStatus ---

type UpdateRunStatusParams struct {
	RunID  uuid.UUID
	Status string
}

func (a *Activities) UpdateWorkflowRunStatus(ctx context.Context, params UpdateRunStatusParams) error {
	return a.client.UpdateWorkflowRunStatus(ctx, params.RunID, params.Status)
}

// --- Activity: GetGraphRuntime ---

func (a *Activities) GetGraphRuntime(ctx context.Context, runID uuid.UUID) (string, error) {
	return a.client.GetGraphRuntime(ctx, runID)
}

// --- Activity: ExecuteAINodeFromSnapshot (Phase 2) ---

type ExecuteAINodeFromSnapshotParams struct {
	NodeExecutionID uuid.UUID
	RunID           uuid.UUID
	TemplateNodeID  uuid.UUID
	Label           string
	ExecutorType    string // "ai" or "script"
	PromptTemplate  string
	InputArtifacts  map[string]string
	// RequiredInputArtifacts names the InputArtifacts keys whose absence must fail the node.
	// Everything else is best-effort: several declarations legitimately reference a prior
	// iteration that does not exist on iteration 1.
	RequiredInputArtifacts []string
	Variables              map[string]string
	LoopGroup              string // from config_overrides, empty if not a review node
	Iteration              int
	RepoURL                string
	WorkingBranch          string
	Command                string                   // for script executor
	RunLogPath             string                   // Deprecated: kept for Temporal activity history replay; activity builds from OrgSlug + RunID
	OrgSlug                string                   // Org slug for object storage path isolation; empty = legacy paths
	NeedDecision           bool                     // true if node has conditional edges and is AI type
	NeedsPR                bool                     // true if node must open/register a PR before finishing (config_overrides.needs_pr)
	OutputSpec             string                   // JSON string describing required output files; "" or "{}" = no enforcement
	Repos                  []map[string]interface{} `json:"repos,omitempty"`
	Model                  string                   `json:"model,omitempty"`  // optional override; empty = agent default
	Effort                 string                   `json:"effort,omitempty"` // optional override; empty = agent default
	// Per-node turn/retry budget, as configured strings. Empty = agent default
	// (the entrypoint's own 100 turns / 3 attempts); the entrypoint validates
	// any value it is given.
	MaxTurns   string `json:"max_turns,omitempty"`
	MaxRetries string `json:"max_retries,omitempty"`
	// Triggering Task's identity (Decision 1/2/3), broadcast into config.json's task_context
	// for every node execution in a task-triggered run. TaskID == "" means the run wasn't
	// started from a Task; StoryID/EpicID may independently be "" if that level no longer
	// resolves (Caveat 1) even though TaskID is set.
	TaskID     string
	TaskTitle  string
	StoryID    string
	StoryTitle string
	EpicID     string
	EpicTitle  string
	// OpenBlockers lists the triggering Task's own direct, not-yet-done incoming blocking
	// edges (Decision 1/4), threaded into config.json's task_context.open_blockers. Empty
	// (nil or zero-length) omits the key entirely, matching how task_context itself is
	// omitted when TaskID == "".
	OpenBlockers []OpenBlockerParam
}

// OpenBlockerParam mirrors one entry of state.SnapshotOpenBlocker, flattened into the
// activity's plain-string param shape (same convention as TaskID/TaskTitle/... above).
type OpenBlockerParam struct {
	ItemType string
	ItemID   string
	Title    string
	Status   string
}

func (a *Activities) ExecuteAINodeFromSnapshot(ctx context.Context, params ExecuteAINodeFromSnapshotParams) error {
	// Resolve prompt template
	vars := params.Variables
	if vars == nil {
		vars = map[string]string{}
	}
	resolvedPrompt, err := a.resolver.Resolve(params.PromptTemplate, vars)
	if err != nil {
		return fmt.Errorf("resolve prompt: %w", err)
	}

	// Append predecessor artifact annotation to prompt when artifact refs are present.
	// vars from LoadPredecessorInputs use keys "input.{label}.result" (text) and
	// "input.{label}.{filename}" (object storage path). Filter out .result entries.
	//
	// Anything also present in InputArtifacts is left out: the entrypoint materialises
	// those under /workspace/in/{label}/{filename} before the agent starts, so a
	// "download it yourself" instruction would send the agent after a file it already
	// has. The two maps spell the same file differently — "input.{label}.{filename}"
	// here, "{label}/{filename}" there — so compare on the translated key, not the raw
	// one. A filename may itself contain dots, hence the join over parts[2:].
	var artifactLines []string
	for key, val := range params.Variables {
		parts := strings.Split(key, ".")
		// Must have at least 3 parts (input, label, thing) and not end in "result"
		if len(parts) < 3 || parts[0] != "input" || parts[len(parts)-1] == "result" {
			continue
		}
		if _, materialised := params.InputArtifacts[parts[1]+"/"+strings.Join(parts[2:], ".")]; materialised {
			continue
		}
		artifactLines = append(artifactLines, fmt.Sprintf("- %s: %s", key, val))
	}
	if len(artifactLines) > 0 {
		sort.Strings(artifactLines)
		resolvedPrompt += "\n\n---\n**Predecessor Artifacts**" +
			" (download with `artifact get <object-path> <local-path>`):\n" +
			strings.Join(artifactLines, "\n")
	}

	// Append run-level input annotation. These are attachments uploaded by the user
	// at run start (Run Start Dialog) and are bundled into every AI/script node's
	// input_artifacts under the "run_input/" key prefix. Without this annotation the
	// LLM would not know they exist — entrypoint.sh downloads them silently to
	// /workspace/in/run_input/, but only this prompt suffix tells the model to look.
	var runInputLines []string
	for key, val := range params.InputArtifacts {
		if strings.HasPrefix(key, "run_input/") {
			runInputLines = append(runInputLines, fmt.Sprintf("- %s: %s", key, val))
		}
	}
	if len(runInputLines) > 0 {
		sort.Strings(runInputLines)
		resolvedPrompt += "\n\n---\n**Run Inputs**" +
			" (uploaded at run start, downloaded to `/workspace/in/run_input/`):\n" +
			strings.Join(runInputLines, "\n")
	}

	// Build config.json content with org-prefixed object storage paths. Keyed by
	// NodeExecutionID so each iteration owns its own prefix (see ExecuteAINode).
	baseOutputPath := fmt.Sprintf("runs/%s/%s/out/", params.RunID, params.NodeExecutionID)
	outputPath := objectstore.PrefixPath(params.OrgSlug, baseOutputPath)
	configJSON := map[string]interface{}{
		"node_execution_id": params.NodeExecutionID.String(),
		"run_id":            params.RunID.String(),
		"prompt":            resolvedPrompt,
		"callback_url":      a.config.Callback.URL,
		"output_path":       outputPath,
		"input_artifacts":   params.InputArtifacts,
		"executor_type":     params.ExecutorType,
		"api_server_url":    a.config.APIServerURL,
	}
	// Emitted only when non-empty so config.json keeps its existing shape for nodes that
	// declare nothing — the entrypoint treats an absent key as "every input is best-effort".
	if len(params.RequiredInputArtifacts) > 0 {
		configJSON["required_input_artifacts"] = params.RequiredInputArtifacts
	}
	// Add optional fields only if set
	if params.RepoURL != "" {
		configJSON["repo_url"] = params.RepoURL
	}
	if params.WorkingBranch != "" {
		configJSON["working_branch"] = params.WorkingBranch
	}
	if params.Command != "" {
		configJSON["command"] = params.Command
	}
	configJSON["github_token_url"] = fmt.Sprintf("%s/internal/runs/%s/node-executions/%s/github-token",
		a.config.APIServerURL, params.RunID, params.NodeExecutionID)
	// Build run log path with org prefix
	baseRunLogPath := fmt.Sprintf("runs/%s/run_log.md", params.RunID)
	runLogPath := objectstore.PrefixPath(params.OrgSlug, baseRunLogPath)
	configJSON["run_log_path"] = runLogPath
	if params.NeedDecision {
		configJSON["need_decision"] = true
	}
	if params.NeedsPR {
		configJSON["needs_pr"] = true
	}
	if params.OutputSpec != "" && params.OutputSpec != "{}" {
		configJSON["output_spec"] = params.OutputSpec
	}
	if params.Iteration > 0 {
		configJSON["iteration"] = params.Iteration
	}
	if len(params.Repos) > 0 {
		configJSON["repos"] = params.Repos
	} else if tc, ok := vars["run.test_command"]; ok && tc != "" {
		// Single-repo runs don't populate repos[]; expose the run-level test_command at the
		// top level so run-all-tests can find it (the agent's repo content lives directly at
		// /workspace/repo, not under a /workspace/repo/<name>/ subdirectory).
		configJSON["test_command"] = tc
	}
	if params.Model != "" {
		configJSON["model"] = params.Model
	}
	if params.Effort != "" {
		configJSON["effort"] = params.Effort
	}
	// Written only when configured, so config.json keeps its shape for the nodes
	// that set no budget and the agent applies its own defaults.
	if params.MaxTurns != "" {
		configJSON["max_turns"] = params.MaxTurns
	}
	if params.MaxRetries != "" {
		configJSON["max_retries"] = params.MaxRetries
	}
	if params.TaskID != "" {
		taskContext := map[string]interface{}{
			"task_id":     params.TaskID,
			"task_title":  params.TaskTitle,
			"story_id":    params.StoryID,
			"story_title": params.StoryTitle,
			"epic_id":     params.EpicID,
			"epic_title":  params.EpicTitle,
		}
		if len(params.OpenBlockers) > 0 {
			openBlockers := make([]map[string]interface{}, 0, len(params.OpenBlockers))
			for _, b := range params.OpenBlockers {
				openBlockers = append(openBlockers, map[string]interface{}{
					"item_type": b.ItemType,
					"item_id":   b.ItemID,
					"title":     b.Title,
					"status":    b.Status,
				})
			}
			taskContext["open_blockers"] = openBlockers
		}
		configJSON["task_context"] = taskContext
	}

	// Delegate workload creation to the API server.
	result, err := a.client.CreateWorkload(ctx, apiclient.CreateWorkloadParams{
		RunID:          params.RunID,
		NodeExecID:     params.NodeExecutionID,
		TemplateNodeID: params.TemplateNodeID,
		ConfigJSON:     configJSON,
	})
	if err != nil {
		return fmt.Errorf("create workload: %w", err)
	}

	a.client.WriteExecutionLog(ctx, params.RunID, params.NodeExecutionID, "info",
		fmt.Sprintf("Agent launched: %s", result.ExecutionHandle))
	a.client.WriteExecutionLog(ctx, params.RunID, params.NodeExecutionID, "info",
		fmt.Sprintf("Prompt resolved (%d chars)", len(resolvedPrompt)))

	return activity.ErrResultPending
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
			activity.GetLogger(ctx).Warn("skipping artifact_refs for predecessor: unmarshal failed",
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
	return a.client.UpdateNodeExecution(ctx, params.RunID, params.NodeExecutionID, state.UpdateNodeExecutionParams{
		Status:       params.Status,
		Result:       params.Result,
		ErrorMessage: params.ErrorMessage,
		ArtifactRefs: params.ArtifactRefs, // NEW
	})
}

// --- Activity: DeleteAgentJob ---

type DeleteAgentJobParams struct {
	NodeExecutionID uuid.UUID
}

func (a *Activities) DeleteAgentJob(ctx context.Context, params DeleteAgentJobParams) error {
	return a.client.CleanupWorkload(ctx, params.NodeExecutionID)
}

// --- Activity: FetchPodLogs ---

type FetchPodLogsParams struct {
	NodeExecutionID uuid.UUID
	TailLines       int
}

func (a *Activities) FetchPodLogs(ctx context.Context, params FetchPodLogsParams) (string, error) {
	tailLines := params.TailLines
	if tailLines <= 0 {
		tailLines = 50
	}
	return a.client.GetWorkloadLogs(ctx, params.NodeExecutionID, tailLines)
}

// --- Activity: GetNodeDecision ---

type GetNodeDecisionParams struct {
	RunID           uuid.UUID
	NodeExecutionID uuid.UUID
}

func (a *Activities) GetNodeDecision(ctx context.Context, params GetNodeDecisionParams) (string, error) {
	return a.client.GetNodeDecision(ctx, params.RunID, params.NodeExecutionID)
}

// --- Activity: SetNodeDecision ---

type SetNodeDecisionParams struct {
	RunID           uuid.UUID
	NodeExecutionID uuid.UUID
	Decision        string
}

func (a *Activities) SetNodeDecision(ctx context.Context, params SetNodeDecisionParams) error {
	return a.client.SetNodeDecision(ctx, params.RunID, params.NodeExecutionID, params.Decision)
}

// --- Activity: SetTraversedEdges ---

type SetTraversedEdgesParams struct {
	RunID           uuid.UUID
	NodeExecutionID uuid.UUID
	EdgeIDs         []uuid.UUID
}

func (a *Activities) SetTraversedEdges(ctx context.Context, params SetTraversedEdgesParams) error {
	return a.client.SetTraversedEdges(ctx, params.RunID, params.NodeExecutionID, params.EdgeIDs)
}
