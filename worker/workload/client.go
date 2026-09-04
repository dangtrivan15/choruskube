// Package workload calls the API server's Worker workload routes — creating an agent
// container, deleting it, and reading its logs. It is the only application surface a Worker
// touches; every other endpoint (execution logs, review history, decisions, ...) stays behind
// the orchestrator's own client.
package workload

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/google/uuid"
)

// Client calls the API server's Worker workload endpoints.
type Client struct {
	baseURL string
	// credential is read per request, not captured once: the renewal loop replaces the process's
	// credential while requests are in flight, and a captured value would outlive it.
	credential func() string
	hc         *http.Client
}

// NewClient returns a Client that authenticates every request to the API server at baseURL
// with a Bearer token from credential. It panics when credential is nil. hc defaults to a
// 30-second-timeout client when nil — CreateWorkload runs under a 30-minute activity timeout
// with MaximumAttempts: 1, so an unbounded default client would let one hung POST stall a node
// for the full 30 minutes with no retry, instead of failing fast enough for Temporal to retry it.
func NewClient(baseURL string, credential func() string, hc *http.Client) *Client {
	// Left nil, the first request panics inside an activity, where Temporal reports it as a node
	// failure and retries it. Failing here fails the process at the wiring mistake instead.
	if credential == nil {
		panic("workload.NewClient: credential must not be nil")
	}
	if hc == nil {
		hc = &http.Client{Timeout: 30 * time.Second}
	}
	return &Client{baseURL: strings.TrimRight(baseURL, "/"), credential: credential, hc: hc}
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

func (c *Client) do(req *http.Request) ([]byte, error) {
	req.Header.Set("Authorization", "Bearer "+c.credential())
	resp, err := c.hc.Do(req)
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
	return c.do(req)
}

// workloadPath addresses one node execution within the run that owns it. The run id is part of
// the path, not a body field, because the server authorizes the credential against it.
func workloadPath(runID, nodeExecID uuid.UUID) string {
	return fmt.Sprintf("/worker/runs/%s/node-executions/%s/workload", runID, nodeExecID)
}

type createWorkloadRequest struct {
	TemplateNodeID uuid.UUID              `json:"templateNodeId"`
	ConfigJSON     map[string]interface{} `json:"configJson"`
}

// CreateWorkloadResponse is the API server's response to a workload creation request.
type CreateWorkloadResponse struct {
	ExecutionHandle string `json:"executionHandle"`
	JobSecretHash   string `json:"jobSecretHash"`
}

// CreateWorkloadParams contains everything needed to create a workload via the API server.
type CreateWorkloadParams struct {
	RunID          uuid.UUID
	NodeExecID     uuid.UUID
	TemplateNodeID uuid.UUID
	ConfigJSON     map[string]interface{}
}

// CreateWorkload delegates container creation to the API server. The API server
// resolves infrastructure details (image, namespace, secrets, docker config,
// identity) from the stored graph snapshot and atomically creates the container.
func (c *Client) CreateWorkload(ctx context.Context, params CreateWorkloadParams) (*CreateWorkloadResponse, error) {
	body := createWorkloadRequest{
		TemplateNodeID: params.TemplateNodeID,
		ConfigJSON:     params.ConfigJSON,
	}
	path := workloadPath(params.RunID, params.NodeExecID)
	resp, err := c.doJSON(ctx, http.MethodPost, path, body)
	if err != nil {
		return nil, fmt.Errorf("create workload: %w", err)
	}
	var result CreateWorkloadResponse
	if err := json.Unmarshal(resp, &result); err != nil {
		return nil, fmt.Errorf("unmarshal create workload response: %w", err)
	}
	return &result, nil
}

// CleanupWorkload removes all resources associated with a completed execution.
func (c *Client) CleanupWorkload(ctx context.Context, runID, nodeExecID uuid.UUID) error {
	_, err := c.doJSON(ctx, http.MethodDelete, workloadPath(runID, nodeExecID), nil)
	if err != nil {
		return fmt.Errorf("cleanup workload: %w", err)
	}
	return nil
}

// prepareWorkloadRequest mirrors createWorkloadRequest: the API server resolves EnableDocker in
// PrepareResponse from ConfigJSON's executor_type, so prepare must send the same config a create
// call would, not just the template node id.
type prepareWorkloadRequest struct {
	TemplateNodeID uuid.UUID              `json:"templateNodeId"`
	ConfigJSON     map[string]interface{} `json:"configJson"`
}

// RegistryCredentials is a container registry login a Worker needs to pull a private agent image.
type RegistryCredentials struct {
	Host     string `json:"host"`
	Username string `json:"username"`
	Password string `json:"password"`
}

// PrepareResponse is what a Worker needs to launch a workload itself, resolved server-side from
// the stored graph snapshot and DB-held secrets.
type PrepareResponse struct {
	Image            string               `json:"image"`
	EnableDocker     bool                 `json:"enableDocker"`
	ClaudeOAuthToken string               `json:"claudeOAuthToken"`
	GitHubTokenURL   string               `json:"githubTokenUrl"`
	Registry         *RegistryCredentials `json:"registryCredentials"`
	Namespace        string               `json:"namespace"`
	ServiceAccount   string               `json:"serviceAccount"`
}

// PrepareParams contains everything needed to resolve a workload's launch inputs via the API
// server, without asking it to launch the container.
type PrepareParams struct {
	RunID          uuid.UUID
	NodeExecID     uuid.UUID
	TemplateNodeID uuid.UUID
	ConfigJSON     map[string]interface{}
}

// PrepareWorkload resolves everything a Worker needs to launch this workload itself — image,
// credentials, and identity — without the API server launching the container. It is the
// counterpart to CreateWorkload for the case where the Worker runs the container.
func (c *Client) PrepareWorkload(ctx context.Context, params PrepareParams) (*PrepareResponse, error) {
	body := prepareWorkloadRequest{
		TemplateNodeID: params.TemplateNodeID,
		ConfigJSON:     params.ConfigJSON,
	}
	path := workloadPath(params.RunID, params.NodeExecID) + "/prepare"
	resp, err := c.doJSON(ctx, http.MethodPost, path, body)
	if err != nil {
		return nil, fmt.Errorf("prepare workload: %w", err)
	}
	var result PrepareResponse
	if err := json.Unmarshal(resp, &result); err != nil {
		return nil, fmt.Errorf("unmarshal prepare workload response: %w", err)
	}
	return &result, nil
}

// CompleteRequest is the wire body CompleteWorkload sends: what a Worker reports back after it
// has launched a workload on its own.
type CompleteRequest struct {
	PodName       string `json:"podName"`
	JobSecretHash string `json:"jobSecretHash"`
}

// CompleteParams identifies the node execution CompleteWorkload records completion for.
type CompleteParams struct {
	RunID         uuid.UUID
	NodeExecID    uuid.UUID
	PodName       string
	JobSecretHash string
}

// CompleteWorkload records the pod name and job secret hash for a workload the Worker launched
// itself, and transitions the node execution to running. It mirrors the DB-write tail
// CreateWorkload performs atomically when the API server is the one launching the container.
func (c *Client) CompleteWorkload(ctx context.Context, params CompleteParams) error {
	body := CompleteRequest{PodName: params.PodName, JobSecretHash: params.JobSecretHash}
	path := workloadPath(params.RunID, params.NodeExecID) + "/complete"
	if _, err := c.doJSON(ctx, http.MethodPost, path, body); err != nil {
		return fmt.Errorf("complete workload: %w", err)
	}
	return nil
}

// GetWorkloadLogs returns recent log output from the agent container.
func (c *Client) GetWorkloadLogs(ctx context.Context, runID, nodeExecID uuid.UUID, tailLines int) (string, error) {
	path := workloadPath(runID, nodeExecID) + fmt.Sprintf("/logs?tailLines=%d", tailLines)
	resp, err := c.doJSON(ctx, http.MethodGet, path, nil)
	if err != nil {
		return "", fmt.Errorf("get workload logs: %w", err)
	}
	var result struct {
		Logs string `json:"logs"`
	}
	if err := json.Unmarshal(resp, &result); err != nil {
		return "", fmt.Errorf("unmarshal workload logs: %w", err)
	}
	return result.Logs, nil
}
