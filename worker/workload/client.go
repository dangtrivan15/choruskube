// Package workload calls the API server's internal workload endpoints — creating an agent
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

	"github.com/google/uuid"
)

// Client calls the API server's internal workload endpoints.
type Client struct {
	baseURL string
	secret  string
	hc      *http.Client
}

// NewClient returns a Client that authenticates every request to the API server at baseURL
// with a Bearer token built from secret. hc defaults to http.DefaultClient when nil.
func NewClient(baseURL, secret string, hc *http.Client) *Client {
	if hc == nil {
		hc = http.DefaultClient
	}
	return &Client{baseURL: strings.TrimRight(baseURL, "/"), secret: secret, hc: hc}
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
	req.Header.Set("Authorization", "Bearer "+c.secret)
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
	path := fmt.Sprintf("/internal/workloads/%s/%s", params.RunID, params.NodeExecID)
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
func (c *Client) CleanupWorkload(ctx context.Context, executionID uuid.UUID) error {
	_, err := c.doJSON(ctx, http.MethodDelete, fmt.Sprintf("/internal/workloads/%s", executionID), nil)
	if err != nil {
		return fmt.Errorf("cleanup workload: %w", err)
	}
	return nil
}

// GetWorkloadLogs returns recent log output from the agent container.
func (c *Client) GetWorkloadLogs(ctx context.Context, executionID uuid.UUID, tailLines int) (string, error) {
	path := fmt.Sprintf("/internal/workloads/%s/logs?tailLines=%d", executionID, tailLines)
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
