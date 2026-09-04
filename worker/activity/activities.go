// Package activity runs the agent-step Temporal activities against the workload API — the
// only application surface a Worker touches. Everything else an agent step needs (execution
// logs, review history, decisions, predecessor artifacts) stays with the orchestrator's own
// activities, which keep serving in-flight runs against the same API server.
package activity

import (
	"context"
	"fmt"
	"log/slog"
	"regexp"
	"sort"
	"strings"
	"time"

	"github.com/google/uuid"
	temporalactivity "go.temporal.io/sdk/activity"

	"github.com/dangtrivan15/choruskube/worker/callback"
	"github.com/dangtrivan15/choruskube/worker/executor"
	"github.com/dangtrivan15/choruskube/worker/workload"
)

// NOTE: This package uses temporalactivity.ErrResultPending from the Temporal SDK
// (go.temporal.io/sdk/activity) to signal async activity completion.
// Do NOT define a custom ErrResultPending — the SDK's sentinel is intercepted
// at the framework level to keep the activity "open" in Temporal.

// workloadClient is the subset of *workload.Client this package calls, declared here at the
// point of use so a test can inject a fake without a real HTTP server. *workload.Client
// satisfies it unchanged.
type workloadClient interface {
	CreateWorkload(ctx context.Context, params workload.CreateWorkloadParams) (*workload.CreateWorkloadResponse, error)
	CleanupWorkload(ctx context.Context, runID, nodeExecID uuid.UUID) error
	GetWorkloadLogs(ctx context.Context, runID, nodeExecID uuid.UUID, tailLines int) (string, error)
	PrepareWorkload(ctx context.Context, params workload.PrepareParams) (*workload.PrepareResponse, error)
	CompleteWorkload(ctx context.Context, params workload.CompleteParams) error
}

var _ workloadClient = (*workload.Client)(nil)

// Activities runs agent-step activities against a workload.Client.
type Activities struct {
	client workloadClient
	// executor, hashCache and pending are nil in legacy mode (constructed via New): with
	// executor nil, ExecuteAINodeFromSnapshot delegates workload creation to the API server
	// instead of running it locally.
	executor  executor.Executor
	hashCache *callback.HashCache
	Pending   *PendingCache
	resolver  *templateResolver
	// CallbackURL is the endpoint agent pods POST their results to. Left empty, the agent
	// pod launches with no way to report back, and the activity hangs until StartToClose
	// instead of failing — ExecuteAINodeFromSnapshot rejects an empty value up front instead.
	CallbackURL string
	// APIServerURL is embedded in agent config, including as the base of github_token_url.
	// Left empty, github_token_url becomes a relative path the agent cannot call —
	// ExecuteAINodeFromSnapshot rejects an empty value up front instead.
	APIServerURL string
}

// New returns Activities backed by client, delegating workload creation to the API server
// (legacy mode — kept for the transition until every Worker runs an Executor). Set CallbackURL
// and APIServerURL on the result before registering it with a Temporal worker.
func New(client *workload.Client) *Activities {
	return &Activities{client: client, resolver: newTemplateResolver()}
}

// NewWithExecutor returns Activities that run each workload locally through exec instead of
// delegating creation to the API server: ExecuteAINodeFromSnapshot calls prepare to resolve
// credentials and identity, launches the workload itself, then calls complete to report the
// result back. cache is populated with each execution's job-secret hash so the Worker's own
// callback server can authenticate the agent's completion POST without a network round trip.
// Set CallbackURL and APIServerURL on the result before registering it with a Temporal worker.
func NewWithExecutor(client workloadClient, exec executor.Executor, cache *callback.HashCache) *Activities {
	return &Activities{
		client:    client,
		executor:  exec,
		hashCache: cache,
		Pending:   NewPendingCache(),
		resolver:  newTemplateResolver(),
	}
}


// --- Activity: ExecuteAINodeFromSnapshot ---

type ExecuteAINodeFromSnapshotParams struct {
	NodeExecutionID uuid.UUID
	RunID           uuid.UUID
	TemplateNodeID  uuid.UUID
	ExecutorType    string // "ai" or "script"
	PromptTemplate  string
	InputArtifacts  map[string]string
	// RequiredInputArtifacts names the InputArtifacts keys whose absence must fail the node.
	// Everything else is best-effort: several declarations legitimately reference a prior
	// iteration that does not exist on iteration 1.
	RequiredInputArtifacts []string
	Variables              map[string]string
	Iteration              int
	RepoURL                string
	WorkingBranch          string
	Command                string                   // for script executor
	OrgSlug                string                   // Org slug for object storage path isolation; empty = legacy paths
	NeedDecision           bool                     // true if node has conditional edges and is AI type
	NeedsPR                bool                     // true if node must open/register a PR before finishing (config_overrides.needs_pr)
	OutputSpec             string                   // JSON string describing required output files; "" or "{}" = no enforcement
	SupervisorLabel        string                   // label of the template's routing-hub node; "" = template declares none
	Repos                  []map[string]interface{} `json:"repos,omitempty"`
	Model                  string                   `json:"model,omitempty"`  // optional override; empty = agent default
	Effort                 string                   `json:"effort,omitempty"` // optional override; empty = agent default
	// Per-node turn/retry budget, as configured strings. Empty = agent default
	// (the entrypoint's own 100 turns / 3 attempts); the entrypoint validates
	// any value it is given.
	MaxTurns   string `json:"max_turns,omitempty"`
	MaxRetries string `json:"max_retries,omitempty"`
	// Triggering Task's identity, broadcast into config.json's task_context
	// for every node execution in a task-triggered run. TaskID == "" means the run wasn't
	// started from a Task; StoryID/EpicID may independently be "" if that level no longer
	// resolves even though TaskID is set.
	TaskID     string
	TaskTitle  string
	StoryID    string
	StoryTitle string
	EpicID     string
	EpicTitle  string
	// OpenBlockers lists the triggering Task's own direct, not-yet-done incoming blocking
	// edges, threaded into config.json's task_context.open_blockers. Empty
	// (nil or zero-length) omits the key entirely, matching how task_context itself is
	// omitted when TaskID == "".
	OpenBlockers []OpenBlockerParam
	// Set only when this iteration resumes a session parked by a previous one.
	// The entrypoint restores the transcript and runs `claude --resume`; empty
	// means start a fresh session.
	SessionID           string `json:"session_id,omitempty"`
	SessionArtifactPath string `json:"session_artifact_path,omitempty"`
}

// OpenBlockerParam mirrors one entry of state.SnapshotOpenBlocker, flattened into the
// activity's plain-string param shape (same convention as TaskID/TaskTitle/... above).
type OpenBlockerParam struct {
	ItemType string
	ItemID   string
	Title    string
	Status   string
}

// CallbackResult is the result type returned when the activity is completed externally
type CallbackResult struct {
	Status       string `json:"status"`
	Result       string `json:"result"`
	ArtifactRefs string `json:"artifact_refs"`
	ErrorMessage string `json:"error_message"`
	// Set only when Status == "rate_limited". ResumeAt is when the org's Claude
	// quota resets; the workflow sleeps until then rather than failing the node.
	// SessionArtifactPath is empty when the transcript upload failed, in which
	// case the next iteration starts a fresh Claude session.
	ResumeAt            time.Time `json:"resume_at"`
	SessionID           string    `json:"session_id"`
	SessionArtifactPath string    `json:"session_artifact_path"`
}

func (a *Activities) ExecuteAINodeFromSnapshot(ctx context.Context, params ExecuteAINodeFromSnapshotParams) (CallbackResult, error) {
	if a.CallbackURL == "" || a.APIServerURL == "" {
		return CallbackResult{}, fmt.Errorf("activities: CallbackURL and APIServerURL must both be set before executing")
	}
	runID, err := runIDOf(ctx)
	if err != nil {
		return CallbackResult{}, err
	}
	// The parameters and the workflow that scheduled them must name the same run. They cannot
	// disagree unless something built this activity wrongly, and continuing would send a pair the
	// server refuses -- as a node failure with no explanation rather than this one.
	if runID != params.RunID {
		return CallbackResult{}, fmt.Errorf("activity params name run %s but its workflow is run %s", params.RunID, runID)
	}

	// Resolve prompt template
	vars := params.Variables
	if vars == nil {
		vars = map[string]string{}
	}
	resolvedPrompt, err := a.resolver.resolve(params.PromptTemplate, vars)
	if err != nil {
		return CallbackResult{}, fmt.Errorf("resolve prompt: %w", err)
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

	configJSON := a.buildConfigJSON(params, resolvedPrompt)

	if a.executor != nil {
		return a.executeLocally(ctx, runID, params, configJSON)
	}
	return a.executeDelegated(ctx, params, configJSON)
}

// buildConfigJSON assembles the agent pod's config.json content — everything the entrypoint
// reads to run one node execution — with object storage paths prefixed by OrgSlug. Output paths
// are keyed by NodeExecutionID so each iteration of a self-looping or re-executed node owns its
// own prefix instead of overwriting a prior iteration's artifacts.
func (a *Activities) buildConfigJSON(params ExecuteAINodeFromSnapshotParams, resolvedPrompt string) map[string]interface{} {
	vars := params.Variables
	if vars == nil {
		vars = map[string]string{}
	}
	baseOutputPath := fmt.Sprintf("runs/%s/%s/out/", params.RunID, params.NodeExecutionID)
	outputPath := prefixPath(params.OrgSlug, baseOutputPath)
	configJSON := map[string]interface{}{
		"node_execution_id": params.NodeExecutionID.String(),
		"run_id":            params.RunID.String(),
		"prompt":            resolvedPrompt,
		"callback_url":      a.CallbackURL,
		"output_path":       outputPath,
		"input_artifacts":   params.InputArtifacts,
		"executor_type":     params.ExecutorType,
		"api_server_url":    a.APIServerURL,
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
		a.APIServerURL, params.RunID, params.NodeExecutionID)
	// Build run log path with org prefix
	baseRunLogPath := fmt.Sprintf("runs/%s/run_log.md", params.RunID)
	runLogPath := prefixPath(params.OrgSlug, baseRunLogPath)
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
	// Emitted only when the template declares a Supervisor, so config.json keeps its exact
	// current shape for every other template — and the entrypoint's escalation block, which is
	// gated on this key, stays silent for them.
	if params.SupervisorLabel != "" {
		configJSON["supervisor"] = map[string]string{
			"label": params.SupervisorLabel,
			"name":  "Supervisor",
		}
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
	if params.SessionID != "" {
		configJSON["session_id"] = params.SessionID
		configJSON["session_artifact_path"] = params.SessionArtifactPath
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

	return configJSON
}

// executeDelegated is the legacy path (a.executor == nil): the API server resolves
// infrastructure details from configJSON and launches the container itself.
func (a *Activities) executeDelegated(ctx context.Context, params ExecuteAINodeFromSnapshotParams, configJSON map[string]interface{}) (CallbackResult, error) {
	_, err := a.client.CreateWorkload(ctx, workload.CreateWorkloadParams{
		RunID:          params.RunID,
		NodeExecID:     params.NodeExecutionID,
		TemplateNodeID: params.TemplateNodeID,
		ConfigJSON:     configJSON,
	})
	if err != nil {
		return CallbackResult{}, fmt.Errorf("create workload: %w", err)
	}

	return CallbackResult{}, temporalactivity.ErrResultPending
}

// executeLocally is the ported path (a.executor != nil): this Worker resolves credentials via
// prepare, launches the workload itself through a.executor, then reports the result via
// complete instead of asking the API server to launch it.
func (a *Activities) executeLocally(ctx context.Context, runID uuid.UUID, params ExecuteAINodeFromSnapshotParams, configJSON map[string]interface{}) (CallbackResult, error) {
	prep, err := a.client.PrepareWorkload(ctx, workload.PrepareParams{
		RunID:          runID,
		NodeExecID:     params.NodeExecutionID,
		TemplateNodeID: params.TemplateNodeID,
		ConfigJSON:     configJSON,
	})
	if err != nil {
		return CallbackResult{}, fmt.Errorf("prepare workload: %w", err)
	}

	// A fresh secret per execution: the workload proves its identity by presenting it back on
	// its completion callback, verified against the hash cached below.
	secret, _, err := executor.GenerateJobSecret()
	if err != nil {
		return CallbackResult{}, fmt.Errorf("generate job secret: %w", err)
	}

	execParams := executor.ExecutionParams{
		RunID:           runID,
		NodeExecutionID: params.NodeExecutionID,
		NodeID:          params.TemplateNodeID,
		Image:           prep.Image,
		JobSecret:       secret,
		Credentials: executor.NodeCredentials{
			GitHubTokenURL:   prep.GitHubTokenURL,
			ClaudeOAuthToken: prep.ClaudeOAuthToken,
		},
		ConfigJSON:   configJSON,
		CallbackURL:  a.CallbackURL,
		EnableDocker: prep.EnableDocker,
		Identity: executor.ExecutionIdentity{
			Namespace:      prep.Namespace,
			ServiceAccount: prep.ServiceAccount,
		},
	}
	if prep.Registry != nil {
		execParams.Credentials.Registry = &executor.RegistryCredentials{
			Host:     prep.Registry.Host,
			Username: prep.Registry.Username,
			Password: prep.Registry.Password,
		}
	}

	result, err := a.executor.Execute(ctx, execParams)
	if err != nil {
		return CallbackResult{}, fmt.Errorf("execute: %w", err)
	}

	// Both caches are populated before complete is even called: the workload can call back the
	// moment it starts, racing this activity's own report to the API server -- CompleteWorkload
	// below is a network round trip a fast (e.g. script) workload can easily win.
	a.hashCache.Put(params.NodeExecutionID, result.JobSecretHash)
	// Captured now, not resolved later from params: the agent's completion and heartbeat
	// requests carry only NodeExecutionID, so this is the one place that also has Temporal's
	// own addressing for the activity they need to reach.
	info := activityInfo(ctx)
	a.Pending.Put(params.NodeExecutionID, PendingCompletion{
		Namespace:  info.Namespace,
		TaskQueue:  info.TaskQueue,
		WorkflowID: info.WorkflowExecution.ID,
		ActivityID: info.ActivityID,
	})

	if err := a.client.CompleteWorkload(ctx, workload.CompleteParams{
		RunID:         runID,
		NodeExecID:    params.NodeExecutionID,
		PodName:       result.PodName,
		JobSecretHash: result.JobSecretHash,
	}); err != nil {
		return CallbackResult{}, fmt.Errorf("complete workload: %w", err)
	}

	slog.Info("agent launched", "node_execution_id", params.NodeExecutionID, "pod_name", result.PodName)

	return CallbackResult{}, temporalactivity.ErrResultPending
}

// --- Activity: DeleteAgentJob ---

type DeleteAgentJobParams struct {
	NodeExecutionID uuid.UUID
}

func (a *Activities) DeleteAgentJob(ctx context.Context, params DeleteAgentJobParams) error {
	if a.executor != nil {
		return a.executor.Cleanup(ctx, params.NodeExecutionID)
	}
	runID, err := runIDOf(ctx)
	if err != nil {
		return err
	}
	return a.client.CleanupWorkload(ctx, runID, params.NodeExecutionID)
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
	if a.executor != nil {
		return a.executor.GetLogs(ctx, params.NodeExecutionID, tailLines)
	}
	runID, err := runIDOf(ctx)
	if err != nil {
		return "", err
	}
	return a.client.GetWorkloadLogs(ctx, runID, params.NodeExecutionID, tailLines)
}

// prefixPath namespaces an object storage key by org slug, so each organization's runs live
// under separate prefixes; orgSlug == "" keeps the legacy unprefixed key.
func prefixPath(orgSlug, key string) string {
	if orgSlug == "" {
		return key
	}
	return orgSlug + "/" + key
}

// varPattern matches {variable.name} style placeholders.
var varPattern = regexp.MustCompile(`\{([a-zA-Z_][a-zA-Z0-9_.]*)\}`)

// templateResolver substitutes {variable} placeholders in a prompt template with values from
// a map. Double-brace sequences ({{...}}) are treated as literal text and are not substituted.
type templateResolver struct{}

func newTemplateResolver() *templateResolver {
	return &templateResolver{}
}

// resolve substitutes all {variable} placeholders in template with the corresponding values
// from vars and returns an error listing any variable referenced but not present in vars.
func (r *templateResolver) resolve(template string, vars map[string]string) (string, error) {
	var missingVars []string

	// Protect double-brace sequences by replacing them with sentinels that
	// contain no braces, so the variable regex cannot match inside them.
	const openSentinel = "\x00OPEN\x00"
	const closeSentinel = "\x00CLOSE\x00"
	protected := strings.ReplaceAll(template, "{{", openSentinel)
	protected = strings.ReplaceAll(protected, "}}", closeSentinel)

	result := varPattern.ReplaceAllStringFunc(protected, func(match string) string {
		key := match[1 : len(match)-1]
		if val, ok := vars[key]; ok {
			return val
		}
		missingVars = append(missingVars, key)
		return match
	})

	result = strings.ReplaceAll(result, openSentinel, "{{")
	result = strings.ReplaceAll(result, closeSentinel, "}}")

	if len(missingVars) > 0 {
		return "", fmt.Errorf("unresolved variables: %v", missingVars)
	}
	return result, nil
}
