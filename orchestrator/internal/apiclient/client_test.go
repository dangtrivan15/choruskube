package apiclient

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/dangtrivan15/choruskube/orchestrator/internal/state"
)

func newTestServer(t *testing.T, handler http.HandlerFunc) (*httptest.Server, *Client) {
	t.Helper()
	server := httptest.NewServer(handler)
	t.Cleanup(server.Close)
	client := NewClient(server.URL)
	return server, client
}

func TestCreateNodeExecution(t *testing.T) {
	runID := uuid.New()
	execID := uuid.New()
	templateNodeID := uuid.New()

	_, client := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodPost, r.Method)
		assert.Contains(t, r.URL.Path, "/internal/runs/"+runID.String()+"/node-executions")
		assert.Equal(t, "application/json", r.Header.Get("Content-Type"))

		var body createNodeExecRequest
		require.NoError(t, json.NewDecoder(r.Body).Decode(&body))
		assert.Equal(t, templateNodeID, body.TemplateNodeID)
		assert.Equal(t, 1, body.GraphVersion)

		w.WriteHeader(http.StatusCreated)
		json.NewEncoder(w).Encode(nodeExecResponse{
			ID:             execID,
			TemplateNodeID: templateNodeID,
			Status:         "pending",
			Iteration:      1,
			GraphVersion:   1,
			ArtifactRefs:   "{}",
		})
	})

	exec, err := client.CreateNodeExecution(context.Background(), runID, state.CreateNodeExecutionParams{
		WorkflowRunID:  runID,
		TemplateNodeID: templateNodeID,
		GraphVersion:   1,
		Iteration:      1,
	})
	require.NoError(t, err)
	assert.Equal(t, execID, exec.ID)
	assert.Equal(t, "pending", exec.Status)
}

func TestUpdateNodeExecution(t *testing.T) {
	runID := uuid.New()
	execID := uuid.New()

	_, client := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodPut, r.Method)
		assert.Contains(t, r.URL.Path, "/status")

		var body updateNodeExecRequest
		require.NoError(t, json.NewDecoder(r.Body).Decode(&body))
		assert.Equal(t, "running", body.Status)

		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(nodeExecResponse{
			ID:           execID,
			Status:       "running",
			ArtifactRefs: "{}",
		})
	})

	err := client.UpdateNodeExecution(context.Background(), runID, execID, state.UpdateNodeExecutionParams{
		Status: "running",
	})
	require.NoError(t, err)
}

func TestUpdateWorkflowRunStatus(t *testing.T) {
	runID := uuid.New()

	_, client := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodPut, r.Method)
		assert.Equal(t, "/internal/runs/"+runID.String()+"/status", r.URL.Path)

		var body map[string]string
		require.NoError(t, json.NewDecoder(r.Body).Decode(&body))
		assert.Equal(t, "running", body["status"])

		w.WriteHeader(http.StatusNoContent)
	})

	err := client.UpdateWorkflowRunStatus(context.Background(), runID, "running")
	require.NoError(t, err)
}

func TestGetGraphRuntime(t *testing.T) {
	runID := uuid.New()
	snapshot := `{"nodes":[],"edges":[]}`

	_, client := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodGet, r.Method)
		assert.Equal(t, "/internal/runs/"+runID.String()+"/graph-runtime", r.URL.Path)

		w.WriteHeader(http.StatusOK)
		w.Write([]byte(snapshot))
	})

	result, err := client.GetGraphRuntime(context.Background(), runID)
	require.NoError(t, err)
	assert.Equal(t, snapshot, result)
}

func TestWriteExecutionLog(t *testing.T) {
	runID := uuid.New()
	execID := uuid.New()

	_, client := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodPost, r.Method)
		assert.Contains(t, r.URL.Path, "/logs")

		var body map[string]string
		require.NoError(t, json.NewDecoder(r.Body).Decode(&body))
		assert.Equal(t, "info", body["level"])
		assert.Equal(t, "test message", body["message"])

		w.WriteHeader(http.StatusCreated)
	})

	err := client.WriteExecutionLog(context.Background(), runID, execID, "info", "test message")
	require.NoError(t, err)
}

func TestGetCompletedPredecessors(t *testing.T) {
	runID := uuid.New()
	execID := uuid.New()
	predNodeID := uuid.New()

	_, client := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodGet, r.Method)
		assert.Contains(t, r.URL.Path, "/predecessors")

		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode([]predecessorArtifactsResponse{
			{TemplateNodeID: predNodeID, ArtifactRefs: `{"spec":"runs/123/out/spec.md"}`},
		})
	})

	preds, err := client.GetCompletedPredecessors(context.Background(), runID, execID)
	require.NoError(t, err)
	require.Len(t, preds, 1)
	assert.Equal(t, predNodeID, preds[0].TemplateNodeID)
}

func TestGetJobSecretHash(t *testing.T) {
	runID := uuid.New()
	execID := uuid.New()

	_, client := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodGet, r.Method)
		assert.Contains(t, r.URL.Path, "/job-secret-hash")

		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(jobSecretHashResponse{Hash: "abc123"})
	})

	hash, err := client.GetJobSecretHash(context.Background(), runID, execID)
	require.NoError(t, err)
	assert.Equal(t, "abc123", hash)
}

func TestAuthenticatedClient_SendsBearerToken(t *testing.T) {
	runID := uuid.New()
	snapshot := `{"nodes":[],"edges":[]}`

	_, client := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		// Verify the Authorization header is set
		assert.Equal(t, "Bearer my-secret-token", r.Header.Get("Authorization"))
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(snapshot))
	})
	// Override with authenticated client pointing at same server
	client.bearerToken = "my-secret-token"

	result, err := client.GetGraphRuntime(context.Background(), runID)
	require.NoError(t, err)
	assert.Equal(t, snapshot, result)
}

func TestUnauthenticatedClient_NoBearerToken(t *testing.T) {
	runID := uuid.New()
	snapshot := `{"nodes":[],"edges":[]}`

	_, client := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		// Verify no Authorization header is set
		assert.Empty(t, r.Header.Get("Authorization"))
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(snapshot))
	})

	result, err := client.GetGraphRuntime(context.Background(), runID)
	require.NoError(t, err)
	assert.Equal(t, snapshot, result)
}

func TestAPIError(t *testing.T) {
	runID := uuid.New()

	_, client := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
		w.Write([]byte("not found"))
	})

	_, err := client.GetGraphRuntime(context.Background(), runID)
	require.Error(t, err)
	assert.Contains(t, err.Error(), "api error 404")

	// Verify the error is a typed *APIError extractable via errors.As
	var apiErr *APIError
	require.True(t, errors.As(err, &apiErr))
	assert.Equal(t, http.StatusNotFound, apiErr.StatusCode)
	assert.Equal(t, "not found", apiErr.Body)
}

func TestAPIError_429_ReturnsTypedError(t *testing.T) {
	runID := uuid.New()

	_, client := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusTooManyRequests)
		w.Write([]byte(`{"error":"quota_exceeded","quotaType":"monthly_runs","current":500,"limit":500}`))
	})

	_, err := client.GetGraphRuntime(context.Background(), runID)
	require.Error(t, err)

	var apiErr *APIError
	require.True(t, errors.As(err, &apiErr))
	assert.Equal(t, http.StatusTooManyRequests, apiErr.StatusCode)
	assert.Contains(t, apiErr.Body, "quota_exceeded")
}

func TestCreateWorkload_SlimBody(t *testing.T) {
	runID := uuid.New()
	nodeExecID := uuid.New()
	templateNodeID := uuid.New()

	_, client := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodPost, r.Method)
		assert.Equal(t, fmt.Sprintf("/internal/workloads/%s/%s", runID, nodeExecID), r.URL.Path)

		var body map[string]interface{}
		require.NoError(t, json.NewDecoder(r.Body).Decode(&body))

		// Only templateNodeId and configJson should be present
		assert.Len(t, body, 2)
		assert.Equal(t, templateNodeID.String(), body["templateNodeId"])
		assert.NotNil(t, body["configJson"])

		w.WriteHeader(http.StatusCreated)
		json.NewEncoder(w).Encode(CreateWorkloadResponse{
			ExecutionHandle: "agent-xyz",
			JobSecretHash:   "hash456",
		})
	})

	resp, err := client.CreateWorkload(context.Background(), CreateWorkloadParams{
		RunID:          runID,
		NodeExecID:     nodeExecID,
		TemplateNodeID: templateNodeID,
		ConfigJSON:     map[string]interface{}{"key": "value"},
	})
	require.NoError(t, err)
	assert.Equal(t, "agent-xyz", resp.ExecutionHandle)
	assert.Equal(t, "hash456", resp.JobSecretHash)
}

// TestGetReviewHistory_IncludesFeedbackFields is the regression test for the bug where
// the orchestrator's review-history decode struct silently dropped the reviewer's
// feedback text (and status/nodeLabel) before it ever reached the {review_history}
// prompt variable. It also covers null-safety (an AI-authored entry with no human
// feedback yet) and ordering (multiple loop iterations must accumulate in sequence).
func TestGetReviewHistory_IncludesFeedbackFields(t *testing.T) {
	runID := uuid.New()

	_, client := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodGet, r.Method)
		assert.Contains(t, r.URL.Path, "/internal/runs/"+runID.String()+"/review-history")
		assert.Equal(t, "proposal-review", r.URL.Query().Get("loopGroup"))

		w.WriteHeader(http.StatusOK)
		fmt.Fprint(w, `[
			{
				"id": "`+uuid.New().String()+`",
				"loopGroup": "proposal-review",
				"iteration": 1,
				"reviewerType": "ai",
				"decision": "",
				"result": null,
				"status": null,
				"artifactRefs": "{}",
				"nodeLabel": null,
				"timestamp": "2026-01-01T00:00:00Z"
			},
			{
				"id": "`+uuid.New().String()+`",
				"loopGroup": "proposal-review",
				"iteration": 1,
				"reviewerType": "human",
				"decision": "rejected",
				"result": "Please avoid duplicating the CSV import epic",
				"status": "completed",
				"artifactRefs": "{}",
				"nodeLabel": "roadmap_human_gate",
				"timestamp": "2026-01-01T00:05:00Z"
			}
		]`)
	})

	reviews, err := client.GetReviewHistory(context.Background(), runID, "proposal-review")
	require.NoError(t, err)
	require.Len(t, reviews, 2)

	// Entry 1: AI-authored, no human feedback yet — must decode nullable fields
	// to "" without error, and must not be reordered relative to entry 2.
	assert.Equal(t, "ai", reviews[0].ReviewerType)
	assert.Empty(t, reviews[0].Result)
	assert.Empty(t, reviews[0].Status)
	assert.Empty(t, reviews[0].NodeLabel)

	// Entry 2: human feedback — this is the seam that previously dropped the data.
	assert.Equal(t, "human", reviews[1].ReviewerType)
	assert.Equal(t, "Please avoid duplicating the CSV import epic", reviews[1].Result)
	assert.Equal(t, "completed", reviews[1].Status)
	assert.Equal(t, "roadmap_human_gate", reviews[1].NodeLabel)
}
