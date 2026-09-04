package activity

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"go.temporal.io/sdk/temporal"

	"github.com/dangtrivan15/choruskube/orchestrator/internal/apiclient"
	"github.com/dangtrivan15/choruskube/orchestrator/internal/config"
	"github.com/dangtrivan15/choruskube/orchestrator/internal/prompt"
)

func TestCreateNodeExecution(t *testing.T) {
	execID := uuid.New()
	templateNodeID := uuid.New()
	runID := uuid.New()

	apiServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusCreated)
		json.NewEncoder(w).Encode(map[string]interface{}{
			"id":             execID,
			"templateNodeId": templateNodeID,
			"status":         "pending",
			"iteration":      1,
			"graphVersion":   1,
			"artifactRefs":   "{}",
		})
	}))
	defer apiServer.Close()

	client := apiclient.NewClient(apiServer.URL)
	activities := NewActivities(client, prompt.NewResolver(), testConfig(), nil)

	result, err := activities.CreateNodeExecution(withWorkflowRunID(t, runID), CreateNodeExecParams{
		WorkflowRunID:  runID,
		TemplateNodeID: templateNodeID,
		GraphVersion:   1,
	})
	require.NoError(t, err)
	assert.Equal(t, execID, result)
}

func TestCreateNodeExecution_429_ReturnsNonRetryableError(t *testing.T) {
	runID := uuid.New()
	templateNodeID := uuid.New()

	apiServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusTooManyRequests)
		w.Write([]byte(`{"error":"quota_exceeded","quotaType":"monthly_node_executions","current":5000,"limit":5000}`))
	}))
	defer apiServer.Close()

	client := apiclient.NewClient(apiServer.URL)
	activities := NewActivities(client, prompt.NewResolver(), testConfig(), nil)

	_, err := activities.CreateNodeExecution(withWorkflowRunID(t, runID), CreateNodeExecParams{
		WorkflowRunID:  runID,
		TemplateNodeID: templateNodeID,
		GraphVersion:   1,
	})
	require.Error(t, err)

	var appErr *temporal.ApplicationError
	require.True(t, errors.As(err, &appErr))
	assert.True(t, appErr.NonRetryable())
	assert.Equal(t, "QUOTA_EXCEEDED", appErr.Type())
	assert.Contains(t, appErr.Error(), "quota exceeded")
}

func TestCreateNodeExecution_500_ReturnsRetryableError(t *testing.T) {
	runID := uuid.New()
	templateNodeID := uuid.New()

	apiServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
		w.Write([]byte("internal error"))
	}))
	defer apiServer.Close()

	client := apiclient.NewClient(apiServer.URL)
	activities := NewActivities(client, prompt.NewResolver(), testConfig(), nil)

	_, err := activities.CreateNodeExecution(withWorkflowRunID(t, runID), CreateNodeExecParams{
		WorkflowRunID:  runID,
		TemplateNodeID: templateNodeID,
		GraphVersion:   1,
	})
	require.Error(t, err)

	var appErr *temporal.ApplicationError
	assert.False(t, errors.As(err, &appErr), "500 errors should not be wrapped as ApplicationError")
}

func TestWriteExecutionLog(t *testing.T) {
	apiServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodPost, r.Method)
		assert.Contains(t, r.URL.Path, "/logs")
		w.WriteHeader(http.StatusCreated)
	}))
	defer apiServer.Close()

	client := apiclient.NewClient(apiServer.URL)
	acts := NewActivities(client, nil, nil, nil)

	runID := uuid.New()
	err := acts.WriteExecutionLog(withWorkflowRunID(t, runID), WriteExecutionLogParams{
		RunID: runID, NodeExecutionID: uuid.New(), Level: "info", Message: "test log",
	})
	require.NoError(t, err)
}

func TestUpdateNodeExecutionStatus(t *testing.T) {
	apiServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodPut, r.Method)
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(map[string]interface{}{"id": uuid.New().String(), "status": "failed"})
	}))
	defer apiServer.Close()

	client := apiclient.NewClient(apiServer.URL)
	acts := NewActivities(client, nil, nil, nil)

	errMsg := "heartbeat timeout"
	runID := uuid.New()
	err := acts.UpdateNodeExecutionStatus(withWorkflowRunID(t, runID), UpdateNodeExecStatusParams{
		RunID: runID, NodeExecutionID: uuid.New(), Status: "failed", ErrorMessage: &errMsg,
	})
	require.NoError(t, err)
}

func TestUpdateWorkflowRunStatus(t *testing.T) {
	apiServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodPut, r.Method)
		w.WriteHeader(http.StatusOK)
	}))
	defer apiServer.Close()

	client := apiclient.NewClient(apiServer.URL)
	acts := NewActivities(client, nil, nil, nil)

	runID := uuid.New()
	err := acts.UpdateWorkflowRunStatus(withWorkflowRunID(t, runID), UpdateRunStatusParams{
		RunID: runID, Status: "awaiting_retry",
	})
	require.NoError(t, err)
}

func TestDeleteStaleBranches(t *testing.T) {
	runID := uuid.New()
	var receivedMethod, receivedPath string

	apiServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		receivedMethod = r.Method
		receivedPath = r.URL.Path
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(map[string]interface{}{"results": []interface{}{}})
	}))
	defer apiServer.Close()

	client := apiclient.NewClient(apiServer.URL)
	acts := NewActivities(client, nil, nil, nil)

	err := acts.DeleteStaleBranches(withWorkflowRunID(t, runID), DeleteStaleBranchesParams{RunID: runID})
	require.NoError(t, err)
	assert.Equal(t, http.MethodPost, receivedMethod)
	assert.Equal(t, fmt.Sprintf("/internal/runs/%s/cleanup-branches", runID), receivedPath)
}

func TestDeleteStaleBranches_NonOKSurfacesAsError(t *testing.T) {
	runID := uuid.New()

	apiServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
		w.Write([]byte("internal error"))
	}))
	defer apiServer.Close()

	client := apiclient.NewClient(apiServer.URL)
	acts := NewActivities(client, nil, nil, nil)

	err := acts.DeleteStaleBranches(withWorkflowRunID(t, runID), DeleteStaleBranchesParams{RunID: runID})
	require.Error(t, err)
}

func TestSetNodeDecision(t *testing.T) {
	var receivedBody map[string]string
	apiServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodPut, r.Method)
		assert.Contains(t, r.URL.Path, "/decision")
		json.NewDecoder(r.Body).Decode(&receivedBody)
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(map[string]string{"decision": receivedBody["decision"]})
	}))
	defer apiServer.Close()

	client := apiclient.NewClient(apiServer.URL)
	acts := NewActivities(client, nil, nil, nil)

	runID := uuid.New()
	err := acts.SetNodeDecision(withWorkflowRunID(t, runID), SetNodeDecisionParams{
		RunID: runID, NodeExecutionID: uuid.New(), Decision: "approved",
	})
	require.NoError(t, err)
	assert.Equal(t, "approved", receivedBody["decision"])
}

func TestUpdateNodeExecutionStatus_WithArtifactRefs(t *testing.T) {
	var receivedBody map[string]interface{}

	apiServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodPut, r.Method)
		json.NewDecoder(r.Body).Decode(&receivedBody)
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(map[string]interface{}{"id": uuid.New().String(), "status": "completed"})
	}))
	defer apiServer.Close()

	client := apiclient.NewClient(apiServer.URL)
	acts := NewActivities(client, nil, nil, nil)

	refs := `{"report.pdf":"orgs/myorg/runs/abc/out/report.pdf"}`
	result := "done"
	runID := uuid.New()
	err := acts.UpdateNodeExecutionStatus(withWorkflowRunID(t, runID), UpdateNodeExecStatusParams{
		RunID:           runID,
		NodeExecutionID: uuid.New(),
		Status:          "completed",
		Result:          &result,
		ArtifactRefs:    &refs,
	})
	require.NoError(t, err)
	assert.Equal(t, refs, receivedBody["artifactRefs"], "artifactRefs should be forwarded to PUT body")
}

func TestLoadReviewHistoryJSON_PreservesFeedbackText(t *testing.T) {
	runID := uuid.New()
	combinedFeedback := "## Chat Transcript\nReviewer: what about CSV import?\n\n## Reviewer Feedback\nPlease avoid duplicating the CSV import epic"

	apiServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Contains(t, r.URL.Path, "/review-history")
		w.WriteHeader(http.StatusOK)
		fmt.Fprintf(w, `[
			{
				"id": "%s",
				"loopGroup": "proposal-review",
				"iteration": 1,
				"reviewerType": "human",
				"decision": "rejected",
				"result": %q,
				"status": "completed",
				"artifactRefs": "{}",
				"nodeLabel": "roadmap_human_gate",
				"timestamp": "2026-01-01T00:00:00Z"
			},
			{
				"id": "%s",
				"loopGroup": "proposal-review",
				"iteration": 2,
				"reviewerType": "human",
				"decision": "approved",
				"result": "",
				"status": "completed",
				"artifactRefs": "{}",
				"nodeLabel": "roadmap_human_gate",
				"timestamp": "2026-01-01T00:10:00Z"
			}
		]`, uuid.New().String(), combinedFeedback, uuid.New().String())
	}))
	defer apiServer.Close()

	client := apiclient.NewClient(apiServer.URL)
	acts := NewActivities(client, nil, nil, nil)

	jsonStr, err := acts.LoadReviewHistoryJSON(withWorkflowRunID(t, runID), LoadReviewHistoryJSONParams{
		RunID:     runID,
		LoopGroup: "proposal-review",
	})
	require.NoError(t, err)

	assert.Contains(t, jsonStr, "## Chat Transcript")
	assert.Contains(t, jsonStr, "## Reviewer Feedback")
	assert.Contains(t, jsonStr, "Please avoid duplicating the CSV import epic")

	var decoded []map[string]interface{}
	require.NoError(t, json.Unmarshal([]byte(jsonStr), &decoded))
	require.Len(t, decoded, 2)
	assert.Equal(t, "", decoded[1]["Result"])
	assert.NotContains(t, jsonStr, `"Result":null`)
}

func testConfig() *config.Config {
	return &config.Config{
		APIServerURL: "http://localhost:8080",
	}
}
