// Package activity runs the agent-step Temporal activities against the workload API — the
// only application surface a Worker touches. Everything else an agent step needs (execution
// logs, review history, decisions, predecessor artifacts) stays with the orchestrator's own
// activities, which keep serving in-flight runs against the same API server.
package activity

import (
	"context"
	"fmt"
	"regexp"
	"sort"
	"strings"
	"time"

	"github.com/google/uuid"
	"go.temporal.io/sdk/activity"

	"github.com/dangtrivan15/choruskube/worker/workload"
)

// NOTE: This package uses activity.ErrResultPending from the Temporal SDK
// (go.temporal.io/sdk/activity) to signal async activity completion.
// Do NOT define a custom ErrResultPending — the SDK's sentinel is intercepted
// at the framework level to keep the activity "open" in Temporal.

// Activities runs agent-step activities against a workload.Client.
type Activities struct {
	client   *workload.Client
	resolver *templateResolver
	// CallbackURL is the endpoint agent pods POST their results to. Left empty until the
	// caller sets it, since New's signature is fixed by callers that construct it before any
	// deployment config is available.
	CallbackURL string
	// APIServerURL is embedded in agent config for endpoints the agent calls directly (its
	// GitHub token exchange). Left empty until the caller sets it, for the same reason as
	// CallbackURL.
	APIServerURL string
}

// New returns Activities backed by client. Set CallbackURL and APIServerURL on the result
// before registering it with a Temporal worker.
func New(client *workload.Client) *Activities {
	return &Activities{client: client, resolver: newTemplateResolver()}
}

// --- Activity: ExecuteAINodeFromSnapshot ---

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

	// Build config.json content with org-prefixed object storage paths. Keyed by
	// NodeExecutionID so each iteration owns its own prefix.
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

	// Delegate workload creation to the API server.
	_, err = a.client.CreateWorkload(ctx, workload.CreateWorkloadParams{
		RunID:          params.RunID,
		NodeExecID:     params.NodeExecutionID,
		TemplateNodeID: params.TemplateNodeID,
		ConfigJSON:     configJSON,
	})
	if err != nil {
		return CallbackResult{}, fmt.Errorf("create workload: %w", err)
	}

	return CallbackResult{}, activity.ErrResultPending
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
