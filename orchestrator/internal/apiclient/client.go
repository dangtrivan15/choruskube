package apiclient

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"time"

	"github.com/google/uuid"
	"go.temporal.io/sdk/temporal"

	"github.com/dangtrivan15/choruskube/orchestrator/internal/state"
)

// Client replaces direct Postgres access with HTTP calls to the API server.
type Client struct {
	baseURL     string
	bearerToken string // Optional Bearer token for authentication
	httpClient  *http.Client
	// untimedClient has no client-side timeout, for calls bounded by the caller's ctx deadline.
	untimedClient *http.Client
}

func NewClient(baseURL string) *Client {
	return &Client{
		baseURL: baseURL,
		httpClient: &http.Client{
			Timeout: 30 * time.Second,
		},
		untimedClient: &http.Client{},
	}
}

// NewAuthenticatedClient creates a client that injects Bearer token on every request.
func NewAuthenticatedClient(baseURL, bearerToken string) *Client {
	return &Client{
		baseURL:     baseURL,
		bearerToken: bearerToken,
		httpClient: &http.Client{
			Timeout: 30 * time.Second,
		},
		untimedClient: &http.Client{},
	}
}

// APIError represents an HTTP error response from the API server.
// Callers can use errors.As to extract the status code for specific handling.
type APIError struct {
	StatusCode int
	Body       string
}

func (e *APIError) Error() string {
	return fmt.Sprintf("api error %d: %s", e.StatusCode, e.Body)
}

func (c *Client) do(hc *http.Client, req *http.Request) ([]byte, error) {
	resp, err := hc.Do(req)
	if err != nil {
		return nil, fmt.Errorf("api request failed: %w", err)
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("read response body: %w", err)
	}
	if resp.StatusCode >= 400 {
		return nil, &APIError{StatusCode: resp.StatusCode, Body: string(body)}
	}
	return body, nil
}

func (c *Client) doJSON(ctx context.Context, method, path string, reqBody interface{}) ([]byte, error) {
	return c.doJSONWith(ctx, c.httpClient, method, path, reqBody)
}

func (c *Client) doJSONWith(ctx context.Context, hc *http.Client, method, path string, reqBody interface{}) ([]byte, error) {
	var bodyReader io.Reader
	if reqBody != nil {
		data, err := json.Marshal(reqBody)
		if err != nil {
			return nil, fmt.Errorf("marshal request body: %w", err)
		}
		bodyReader = bytes.NewReader(data)
	}
	req, err := http.NewRequestWithContext(ctx, method, c.baseURL+path, bodyReader)
	if err != nil {
		return nil, fmt.Errorf("create request: %w", err)
	}
	if reqBody != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if c.bearerToken != "" {
		req.Header.Set("Authorization", "Bearer "+c.bearerToken)
	}
	return c.do(hc, req)
}

// --- Node Execution ---

type createNodeExecRequest struct {
	TemplateNodeID uuid.UUID `json:"templateNodeId"`
	GraphVersion   int       `json:"graphVersion"`
	Iteration      int       `json:"iteration"`
	Label          string    `json:"label"`
}

type nodeExecResponse struct {
	ID             uuid.UUID  `json:"id"`
	TemplateNodeID uuid.UUID  `json:"templateNodeId"`
	Status         string     `json:"status"`
	Result         *string    `json:"result"`
	PodName        *string    `json:"podName"`
	Iteration      int        `json:"iteration"`
	StartedAt      *time.Time `json:"startedAt"`
	CompletedAt    *time.Time `json:"completedAt"`
	ErrorMessage   *string    `json:"errorMessage"`
	GraphVersion   int        `json:"graphVersion"`
	ArtifactRefs   string     `json:"artifactRefs"`
}

func toNodeExecution(r *nodeExecResponse, runID uuid.UUID) *state.NodeExecution {
	return &state.NodeExecution{
		ID:             r.ID,
		WorkflowRunID:  runID,
		TemplateNodeID: r.TemplateNodeID,
		Status:         r.Status,
		Result:         r.Result,
		ArtifactRefs:   r.ArtifactRefs,
		PodName:        r.PodName,
		Iteration:      r.Iteration,
		GraphVersion:   r.GraphVersion,
		StartedAt:      r.StartedAt,
		CompletedAt:    r.CompletedAt,
		ErrorMessage:   r.ErrorMessage,
	}
}

func (c *Client) CreateNodeExecution(ctx context.Context, runID uuid.UUID, params state.CreateNodeExecutionParams) (*state.NodeExecution, error) {
	body := createNodeExecRequest{
		TemplateNodeID: params.TemplateNodeID,
		GraphVersion:   params.GraphVersion,
		Iteration:      params.Iteration,
		Label:          params.Label,
	}
	resp, err := c.doJSON(ctx, http.MethodPost, fmt.Sprintf("/internal/runs/%s/node-executions", runID), body)
	if err != nil {
		return nil, fmt.Errorf("create node execution: %w", err)
	}
	var result nodeExecResponse
	if err := json.Unmarshal(resp, &result); err != nil {
		return nil, fmt.Errorf("unmarshal node execution response: %w", err)
	}
	return toNodeExecution(&result, runID), nil
}

type updateNodeExecRequest struct {
	Status        string  `json:"status"`
	Result        *string `json:"result,omitempty"`
	ArtifactRefs  *string `json:"artifactRefs,omitempty"`
	PodName       *string `json:"podName,omitempty"`
	JobSecretHash *string `json:"jobSecretHash,omitempty"`
	ErrorMessage  *string `json:"errorMessage,omitempty"`
	FinalizeMode  *string `json:"finalizeMode,omitempty"`
}

func (c *Client) UpdateNodeExecution(ctx context.Context, runID, execID uuid.UUID, params state.UpdateNodeExecutionParams) error {
	body := updateNodeExecRequest{
		Status:        params.Status,
		Result:        params.Result,
		ArtifactRefs:  params.ArtifactRefs,
		PodName:       params.PodName,
		JobSecretHash: params.JobSecretHash,
		ErrorMessage:  params.ErrorMessage,
		FinalizeMode:  params.FinalizeMode,
	}
	_, err := c.doJSON(ctx, http.MethodPut, fmt.Sprintf("/internal/runs/%s/node-executions/%s/status", runID, execID), body)
	if err != nil {
		return fmt.Errorf("update node execution: %w", err)
	}
	return nil
}

func (c *Client) GetNodeExecution(ctx context.Context, runID, execID uuid.UUID) (*state.NodeExecution, error) {
	resp, err := c.doJSON(ctx, http.MethodGet, fmt.Sprintf("/internal/runs/%s/node-executions/%s", runID, execID), nil)
	if err != nil {
		return nil, fmt.Errorf("get node execution: %w", err)
	}
	var result nodeExecResponse
	if err := json.Unmarshal(resp, &result); err != nil {
		return nil, fmt.Errorf("unmarshal node execution: %w", err)
	}
	return toNodeExecution(&result, runID), nil
}

func (c *Client) GetNodeExecutionsByRun(ctx context.Context, runID uuid.UUID) ([]*state.NodeExecution, error) {
	resp, err := c.doJSON(ctx, http.MethodGet, fmt.Sprintf("/internal/runs/%s/node-executions", runID), nil)
	if err != nil {
		return nil, fmt.Errorf("get node executions by run: %w", err)
	}
	var results []nodeExecResponse
	if err := json.Unmarshal(resp, &results); err != nil {
		return nil, fmt.Errorf("unmarshal node executions: %w", err)
	}
	execs := make([]*state.NodeExecution, len(results))
	for i := range results {
		execs[i] = toNodeExecution(&results[i], runID)
	}
	return execs, nil
}

// PlacementDecision is the api-server's answer to whether a node execution may be
// dispatched to the run's task queue. Allowed:false at 200 (never a 4xx) is a real
// decision, not an outage, so callers must not conflate it with a transport failure.
type PlacementDecision struct {
	Allowed bool   `json:"allowed"`
	Reason  string `json:"reason"`
}

// CheckNodePlacement asks the api-server whether nodeExecID may run on the queue its
// run was dispatched to. A non-2xx response returns an error, same as every other
// call through doJSON — this method never manufactures a decision on its own.
func (c *Client) CheckNodePlacement(ctx context.Context, runID, nodeExecID uuid.UUID) (PlacementDecision, error) {
	path := fmt.Sprintf("/internal/runs/%s/node-executions/%s/placement-check", runID, nodeExecID)
	resp, err := c.doJSON(ctx, http.MethodPost, path, nil)
	if err != nil {
		return PlacementDecision{}, fmt.Errorf("check node placement: %w", err)
	}
	var result PlacementDecision
	if err := json.Unmarshal(resp, &result); err != nil {
		return PlacementDecision{}, fmt.Errorf("unmarshal placement decision: %w", err)
	}
	return result, nil
}

// --- Workflow Run ---

// GetWorkflowRunStatus returns the current status string for a workflow run.
// Used by the reconciler to check if a run is still active.
func (c *Client) GetWorkflowRunStatus(ctx context.Context, runID uuid.UUID) (string, error) {
	resp, err := c.doJSON(ctx, http.MethodGet, fmt.Sprintf("/internal/runs/%s/status", runID), nil)
	if err != nil {
		return "", fmt.Errorf("get workflow run status: %w", err)
	}
	var result struct {
		Status string `json:"status"`
	}
	if err := json.Unmarshal(resp, &result); err != nil {
		return "", fmt.Errorf("unmarshal workflow run status: %w", err)
	}
	return result.Status, nil
}

func (c *Client) UpdateWorkflowRunStatus(ctx context.Context, runID uuid.UUID, status string) error {
	body := map[string]string{"status": status}
	_, err := c.doJSON(ctx, http.MethodPut, fmt.Sprintf("/internal/runs/%s/status", runID), body)
	if err != nil {
		return fmt.Errorf("update workflow run status: %w", err)
	}
	return nil
}

func (c *Client) UpdateWorkflowRunExternalID(ctx context.Context, runID uuid.UUID, externalRunID string) error {
	body := map[string]string{"externalRunId": externalRunID}
	_, err := c.doJSON(ctx, http.MethodPut, fmt.Sprintf("/internal/runs/%s/external-run-id", runID), body)
	if err != nil {
		return fmt.Errorf("update workflow run external ID: %w", err)
	}
	return nil
}

func (c *Client) GetGraphRuntime(ctx context.Context, runID uuid.UUID) (string, error) {
	resp, err := c.doJSON(ctx, http.MethodGet, fmt.Sprintf("/internal/runs/%s/graph-runtime", runID), nil)
	if err != nil {
		return "", fmt.Errorf("get graph runtime snapshot: %w", err)
	}
	return string(resp), nil
}

// CleanupBranches asks the API server to best-effort delete this run's per-repo run branch for
// every repo whose branch is not ahead of its default branch. Called once a run reaches
// "completed"; failures are the caller's to log and ignore, never to fail the run over.
func (c *Client) CleanupBranches(ctx context.Context, runID uuid.UUID) error {
	_, err := c.doJSON(ctx, http.MethodPost, fmt.Sprintf("/internal/runs/%s/cleanup-branches", runID), nil)
	if err != nil {
		return fmt.Errorf("cleanup branches: %w", err)
	}
	return nil
}

// --- Execution Log ---

func (c *Client) WriteExecutionLog(ctx context.Context, runID, nodeExecID uuid.UUID, level, message string) error {
	body := map[string]string{"level": level, "message": message}
	_, err := c.doJSON(ctx, http.MethodPost, fmt.Sprintf("/internal/runs/%s/node-executions/%s/logs", runID, nodeExecID), body)
	if err != nil {
		return fmt.Errorf("write execution log: %w", err)
	}
	return nil
}

// --- Review History ---

type createReviewHistoryRequest struct {
	LoopGroup       string    `json:"loopGroup"`
	Iteration       int       `json:"iteration"`
	ReviewerType    string    `json:"reviewerType"`
	ArtifactRefs    string    `json:"artifactRefs"`
	NodeExecutionID uuid.UUID `json:"nodeExecutionId"`
}

func (c *Client) CreateReviewHistory(ctx context.Context, runID uuid.UUID, params state.CreateReviewHistoryParams) error {
	body := createReviewHistoryRequest{
		LoopGroup:       params.LoopGroup,
		Iteration:       params.Iteration,
		ReviewerType:    params.ReviewerType,
		ArtifactRefs:    params.ArtifactRefs,
		NodeExecutionID: params.NodeExecutionID,
	}
	_, err := c.doJSON(ctx, http.MethodPost, fmt.Sprintf("/internal/runs/%s/review-history", runID), body)
	if err != nil {
		return fmt.Errorf("create review history: %w", err)
	}
	return nil
}

// reviewHistoryResponse maps the API server's ReviewHistoryResponse DTO
type reviewHistoryResponse struct {
	ID           uuid.UUID  `json:"id"`
	LoopGroup    string     `json:"loopGroup"`
	Iteration    int        `json:"iteration"`
	ReviewerType string     `json:"reviewerType"`
	Decision     string     `json:"decision"`
	Result       string     `json:"result"`
	Status       string     `json:"status"`
	ArtifactRefs string     `json:"artifactRefs"`
	NodeLabel    string     `json:"nodeLabel"`
	Timestamp    *time.Time `json:"timestamp"`
}

func (c *Client) GetReviewHistory(ctx context.Context, runID uuid.UUID, loopGroup string) ([]*state.ReviewHistory, error) {
	path := fmt.Sprintf("/internal/runs/%s/review-history?loopGroup=%s", runID, loopGroup)
	resp, err := c.doJSON(ctx, http.MethodGet, path, nil)
	if err != nil {
		return nil, fmt.Errorf("get review history: %w", err)
	}
	var results []reviewHistoryResponse
	if err := json.Unmarshal(resp, &results); err != nil {
		return nil, fmt.Errorf("unmarshal review history: %w", err)
	}
	reviews := make([]*state.ReviewHistory, len(results))
	for i, r := range results {
		var ts time.Time
		if r.Timestamp != nil {
			ts = *r.Timestamp
		}
		reviews[i] = &state.ReviewHistory{
			ID:            r.ID,
			WorkflowRunID: runID,
			LoopGroup:     r.LoopGroup,
			Iteration:     r.Iteration,
			ReviewerType:  r.ReviewerType,
			Decision:      r.Decision,
			Result:        r.Result,
			Status:        r.Status,
			NodeLabel:     r.NodeLabel,
			ArtifactRefs:  r.ArtifactRefs,
			Timestamp:     ts,
		}
	}
	return reviews, nil
}

// --- Predecessor Artifacts ---

type predecessorArtifactsResponse struct {
	TemplateNodeID uuid.UUID `json:"templateNodeId"`
	Label          string    `json:"label"`
	ArtifactRefs   string    `json:"artifactRefs"`
	Result         string    `json:"result"`
}

func (c *Client) GetCompletedPredecessors(ctx context.Context, runID, nodeExecID uuid.UUID) ([]*state.PredecessorArtifacts, error) {
	path := fmt.Sprintf("/internal/runs/%s/node-executions/%s/predecessors", runID, nodeExecID)
	resp, err := c.doJSON(ctx, http.MethodGet, path, nil)
	if err != nil {
		return nil, fmt.Errorf("get completed predecessors: %w", err)
	}
	var results []predecessorArtifactsResponse
	if err := json.Unmarshal(resp, &results); err != nil {
		return nil, fmt.Errorf("unmarshal predecessors: %w", err)
	}
	preds := make([]*state.PredecessorArtifacts, len(results))
	for i, r := range results {
		preds[i] = &state.PredecessorArtifacts{
			TemplateNodeID: r.TemplateNodeID,
			Label:          r.Label,
			ArtifactRefs:   r.ArtifactRefs,
			Result:         r.Result,
		}
	}
	return preds, nil
}

// --- Input Artifact Manifest ---

type inputArtifactManifestResponse struct {
	Artifacts map[string]string `json:"artifacts"`
	Required  []string          `json:"required"`
}

// GetInputArtifacts asks the api-server which files this node execution should have materialised
// under /workspace/in/. The api-server owns the resolution because it holds both halves of the
// join: the template's artifact declarations and each source execution's recorded artifact_refs.
func (c *Client) GetInputArtifacts(ctx context.Context, runID, nodeExecID uuid.UUID) (*state.InputArtifactManifest, error) {
	path := fmt.Sprintf("/internal/runs/%s/node-executions/%s/input-artifacts", runID, nodeExecID)
	resp, err := c.doJSON(ctx, http.MethodGet, path, nil)
	if err != nil {
		return nil, fmt.Errorf("get input artifacts: %w", err)
	}
	var result inputArtifactManifestResponse
	if err := json.Unmarshal(resp, &result); err != nil {
		return nil, fmt.Errorf("unmarshal input artifacts: %w", err)
	}
	return &state.InputArtifactManifest{Artifacts: result.Artifacts, Required: result.Required}, nil
}

// --- Workloads (reconciler) ---

// TerminateWorkload stops a running execution.
func (c *Client) TerminateWorkload(ctx context.Context, executionID uuid.UUID) error {
	_, err := c.doJSON(ctx, http.MethodPost, fmt.Sprintf("/internal/workloads/%s/terminate", executionID), nil)
	if err != nil {
		return fmt.Errorf("terminate workload: %w", err)
	}
	return nil
}

// WorkloadExecutionInfo describes a running or completed workload execution.
type WorkloadExecutionInfo struct {
	NodeExecutionID uuid.UUID `json:"nodeExecutionId"`
	RunID           uuid.UUID `json:"runId"`
	ExecutionHandle string    `json:"executionHandle"`
}

// ListWorkloads returns info about all running/completed executions.
func (c *Client) ListWorkloads(ctx context.Context) ([]WorkloadExecutionInfo, error) {
	resp, err := c.doJSON(ctx, http.MethodGet, "/internal/workloads", nil)
	if err != nil {
		return nil, fmt.Errorf("list workloads: %w", err)
	}
	var result []WorkloadExecutionInfo
	if err := json.Unmarshal(resp, &result); err != nil {
		return nil, fmt.Errorf("unmarshal workload list: %w", err)
	}
	return result, nil
}

// --- Job Secret Hash ---

type jobSecretHashResponse struct {
	Hash string `json:"hash"`
}

func (c *Client) GetJobSecretHash(ctx context.Context, runID, nodeExecID uuid.UUID) (string, error) {
	path := fmt.Sprintf("/internal/runs/%s/node-executions/%s/job-secret-hash", runID, nodeExecID)
	resp, err := c.doJSON(ctx, http.MethodGet, path, nil)
	if err != nil {
		return "", fmt.Errorf("get job secret hash: %w", err)
	}
	var result jobSecretHashResponse
	if err := json.Unmarshal(resp, &result); err != nil {
		return "", fmt.Errorf("unmarshal job secret hash: %w", err)
	}
	return result.Hash, nil
}

// --- Node Decision ---

// GetNodeDecision reads the validated decision for a node execution.
// Returns empty string if no decision has been submitted yet.
func (c *Client) GetNodeDecision(ctx context.Context, runID, nodeExecID uuid.UUID) (string, error) {
	path := fmt.Sprintf("/internal/runs/%s/node-executions/%s/decision", runID, nodeExecID)
	body, err := c.doJSON(ctx, http.MethodGet, path, nil)
	if err != nil {
		return "", fmt.Errorf("get node decision: %w", err)
	}
	var resp struct {
		Decision *string `json:"decision"`
	}
	if err := json.Unmarshal(body, &resp); err != nil {
		return "", fmt.Errorf("parse decision response: %w", err)
	}
	if resp.Decision == nil {
		return "", nil
	}
	return *resp.Decision, nil
}

// SetNodeDecision persists a validated decision on a node execution via the
// existing PUT /decision endpoint (submitDecision). The decision was already
// validated by the API server before signaling, so edge validation will pass.
func (c *Client) SetNodeDecision(ctx context.Context, runID, nodeExecID uuid.UUID, decision string) error {
	path := fmt.Sprintf("/internal/runs/%s/node-executions/%s/decision", runID, nodeExecID)
	reqBody := struct {
		Decision string `json:"decision"`
	}{Decision: decision}
	_, err := c.doJSON(ctx, http.MethodPut, path, reqBody)
	if err != nil {
		return fmt.Errorf("set node decision: %w", err)
	}
	return nil
}

// SetTraversedEdges persists which template_edge IDs fired when this node
// completed. The web UI reads this field instead of re-deriving traversal
// from (decision, condition), so the single source of truth is here.
func (c *Client) SetTraversedEdges(ctx context.Context, runID, nodeExecID uuid.UUID, edgeIDs []uuid.UUID) error {
	path := fmt.Sprintf("/internal/runs/%s/node-executions/%s/traversed-edges", runID, nodeExecID)
	if edgeIDs == nil {
		edgeIDs = []uuid.UUID{}
	}
	reqBody := struct {
		EdgeIDs []uuid.UUID `json:"edgeIds"`
	}{EdgeIDs: edgeIDs}
	_, err := c.doJSON(ctx, http.MethodPut, path, reqBody)
	if err != nil {
		return fmt.Errorf("set traversed edges: %w", err)
	}
	return nil
}

// --- Placement ---

// RunNamespace is where a run's workflow lives. Served by an orchestrator-tier
// endpoint: it is deliberately not on a node-executions path, which an agent's
// own secret could reach.
type RunNamespace struct {
	Namespace string `json:"namespace"`
}

// GetRunNamespace returns the namespace a run's workflow was started in. The answer
// is fixed for the life of the run, so callers may cache it indefinitely.
func (c *Client) GetRunNamespace(ctx context.Context, runID uuid.UUID) (RunNamespace, error) {
	resp, err := c.doJSON(ctx, http.MethodGet, fmt.Sprintf("/internal/runs/%s/placement", runID), nil)
	if err != nil {
		return RunNamespace{}, fmt.Errorf("get run namespace: %w", err)
	}
	var result RunNamespace
	if err := json.Unmarshal(resp, &result); err != nil {
		return RunNamespace{}, fmt.Errorf("unmarshal run namespace: %w", err)
	}
	return result, nil
}

// ListNamespaces returns every Temporal namespace this deployment may place a run in.
func (c *Client) ListNamespaces(ctx context.Context) ([]string, error) {
	resp, err := c.doJSON(ctx, http.MethodGet, "/internal/placements", nil)
	if err != nil {
		return nil, fmt.Errorf("list namespaces: %w", err)
	}
	var result struct {
		Namespaces []string `json:"namespaces"`
	}
	if err := json.Unmarshal(resp, &result); err != nil {
		return nil, fmt.Errorf("unmarshal namespaces: %w", err)
	}
	return result.Namespaces, nil
}

// --- Gate finalize ---

// GateFinalizeRejectedType is the application error type of a gate-finalize call the API
// answered with a 400, 404 or 409.
const GateFinalizeRejectedType = "GateFinalizeRejected"

// doGateFinalize posts to a gate-finalize endpoint. A 400, 404 or 409 is the API refusing the
// request itself, so a retry gets the same answer: it comes back as the *ApplicationError itself,
// because the SDK reads an error's retry class from its concrete type and a wrapped one retries.
// ctx's deadline is the only time bound: complete can write a roadmap for longer than the
// default client's timeout, and a cut-off retry would race the write it interrupted.
func (c *Client) doGateFinalize(ctx context.Context, op string, runID, execID uuid.UUID, rest string, reqBody any) ([]byte, error) {
	path := fmt.Sprintf("/internal/runs/%s/node-executions/%s/gate-finalize%s", runID, execID, rest)
	resp, err := c.doJSONWith(ctx, c.untimedClient, http.MethodPost, path, reqBody)
	if err == nil {
		return resp, nil
	}
	var apiErr *APIError
	if errors.As(err, &apiErr) {
		switch apiErr.StatusCode {
		case http.StatusBadRequest, http.StatusNotFound, http.StatusConflict:
			return nil, temporal.NewNonRetryableApplicationError(op, GateFinalizeRejectedType, err)
		}
	}
	return nil, fmt.Errorf("%s: %w", op, err)
}

// The Gate*Item and Gate*Response types are the api-server's GateFinalizeDtos, field for field.
type GateCompareItem struct {
	GitRepoID uuid.UUID `json:"gitRepoId"`
	OwnerRepo string    `json:"ownerRepo"`
	Branch    string    `json:"branch"`
	BaseSha   string    `json:"baseSha"`
}

type GatePRItem struct {
	RowID     uuid.UUID `json:"rowId"`
	OwnerRepo string    `json:"ownerRepo"`
	Number    int       `json:"number"`
	Label     string    `json:"label"`
	PRURL     string    `json:"prUrl"`
}

type GateCompareResultItem struct {
	GitRepoID uuid.UUID `json:"gitRepoId"`
	AheadBy   int       `json:"aheadBy"`
	BaseGone  bool      `json:"baseGone"`
}

type GatePRResultItem struct {
	RowID    uuid.UUID  `json:"rowId"`
	Kind     string     `json:"kind"`
	MergedAt *time.Time `json:"mergedAt,omitempty"`
	MergeSha string     `json:"mergeSha,omitempty"`
}

// GatePlanResponse.Outcome is proceed, hold or refused. A null Reason or MergeMethod decodes to "".
type GatePlanResponse struct {
	Outcome     string            `json:"outcome"`
	Reason      string            `json:"reason"`
	Compares    []GateCompareItem `json:"compares"`
	PRs         []GatePRItem      `json:"prs"`
	MergeMethod string            `json:"mergeMethod"`
}

// GateStepResponse.Outcome is completed, held or refused; null fields decode to "".
type GateStepResponse struct {
	Outcome        string `json:"outcome"`
	Result         string `json:"result"`
	AttachmentRefs string `json:"attachmentRefs"`
	Reason         string `json:"reason"`
}

// ClaimHeldApproval takes the gate's held approval for finalizing and returns its id, or "" when
// none applies.
func (c *Client) ClaimHeldApproval(ctx context.Context, runID, execID uuid.UUID) (string, error) {
	resp, err := c.doGateFinalize(ctx, "claim held approval", runID, execID, "/held", nil)
	if err != nil {
		return "", err
	}
	var result struct {
		ApprovalID *uuid.UUID `json:"approvalId"`
	}
	if err := json.Unmarshal(resp, &result); err != nil {
		return "", fmt.Errorf("unmarshal held approval: %w", err)
	}
	if result.ApprovalID == nil {
		return "", nil
	}
	return result.ApprovalID.String(), nil
}

func (c *Client) PlanGateFinalize(ctx context.Context, runID, execID, approvalID uuid.UUID) (GatePlanResponse, error) {
	resp, err := c.doGateFinalize(ctx, "plan gate finalize", runID, execID, "/"+approvalID.String()+"/plan", nil)
	if err != nil {
		return GatePlanResponse{}, err
	}
	var result GatePlanResponse
	if err := json.Unmarshal(resp, &result); err != nil {
		return GatePlanResponse{}, fmt.Errorf("unmarshal gate finalize plan: %w", err)
	}
	return result, nil
}

func (c *Client) HoldGateApproval(ctx context.Context, runID, execID, approvalID uuid.UUID, compares []GateCompareResultItem) (GateStepResponse, error) {
	if compares == nil {
		compares = []GateCompareResultItem{}
	}
	body := struct {
		Compares []GateCompareResultItem `json:"compares"`
	}{Compares: compares}
	return c.gateStep(ctx, "hold gate approval", runID, execID, "/"+approvalID.String()+"/hold", body)
}

func (c *Client) CompleteGateFinalize(ctx context.Context, runID, execID, approvalID uuid.UUID, merges []GatePRResultItem) (GateStepResponse, error) {
	if merges == nil {
		merges = []GatePRResultItem{}
	}
	body := struct {
		Merges []GatePRResultItem `json:"merges"`
	}{Merges: merges}
	return c.gateStep(ctx, "complete gate finalize", runID, execID, "/"+approvalID.String()+"/complete", body)
}

func (c *Client) gateStep(ctx context.Context, op string, runID, execID uuid.UUID, rest string, body any) (GateStepResponse, error) {
	resp, err := c.doGateFinalize(ctx, op, runID, execID, rest, body)
	if err != nil {
		return GateStepResponse{}, err
	}
	var result GateStepResponse
	if err := json.Unmarshal(resp, &result); err != nil {
		return GateStepResponse{}, fmt.Errorf("unmarshal %s response: %w", op, err)
	}
	return result, nil
}

// RefuseGateApproval records the merges the Worker already made, then voids the approval and
// returns the gate to awaiting_human.
func (c *Client) RefuseGateApproval(ctx context.Context, runID, execID, approvalID uuid.UUID, reason string, merges []GatePRResultItem) error {
	body := struct {
		Reason string             `json:"reason"`
		Merges []GatePRResultItem `json:"merges,omitempty"`
	}{Reason: reason, Merges: merges}
	_, err := c.doGateFinalize(ctx, "refuse gate approval", runID, execID, "/"+approvalID.String()+"/refuse", body)
	return err
}
