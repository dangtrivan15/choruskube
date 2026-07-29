package activity

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"go.temporal.io/sdk/activity"
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

	result, err := activities.CreateNodeExecution(context.Background(), CreateNodeExecParams{
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

	_, err := activities.CreateNodeExecution(context.Background(), CreateNodeExecParams{
		WorkflowRunID:  runID,
		TemplateNodeID: templateNodeID,
		GraphVersion:   1,
	})
	require.Error(t, err)

	// Verify the error is non-retryable (Temporal will not retry it)
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

	_, err := activities.CreateNodeExecution(context.Background(), CreateNodeExecParams{
		WorkflowRunID:  runID,
		TemplateNodeID: templateNodeID,
		GraphVersion:   1,
	})
	require.Error(t, err)

	// Non-429 errors should remain retryable (no ApplicationError wrapping)
	var appErr *temporal.ApplicationError
	assert.False(t, errors.As(err, &appErr), "500 errors should not be wrapped as ApplicationError")
}

func TestExecuteAINode_DelegatesToAPIServer(t *testing.T) {
	execID := uuid.New()
	runID := uuid.New()
	templateNodeID := uuid.New()

	var createWorkloadCalled bool
	var receivedConfigJSON map[string]interface{}

	apiServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodPost && strings.Contains(r.URL.Path, "/internal/workloads/"):
			// CreateWorkload endpoint
			createWorkloadCalled = true
			var req map[string]interface{}
			json.NewDecoder(r.Body).Decode(&req)
			if cj, ok := req["configJson"].(map[string]interface{}); ok {
				receivedConfigJSON = cj
			}
			w.WriteHeader(http.StatusCreated)
			json.NewEncoder(w).Encode(map[string]interface{}{
				"executionHandle": "agent-abc12345",
				"jobSecretHash":   "hash123",
			})
		case r.Method == http.MethodPost && strings.HasSuffix(r.URL.Path, "/logs"):
			// WriteExecutionLog
			w.WriteHeader(http.StatusCreated)
		default:
			w.WriteHeader(http.StatusOK)
		}
	}))
	defer apiServer.Close()

	client := apiclient.NewClient(apiServer.URL)
	activities := NewActivities(client, prompt.NewResolver(), testConfig(), nil)

	err := activities.ExecuteAINode(context.Background(), ExecuteAINodeParams{
		NodeExecutionID: execID,
		RunID:           runID,
		TemplateNodeID:  templateNodeID,
		PromptTemplate:  "Write hello world",
		Image:           "registry.example.com/claude-code:latest",
	})
	// ExecuteAINode returns activity.ErrResultPending from the Temporal SDK
	assert.ErrorIs(t, err, activity.ErrResultPending)
	assert.True(t, createWorkloadCalled, "CreateWorkload should have been called")
	assert.Equal(t, "Write hello world", receivedConfigJSON["prompt"])
}

// TestExecuteAINode_OutputPathKeyedByExecutionID guards against a bug where
// repeated iterations of the same template node share an output prefix and
// overwrite each other's object storage artifacts. The path must include the execution
// ID so each iteration owns its own prefix.
func TestExecuteAINode_OutputPathKeyedByExecutionID(t *testing.T) {
	execID := uuid.New()
	runID := uuid.New()
	templateNodeID := uuid.New()

	var receivedConfigJSON map[string]interface{}

	apiServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodPost && strings.Contains(r.URL.Path, "/internal/workloads/"):
			var req map[string]interface{}
			json.NewDecoder(r.Body).Decode(&req)
			if cj, ok := req["configJson"].(map[string]interface{}); ok {
				receivedConfigJSON = cj
			}
			w.WriteHeader(http.StatusCreated)
			json.NewEncoder(w).Encode(map[string]interface{}{
				"executionHandle": "agent-abc12345",
				"jobSecretHash":   "hash123",
			})
		default:
			w.WriteHeader(http.StatusOK)
		}
	}))
	defer apiServer.Close()

	client := apiclient.NewClient(apiServer.URL)
	activities := NewActivities(client, prompt.NewResolver(), testConfig(), nil)

	err := activities.ExecuteAINode(context.Background(), ExecuteAINodeParams{
		NodeExecutionID: execID,
		RunID:           runID,
		TemplateNodeID:  templateNodeID,
		PromptTemplate:  "irrelevant",
	})
	assert.ErrorIs(t, err, activity.ErrResultPending)

	outputPath, _ := receivedConfigJSON["output_path"].(string)
	assert.Contains(t, outputPath, execID.String(),
		"output_path must include the execution ID so iterations own their own prefix")
	assert.NotContains(t, outputPath, templateNodeID.String(),
		"output_path must NOT be keyed by template node ID — that causes iterations to overwrite each other")
}

// TestExecuteAINodeFromSnapshot_OutputPathKeyedByExecutionID guards the same
// invariant for the snapshot variant (org-prefixed paths).
func TestExecuteAINodeFromSnapshot_OutputPathKeyedByExecutionID(t *testing.T) {
	execID := uuid.New()
	runID := uuid.New()
	templateNodeID := uuid.New()

	var receivedConfigJSON map[string]interface{}

	apiServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodPost && strings.Contains(r.URL.Path, "/internal/workloads/"):
			var req map[string]interface{}
			json.NewDecoder(r.Body).Decode(&req)
			if cj, ok := req["configJson"].(map[string]interface{}); ok {
				receivedConfigJSON = cj
			}
			w.WriteHeader(http.StatusCreated)
			json.NewEncoder(w).Encode(map[string]interface{}{
				"executionHandle": "agent-abc12345",
				"jobSecretHash":   "hash123",
			})
		default:
			w.WriteHeader(http.StatusOK)
		}
	}))
	defer apiServer.Close()

	client := apiclient.NewClient(apiServer.URL)
	cfg := &config.Config{
		APIServerURL: apiServer.URL,
		Callback:     config.CallbackConfig{URL: "http://callback:9090/api/v1/callback"},
	}
	acts := NewActivities(client, prompt.NewResolver(), cfg, nil)

	err := acts.ExecuteAINodeFromSnapshot(context.Background(), ExecuteAINodeFromSnapshotParams{
		NodeExecutionID: execID,
		RunID:           runID,
		TemplateNodeID:  templateNodeID,
		Label:           "spec_review",
		ExecutorType:    "ai",
		PromptTemplate:  "irrelevant",
		Iteration:       2,
	})
	assert.ErrorIs(t, err, activity.ErrResultPending)

	outputPath, _ := receivedConfigJSON["output_path"].(string)
	assert.Contains(t, outputPath, execID.String(),
		"output_path must include the execution ID so iterations own their own prefix")
	assert.NotContains(t, outputPath, templateNodeID.String(),
		"output_path must NOT be keyed by template node ID — that causes iterations to overwrite each other")
}

func TestExecuteAINodeFromSnapshot_ScriptNode_ConfigJson(t *testing.T) {
	var receivedConfigJSON map[string]interface{}

	apiServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodPost && strings.Contains(r.URL.Path, "/internal/workloads/"):
			var req map[string]interface{}
			json.NewDecoder(r.Body).Decode(&req)
			if cj, ok := req["configJson"].(map[string]interface{}); ok {
				receivedConfigJSON = cj
			}
			w.WriteHeader(http.StatusCreated)
			json.NewEncoder(w).Encode(map[string]interface{}{
				"executionHandle": "agent-abc12345",
				"jobSecretHash":   "hash123",
			})
		case r.Method == http.MethodPost && strings.HasSuffix(r.URL.Path, "/logs"):
			w.WriteHeader(http.StatusCreated)
		default:
			w.WriteHeader(http.StatusOK)
		}
	}))
	defer apiServer.Close()

	client := apiclient.NewClient(apiServer.URL)
	cfg := &config.Config{
		APIServerURL: apiServer.URL,
		Callback:     config.CallbackConfig{URL: "http://callback:9090/api/v1/callback"},
	}

	acts := NewActivities(client, prompt.NewResolver(), cfg, nil)

	params := ExecuteAINodeFromSnapshotParams{
		NodeExecutionID: uuid.New(),
		RunID:           uuid.New(),
		TemplateNodeID:  uuid.New(),
		Label:           "test",
		ExecutorType:    "script",
		Command:         "cd /workspace/repo && npm test",
		RepoURL:         "https://github.com/test/repo",
		WorkingBranch:   "choruskube-run-abc123",
		PromptTemplate:  "", // script nodes have no prompt
		Variables:       map[string]string{"run.id": "abc123"},
		Iteration:       1,
	}

	err := acts.ExecuteAINodeFromSnapshot(context.Background(), params)
	assert.ErrorIs(t, err, activity.ErrResultPending)

	// Verify config.json fields
	assert.Equal(t, "script", receivedConfigJSON["executor_type"])
	assert.Equal(t, "cd /workspace/repo && npm test", receivedConfigJSON["command"])
	assert.Equal(t, "https://github.com/test/repo", receivedConfigJSON["repo_url"])
	assert.Equal(t, "choruskube-run-abc123", receivedConfigJSON["working_branch"])
	assert.Equal(t, "", receivedConfigJSON["prompt"])

	// Object storage fields should NOT be in config.json
	assert.Nil(t, receivedConfigJSON["minio_endpoint"])
	assert.Nil(t, receivedConfigJSON["minio_bucket"])
}

func TestExecuteAINodeFromSnapshot_NoSystemPromptInConfigJson(t *testing.T) {
	var receivedConfigJSON map[string]interface{}

	runID := uuid.New()

	apiServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodPost && strings.Contains(r.URL.Path, "/internal/workloads/"):
			var req map[string]interface{}
			json.NewDecoder(r.Body).Decode(&req)
			if cj, ok := req["configJson"].(map[string]interface{}); ok {
				receivedConfigJSON = cj
			}
			w.WriteHeader(http.StatusCreated)
			json.NewEncoder(w).Encode(map[string]interface{}{
				"executionHandle": "agent-abc12345",
				"jobSecretHash":   "hash123",
			})
		case r.Method == http.MethodPost && strings.HasSuffix(r.URL.Path, "/logs"):
			w.WriteHeader(http.StatusCreated)
		default:
			w.WriteHeader(http.StatusOK)
		}
	}))
	defer apiServer.Close()

	client := apiclient.NewClient(apiServer.URL)
	cfg := &config.Config{
		Callback: config.CallbackConfig{URL: "http://callback:9090/api/v1/callback"},
	}

	acts := NewActivities(client, prompt.NewResolver(), cfg, nil)

	params := ExecuteAINodeFromSnapshotParams{
		NodeExecutionID: uuid.New(),
		RunID:           runID,
		TemplateNodeID:  uuid.New(),
		Label:           "implement",
		ExecutorType:    "ai",
		PromptTemplate:  "Do the thing",
		Variables:       map[string]string{"run.id": runID.String()},
		Iteration:       1,
		RunLogPath:      "runs/" + runID.String() + "/run_log.md",
	}

	err := acts.ExecuteAINodeFromSnapshot(context.Background(), params)
	assert.ErrorIs(t, err, activity.ErrResultPending)

	// System prompt is now built by the entrypoint from the image-local template,
	// not passed through config.json by the orchestrator.
	_, hasSystemPrompt := receivedConfigJSON["system_prompt"]
	assert.False(t, hasSystemPrompt, "system_prompt should not be in config.json")
	assert.Equal(t, "runs/"+runID.String()+"/run_log.md", receivedConfigJSON["run_log_path"])
}

func TestExecuteAINodeFromSnapshot_TaskContextInConfigJson(t *testing.T) {
	var receivedConfigJSON map[string]interface{}

	runID := uuid.New()
	taskID := uuid.New()
	storyID := uuid.New()
	epicID := uuid.New()

	apiServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodPost && strings.Contains(r.URL.Path, "/internal/workloads/"):
			var req map[string]interface{}
			json.NewDecoder(r.Body).Decode(&req)
			if cj, ok := req["configJson"].(map[string]interface{}); ok {
				receivedConfigJSON = cj
			}
			w.WriteHeader(http.StatusCreated)
			json.NewEncoder(w).Encode(map[string]interface{}{
				"executionHandle": "agent-abc12345",
				"jobSecretHash":   "hash123",
			})
		case r.Method == http.MethodPost && strings.HasSuffix(r.URL.Path, "/logs"):
			w.WriteHeader(http.StatusCreated)
		default:
			w.WriteHeader(http.StatusOK)
		}
	}))
	defer apiServer.Close()

	client := apiclient.NewClient(apiServer.URL)
	cfg := &config.Config{
		Callback: config.CallbackConfig{URL: "http://callback:9090/api/v1/callback"},
	}

	acts := NewActivities(client, prompt.NewResolver(), cfg, nil)

	params := ExecuteAINodeFromSnapshotParams{
		NodeExecutionID: uuid.New(),
		RunID:           runID,
		TemplateNodeID:  uuid.New(),
		Label:           "implement",
		ExecutorType:    "ai",
		PromptTemplate:  "Do the thing",
		Variables:       map[string]string{"run.id": runID.String()},
		RunLogPath:      "runs/" + runID.String() + "/run_log.md",
		TaskID:          taskID.String(),
		TaskTitle:       "Wire up task_context",
		StoryID:         storyID.String(),
		StoryTitle:      "Agent identity threading",
		EpicID:          epicID.String(),
		EpicTitle:       "Roadmap-aware agents",
	}

	err := acts.ExecuteAINodeFromSnapshot(context.Background(), params)
	assert.ErrorIs(t, err, activity.ErrResultPending)

	taskContext, ok := receivedConfigJSON["task_context"].(map[string]interface{})
	require.True(t, ok, "task_context should be present in config.json when TaskID is set")
	assert.Equal(t, taskID.String(), taskContext["task_id"])
	assert.Equal(t, "Wire up task_context", taskContext["task_title"])
	assert.Equal(t, storyID.String(), taskContext["story_id"])
	assert.Equal(t, "Agent identity threading", taskContext["story_title"])
	assert.Equal(t, epicID.String(), taskContext["epic_id"])
	assert.Equal(t, "Roadmap-aware agents", taskContext["epic_title"])
}

func TestExecuteAINodeFromSnapshot_NoTaskContextWhenTaskIDEmpty(t *testing.T) {
	var receivedConfigJSON map[string]interface{}

	runID := uuid.New()

	apiServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodPost && strings.Contains(r.URL.Path, "/internal/workloads/"):
			var req map[string]interface{}
			json.NewDecoder(r.Body).Decode(&req)
			if cj, ok := req["configJson"].(map[string]interface{}); ok {
				receivedConfigJSON = cj
			}
			w.WriteHeader(http.StatusCreated)
			json.NewEncoder(w).Encode(map[string]interface{}{
				"executionHandle": "agent-abc12345",
				"jobSecretHash":   "hash123",
			})
		case r.Method == http.MethodPost && strings.HasSuffix(r.URL.Path, "/logs"):
			w.WriteHeader(http.StatusCreated)
		default:
			w.WriteHeader(http.StatusOK)
		}
	}))
	defer apiServer.Close()

	client := apiclient.NewClient(apiServer.URL)
	cfg := &config.Config{
		Callback: config.CallbackConfig{URL: "http://callback:9090/api/v1/callback"},
	}

	acts := NewActivities(client, prompt.NewResolver(), cfg, nil)

	// Manually-started run: no TaskID set at all.
	params := ExecuteAINodeFromSnapshotParams{
		NodeExecutionID: uuid.New(),
		RunID:           runID,
		TemplateNodeID:  uuid.New(),
		Label:           "implement",
		ExecutorType:    "ai",
		PromptTemplate:  "Do the thing",
		Variables:       map[string]string{"run.id": runID.String()},
		RunLogPath:      "runs/" + runID.String() + "/run_log.md",
	}

	err := acts.ExecuteAINodeFromSnapshot(context.Background(), params)
	assert.ErrorIs(t, err, activity.ErrResultPending)

	_, hasTaskContext := receivedConfigJSON["task_context"]
	assert.False(t, hasTaskContext, "task_context should be absent from config.json when TaskID is empty")
}

func TestExecuteAINodeFromSnapshot_IterationInConfigJson(t *testing.T) {
	var receivedConfigJSON map[string]interface{}

	apiServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodPost && strings.Contains(r.URL.Path, "/internal/workloads/"):
			var req map[string]interface{}
			json.NewDecoder(r.Body).Decode(&req)
			if cj, ok := req["configJson"].(map[string]interface{}); ok {
				receivedConfigJSON = cj
			}
			w.WriteHeader(http.StatusCreated)
			json.NewEncoder(w).Encode(map[string]interface{}{
				"executionHandle": "agent-abc12345",
				"jobSecretHash":   "hash123",
			})
		case r.Method == http.MethodPost && strings.HasSuffix(r.URL.Path, "/logs"):
			w.WriteHeader(http.StatusCreated)
		default:
			w.WriteHeader(http.StatusOK)
		}
	}))
	defer apiServer.Close()

	client := apiclient.NewClient(apiServer.URL)
	cfg := &config.Config{
		Callback: config.CallbackConfig{URL: "http://callback:9090/api/v1/callback"},
	}

	acts := NewActivities(client, prompt.NewResolver(), cfg, nil)

	params := ExecuteAINodeFromSnapshotParams{
		NodeExecutionID: uuid.New(),
		RunID:           uuid.New(),
		TemplateNodeID:  uuid.New(),
		Label:           "retry-node",
		ExecutorType:    "script",
		Command:         "echo hello",
		PromptTemplate:  "",
		Variables:       map[string]string{"run.id": "test123"},
		Iteration:       3,
	}

	err := acts.ExecuteAINodeFromSnapshot(context.Background(), params)
	assert.ErrorIs(t, err, activity.ErrResultPending)

	assert.Equal(t, float64(3), receivedConfigJSON["iteration"])
}

func TestExecuteAINodeFromSnapshot_IterationZeroOmitted(t *testing.T) {
	var receivedConfigJSON map[string]interface{}

	apiServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodPost && strings.Contains(r.URL.Path, "/internal/workloads/"):
			var req map[string]interface{}
			json.NewDecoder(r.Body).Decode(&req)
			if cj, ok := req["configJson"].(map[string]interface{}); ok {
				receivedConfigJSON = cj
			}
			w.WriteHeader(http.StatusCreated)
			json.NewEncoder(w).Encode(map[string]interface{}{
				"executionHandle": "agent-abc12345",
				"jobSecretHash":   "hash123",
			})
		case r.Method == http.MethodPost && strings.HasSuffix(r.URL.Path, "/logs"):
			w.WriteHeader(http.StatusCreated)
		default:
			w.WriteHeader(http.StatusOK)
		}
	}))
	defer apiServer.Close()

	client := apiclient.NewClient(apiServer.URL)
	cfg := &config.Config{
		Callback: config.CallbackConfig{URL: "http://callback:9090/api/v1/callback"},
	}

	acts := NewActivities(client, prompt.NewResolver(), cfg, nil)

	params := ExecuteAINodeFromSnapshotParams{
		NodeExecutionID: uuid.New(),
		RunID:           uuid.New(),
		TemplateNodeID:  uuid.New(),
		Label:           "first-run",
		ExecutorType:    "ai",
		PromptTemplate:  "Do something",
		Variables:       map[string]string{"run.id": "test123"},
		Iteration:       0,
	}

	err := acts.ExecuteAINodeFromSnapshot(context.Background(), params)
	assert.ErrorIs(t, err, activity.ErrResultPending)

	_, exists := receivedConfigJSON["iteration"]
	assert.False(t, exists, "iteration=0 should not be in config.json")
}

// TestExecuteAINodeFromSnapshot_IterationInEpoch_ResetCase mirrors
// DecisionServiceTest.submitDecision_epochReset_effectiveIterationIsOne's fixture
// (iteration=5, epoch_start=5) so the Go and Java effective-iteration formulas are
// checked against identical inputs (Decision 2).
func TestExecuteAINodeFromSnapshot_IterationInEpoch_ResetCase(t *testing.T) {
	var receivedConfigJSON map[string]interface{}

	apiServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodPost && strings.Contains(r.URL.Path, "/internal/workloads/"):
			var req map[string]interface{}
			json.NewDecoder(r.Body).Decode(&req)
			if cj, ok := req["configJson"].(map[string]interface{}); ok {
				receivedConfigJSON = cj
			}
			w.WriteHeader(http.StatusCreated)
			json.NewEncoder(w).Encode(map[string]interface{}{
				"executionHandle": "agent-abc12345",
				"jobSecretHash":   "hash123",
			})
		case r.Method == http.MethodPost && strings.HasSuffix(r.URL.Path, "/logs"):
			w.WriteHeader(http.StatusCreated)
		default:
			w.WriteHeader(http.StatusOK)
		}
	}))
	defer apiServer.Close()

	client := apiclient.NewClient(apiServer.URL)
	cfg := &config.Config{
		Callback: config.CallbackConfig{URL: "http://callback:9090/api/v1/callback"},
	}

	acts := NewActivities(client, prompt.NewResolver(), cfg, nil)

	params := ExecuteAINodeFromSnapshotParams{
		NodeExecutionID:        uuid.New(),
		RunID:                  uuid.New(),
		TemplateNodeID:         uuid.New(),
		Label:                  "spec-review",
		ExecutorType:           "ai",
		PromptTemplate:         "Do something",
		Variables:              map[string]string{"run.id": "test123"},
		Iteration:              5,
		IterationCapEpochStart: 5,
	}

	err := acts.ExecuteAINodeFromSnapshot(context.Background(), params)
	assert.ErrorIs(t, err, activity.ErrResultPending)

	assert.Equal(t, float64(1), receivedConfigJSON["iteration_in_epoch"])
}

// TestExecuteAINodeFromSnapshot_IterationInEpoch_CarriedForwardCase mirrors
// DecisionServiceTest.submitDecision_epochReset_atEffectiveCap_overrides's fixture
// (iteration=7, epoch_start=5) so the Go and Java effective-iteration formulas are
// checked against identical inputs (Decision 2).
func TestExecuteAINodeFromSnapshot_IterationInEpoch_CarriedForwardCase(t *testing.T) {
	var receivedConfigJSON map[string]interface{}

	apiServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodPost && strings.Contains(r.URL.Path, "/internal/workloads/"):
			var req map[string]interface{}
			json.NewDecoder(r.Body).Decode(&req)
			if cj, ok := req["configJson"].(map[string]interface{}); ok {
				receivedConfigJSON = cj
			}
			w.WriteHeader(http.StatusCreated)
			json.NewEncoder(w).Encode(map[string]interface{}{
				"executionHandle": "agent-abc12345",
				"jobSecretHash":   "hash123",
			})
		case r.Method == http.MethodPost && strings.HasSuffix(r.URL.Path, "/logs"):
			w.WriteHeader(http.StatusCreated)
		default:
			w.WriteHeader(http.StatusOK)
		}
	}))
	defer apiServer.Close()

	client := apiclient.NewClient(apiServer.URL)
	cfg := &config.Config{
		Callback: config.CallbackConfig{URL: "http://callback:9090/api/v1/callback"},
	}

	acts := NewActivities(client, prompt.NewResolver(), cfg, nil)

	params := ExecuteAINodeFromSnapshotParams{
		NodeExecutionID:        uuid.New(),
		RunID:                  uuid.New(),
		TemplateNodeID:         uuid.New(),
		Label:                  "spec-review",
		ExecutorType:           "ai",
		PromptTemplate:         "Do something",
		Variables:              map[string]string{"run.id": "test123"},
		Iteration:              7,
		IterationCapEpochStart: 5,
	}

	err := acts.ExecuteAINodeFromSnapshot(context.Background(), params)
	assert.ErrorIs(t, err, activity.ErrResultPending)

	assert.Equal(t, float64(3), receivedConfigJSON["iteration_in_epoch"])
}

// TestExecuteAINodeFromSnapshot_IterationInEpoch_DefaultEpochStart exercises the
// defensive "0 means default 1" convention at this activity layer. This is a
// Go-only case: there is no Java-side equivalent because InternalRunService
// applies its own 0->1 default earlier, at node-execution creation, so
// DecisionServiceTest never observes an unset epoch start.
func TestExecuteAINodeFromSnapshot_IterationInEpoch_DefaultEpochStart(t *testing.T) {
	var receivedConfigJSON map[string]interface{}

	apiServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodPost && strings.Contains(r.URL.Path, "/internal/workloads/"):
			var req map[string]interface{}
			json.NewDecoder(r.Body).Decode(&req)
			if cj, ok := req["configJson"].(map[string]interface{}); ok {
				receivedConfigJSON = cj
			}
			w.WriteHeader(http.StatusCreated)
			json.NewEncoder(w).Encode(map[string]interface{}{
				"executionHandle": "agent-abc12345",
				"jobSecretHash":   "hash123",
			})
		case r.Method == http.MethodPost && strings.HasSuffix(r.URL.Path, "/logs"):
			w.WriteHeader(http.StatusCreated)
		default:
			w.WriteHeader(http.StatusOK)
		}
	}))
	defer apiServer.Close()

	client := apiclient.NewClient(apiServer.URL)
	cfg := &config.Config{
		Callback: config.CallbackConfig{URL: "http://callback:9090/api/v1/callback"},
	}

	acts := NewActivities(client, prompt.NewResolver(), cfg, nil)

	params := ExecuteAINodeFromSnapshotParams{
		NodeExecutionID:        uuid.New(),
		RunID:                  uuid.New(),
		TemplateNodeID:         uuid.New(),
		Label:                  "spec-review",
		ExecutorType:           "ai",
		PromptTemplate:         "Do something",
		Variables:              map[string]string{"run.id": "test123"},
		Iteration:              2,
		IterationCapEpochStart: 0,
	}

	err := acts.ExecuteAINodeFromSnapshot(context.Background(), params)
	assert.ErrorIs(t, err, activity.ErrResultPending)

	assert.Equal(t, float64(2), receivedConfigJSON["iteration_in_epoch"])
}

func TestFetchPodLogs_DelegatesToAPIServer(t *testing.T) {
	execID := uuid.New()

	apiServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(map[string]interface{}{
			"logs": "(no pod found)",
		})
	}))
	defer apiServer.Close()

	client := apiclient.NewClient(apiServer.URL)
	acts := NewActivities(client, nil, nil, nil)

	logs, err := acts.FetchPodLogs(context.Background(), FetchPodLogsParams{
		NodeExecutionID: execID,
		TailLines:       50,
	})
	require.NoError(t, err)
	assert.Equal(t, "(no pod found)", logs)
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

	err := acts.WriteExecutionLog(context.Background(), WriteExecutionLogParams{
		RunID: uuid.New(), NodeExecutionID: uuid.New(), Level: "info", Message: "test log",
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
	err := acts.UpdateNodeExecutionStatus(context.Background(), UpdateNodeExecStatusParams{
		RunID: uuid.New(), NodeExecutionID: uuid.New(), Status: "failed", ErrorMessage: &errMsg,
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

	err := acts.UpdateWorkflowRunStatus(context.Background(), UpdateRunStatusParams{
		RunID: uuid.New(), Status: "awaiting_retry",
	})
	require.NoError(t, err)
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

	err := acts.SetNodeDecision(context.Background(), SetNodeDecisionParams{
		RunID: uuid.New(), NodeExecutionID: uuid.New(), Decision: "approved",
	})
	require.NoError(t, err)
	assert.Equal(t, "approved", receivedBody["decision"])
}

// TestUpdateNodeExecutionStatus_WithArtifactRefs verifies that a non-nil ArtifactRefs
// is forwarded to the UpdateNodeExecution PUT body.
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
	err := acts.UpdateNodeExecutionStatus(context.Background(), UpdateNodeExecStatusParams{
		RunID:           uuid.New(),
		NodeExecutionID: uuid.New(),
		Status:          "completed",
		Result:          &result,
		ArtifactRefs:    &refs,
	})
	require.NoError(t, err)
	assert.Equal(t, refs, receivedBody["artifactRefs"], "artifactRefs should be forwarded to PUT body")
}

// TestExecuteAINodeFromSnapshot_PredecessorArtifactAnnotation verifies that artifact
// keys (non-.result entries) from Variables are appended to the resolved prompt.
func TestExecuteAINodeFromSnapshot_PredecessorArtifactAnnotation(t *testing.T) {
	var receivedConfigJSON map[string]interface{}

	apiServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodPost && strings.Contains(r.URL.Path, "/internal/workloads/"):
			var req map[string]interface{}
			json.NewDecoder(r.Body).Decode(&req)
			if cj, ok := req["configJson"].(map[string]interface{}); ok {
				receivedConfigJSON = cj
			}
			w.WriteHeader(http.StatusCreated)
			json.NewEncoder(w).Encode(map[string]interface{}{
				"executionHandle": "agent-abc12345",
				"jobSecretHash":   "hash123",
			})
		case r.Method == http.MethodPost && strings.HasSuffix(r.URL.Path, "/logs"):
			w.WriteHeader(http.StatusCreated)
		default:
			w.WriteHeader(http.StatusOK)
		}
	}))
	defer apiServer.Close()

	client := apiclient.NewClient(apiServer.URL)
	cfg := &config.Config{
		APIServerURL: apiServer.URL,
		Callback:     config.CallbackConfig{URL: "http://callback:9090/api/v1/callback"},
	}
	acts := NewActivities(client, prompt.NewResolver(), cfg, nil)

	params := ExecuteAINodeFromSnapshotParams{
		NodeExecutionID: uuid.New(),
		RunID:           uuid.New(),
		TemplateNodeID:  uuid.New(),
		Label:           "implement",
		ExecutorType:    "ai",
		PromptTemplate:  "Do the thing",
		Variables: map[string]string{
			"run.id":                "abc123",
			"input.gate.result":     "approved",          // .result entry — must be excluded
			"input.gate.file.png":   "orgs/x/gate.png",   // artifact — must be included
			"input.gate.report.pdf": "orgs/x/report.pdf", // artifact — must be included
		},
		Iteration: 1,
	}

	err := acts.ExecuteAINodeFromSnapshot(context.Background(), params)
	assert.ErrorIs(t, err, activity.ErrResultPending)

	prompt, ok := receivedConfigJSON["prompt"].(string)
	require.True(t, ok, "prompt should be a string in config.json")
	assert.Contains(t, prompt, "**Predecessor Artifacts**", "prompt should contain the annotation header")
	assert.Contains(t, prompt, "input.gate.file.png", "artifact key should appear in annotation")
	assert.Contains(t, prompt, "input.gate.report.pdf", "artifact key should appear in annotation")
	assert.NotContains(t, prompt, "input.gate.result", "result key must not appear in annotation")
}

// TestExecuteAINodeFromSnapshot_OnlyResultEntries_NoAnnotation verifies that when all
// input.* variables end in ".result", no artifact annotation block is appended.
func TestExecuteAINodeFromSnapshot_OnlyResultEntries_NoAnnotation(t *testing.T) {
	var receivedConfigJSON map[string]interface{}

	apiServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodPost && strings.Contains(r.URL.Path, "/internal/workloads/"):
			var req map[string]interface{}
			json.NewDecoder(r.Body).Decode(&req)
			if cj, ok := req["configJson"].(map[string]interface{}); ok {
				receivedConfigJSON = cj
			}
			w.WriteHeader(http.StatusCreated)
			json.NewEncoder(w).Encode(map[string]interface{}{
				"executionHandle": "agent-abc12345",
				"jobSecretHash":   "hash123",
			})
		case r.Method == http.MethodPost && strings.HasSuffix(r.URL.Path, "/logs"):
			w.WriteHeader(http.StatusCreated)
		default:
			w.WriteHeader(http.StatusOK)
		}
	}))
	defer apiServer.Close()

	client := apiclient.NewClient(apiServer.URL)
	cfg := &config.Config{
		APIServerURL: apiServer.URL,
		Callback:     config.CallbackConfig{URL: "http://callback:9090/api/v1/callback"},
	}
	acts := NewActivities(client, prompt.NewResolver(), cfg, nil)

	params := ExecuteAINodeFromSnapshotParams{
		NodeExecutionID: uuid.New(),
		RunID:           uuid.New(),
		TemplateNodeID:  uuid.New(),
		Label:           "implement",
		ExecutorType:    "ai",
		PromptTemplate:  "Do the thing",
		Variables: map[string]string{
			"run.id":            "abc123",
			"input.gate.result": "approved", // only .result entries — no annotation
		},
		Iteration: 1,
	}

	err := acts.ExecuteAINodeFromSnapshot(context.Background(), params)
	assert.ErrorIs(t, err, activity.ErrResultPending)

	prompt, ok := receivedConfigJSON["prompt"].(string)
	require.True(t, ok, "prompt should be a string in config.json")
	assert.NotContains(t, prompt, "**Predecessor Artifacts**", "no annotation block when only .result entries present")
}

// TestExecuteAINodeFromSnapshot_RunInputAnnotation verifies that run-level attachments
// (keys prefixed "run_input/" in InputArtifacts) are announced to the LLM in a
// dedicated "Run Inputs" block, matching the predecessor-artifact behavior. Without
// this announcement the LLM has no way to learn about user-uploaded files —
// entrypoint.sh downloads them silently and the prompt would not mention them.
func TestExecuteAINodeFromSnapshot_RunInputAnnotation(t *testing.T) {
	var receivedConfigJSON map[string]interface{}

	apiServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodPost && strings.Contains(r.URL.Path, "/internal/workloads/"):
			var req map[string]interface{}
			json.NewDecoder(r.Body).Decode(&req)
			if cj, ok := req["configJson"].(map[string]interface{}); ok {
				receivedConfigJSON = cj
			}
			w.WriteHeader(http.StatusCreated)
			json.NewEncoder(w).Encode(map[string]interface{}{
				"executionHandle": "agent-abc12345",
				"jobSecretHash":   "hash123",
			})
		case r.Method == http.MethodPost && strings.HasSuffix(r.URL.Path, "/logs"):
			w.WriteHeader(http.StatusCreated)
		default:
			w.WriteHeader(http.StatusOK)
		}
	}))
	defer apiServer.Close()

	client := apiclient.NewClient(apiServer.URL)
	cfg := &config.Config{
		APIServerURL: apiServer.URL,
		Callback:     config.CallbackConfig{URL: "http://callback:9090/api/v1/callback"},
	}
	acts := NewActivities(client, prompt.NewResolver(), cfg, nil)

	params := ExecuteAINodeFromSnapshotParams{
		NodeExecutionID: uuid.New(),
		RunID:           uuid.New(),
		TemplateNodeID:  uuid.New(),
		Label:           "implement",
		ExecutorType:    "ai",
		PromptTemplate:  "Do the thing",
		InputArtifacts: map[string]string{
			"run_input/mockup.png": "system/runs/abc/inputs/mockup.png",  // run-level — must appear
			"run_input/spec.md":    "system/runs/abc/inputs/spec.md",     // run-level — must appear
			"input/gate/feedback":  "system/runs/abc/gate-attachments/x", // not run_input/ — must NOT appear in Run Inputs block
		},
		Iteration: 1,
	}

	err := acts.ExecuteAINodeFromSnapshot(context.Background(), params)
	assert.ErrorIs(t, err, activity.ErrResultPending)

	prompt, ok := receivedConfigJSON["prompt"].(string)
	require.True(t, ok, "prompt should be a string in config.json")
	assert.Contains(t, prompt, "**Run Inputs**", "prompt should contain the Run Inputs annotation header")
	assert.Contains(t, prompt, "run_input/mockup.png", "run-level input key should appear in annotation")
	assert.Contains(t, prompt, "run_input/spec.md", "run-level input key should appear in annotation")
	assert.Contains(t, prompt, "system/runs/abc/inputs/mockup.png", "run-level object storage path should appear in annotation")

	// Lines should be sorted (deterministic output)
	mockupIdx := strings.Index(prompt, "run_input/mockup.png")
	specIdx := strings.Index(prompt, "run_input/spec.md")
	require.True(t, mockupIdx > 0 && specIdx > 0)
	assert.Less(t, mockupIdx, specIdx, "annotation lines should be sorted alphabetically")

	// The non-run_input key must not be hoisted into the Run Inputs block. It can
	// legitimately appear elsewhere if there's also a Predecessor Artifacts block,
	// but that block is driven by Variables (not InputArtifacts) — and we passed
	// no Variables, so it must not appear at all.
	assert.NotContains(t, prompt, "**Predecessor Artifacts**", "no predecessor block when Variables has no input.* keys")
	assert.NotContains(t, prompt, "input/gate/feedback", "non-run_input/ keys must not be announced")
}

// TestExecuteAINodeFromSnapshot_NoRunInputs_NoAnnotation verifies that when no
// "run_input/" keys are present, the Run Inputs block is not added.
func TestExecuteAINodeFromSnapshot_NoRunInputs_NoAnnotation(t *testing.T) {
	var receivedConfigJSON map[string]interface{}

	apiServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodPost && strings.Contains(r.URL.Path, "/internal/workloads/"):
			var req map[string]interface{}
			json.NewDecoder(r.Body).Decode(&req)
			if cj, ok := req["configJson"].(map[string]interface{}); ok {
				receivedConfigJSON = cj
			}
			w.WriteHeader(http.StatusCreated)
			json.NewEncoder(w).Encode(map[string]interface{}{
				"executionHandle": "agent-abc12345",
				"jobSecretHash":   "hash123",
			})
		case r.Method == http.MethodPost && strings.HasSuffix(r.URL.Path, "/logs"):
			w.WriteHeader(http.StatusCreated)
		default:
			w.WriteHeader(http.StatusOK)
		}
	}))
	defer apiServer.Close()

	client := apiclient.NewClient(apiServer.URL)
	cfg := &config.Config{
		APIServerURL: apiServer.URL,
		Callback:     config.CallbackConfig{URL: "http://callback:9090/api/v1/callback"},
	}
	acts := NewActivities(client, prompt.NewResolver(), cfg, nil)

	params := ExecuteAINodeFromSnapshotParams{
		NodeExecutionID: uuid.New(),
		RunID:           uuid.New(),
		TemplateNodeID:  uuid.New(),
		Label:           "implement",
		ExecutorType:    "ai",
		PromptTemplate:  "Do the thing",
		InputArtifacts:  map[string]string{}, // empty — nothing to announce
		Iteration:       1,
	}

	err := acts.ExecuteAINodeFromSnapshot(context.Background(), params)
	assert.ErrorIs(t, err, activity.ErrResultPending)

	prompt, ok := receivedConfigJSON["prompt"].(string)
	require.True(t, ok, "prompt should be a string in config.json")
	assert.NotContains(t, prompt, "**Run Inputs**", "no Run Inputs block when InputArtifacts has no run_input/ keys")
}

// TestExecuteAINodeFromSnapshot_OutputSpec_Present verifies that when OutputSpec is a
// non-empty, non-"{}" JSON string, it is forwarded as "output_spec" in config.json.
func TestExecuteAINodeFromSnapshot_OutputSpec_Present(t *testing.T) {
	var receivedConfigJSON map[string]interface{}

	apiServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodPost && strings.Contains(r.URL.Path, "/internal/workloads/"):
			var req map[string]interface{}
			json.NewDecoder(r.Body).Decode(&req)
			if cj, ok := req["configJson"].(map[string]interface{}); ok {
				receivedConfigJSON = cj
			}
			w.WriteHeader(http.StatusCreated)
			json.NewEncoder(w).Encode(map[string]interface{}{
				"executionHandle": "agent-abc12345",
				"jobSecretHash":   "hash123",
			})
		case r.Method == http.MethodPost && strings.HasSuffix(r.URL.Path, "/logs"):
			w.WriteHeader(http.StatusCreated)
		default:
			w.WriteHeader(http.StatusOK)
		}
	}))
	defer apiServer.Close()

	client := apiclient.NewClient(apiServer.URL)
	cfg := &config.Config{
		APIServerURL: apiServer.URL,
		Callback:     config.CallbackConfig{URL: "http://callback:9090/api/v1/callback"},
	}
	acts := NewActivities(client, prompt.NewResolver(), cfg, nil)

	outputSpec := `{"files":[{"name":"report.pdf","required":true}]}`
	params := ExecuteAINodeFromSnapshotParams{
		NodeExecutionID: uuid.New(),
		RunID:           uuid.New(),
		TemplateNodeID:  uuid.New(),
		Label:           "generate-report",
		ExecutorType:    "ai",
		PromptTemplate:  "Generate a report",
		Variables:       map[string]string{"run.id": "abc123"},
		Iteration:       1,
		OutputSpec:      outputSpec,
	}

	err := acts.ExecuteAINodeFromSnapshot(context.Background(), params)
	assert.ErrorIs(t, err, activity.ErrResultPending)

	assert.Equal(t, outputSpec, receivedConfigJSON["output_spec"], "output_spec should be forwarded to config.json")
}

// TestExecuteAINodeFromSnapshot_OutputSpec_Empty verifies that when OutputSpec is empty,
// "output_spec" is absent from config.json.
func TestExecuteAINodeFromSnapshot_OutputSpec_Empty(t *testing.T) {
	var receivedConfigJSON map[string]interface{}

	apiServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodPost && strings.Contains(r.URL.Path, "/internal/workloads/"):
			var req map[string]interface{}
			json.NewDecoder(r.Body).Decode(&req)
			if cj, ok := req["configJson"].(map[string]interface{}); ok {
				receivedConfigJSON = cj
			}
			w.WriteHeader(http.StatusCreated)
			json.NewEncoder(w).Encode(map[string]interface{}{
				"executionHandle": "agent-abc12345",
				"jobSecretHash":   "hash123",
			})
		case r.Method == http.MethodPost && strings.HasSuffix(r.URL.Path, "/logs"):
			w.WriteHeader(http.StatusCreated)
		default:
			w.WriteHeader(http.StatusOK)
		}
	}))
	defer apiServer.Close()

	client := apiclient.NewClient(apiServer.URL)
	cfg := &config.Config{
		APIServerURL: apiServer.URL,
		Callback:     config.CallbackConfig{URL: "http://callback:9090/api/v1/callback"},
	}
	acts := NewActivities(client, prompt.NewResolver(), cfg, nil)

	params := ExecuteAINodeFromSnapshotParams{
		NodeExecutionID: uuid.New(),
		RunID:           uuid.New(),
		TemplateNodeID:  uuid.New(),
		Label:           "simple-node",
		ExecutorType:    "ai",
		PromptTemplate:  "Do something",
		Variables:       map[string]string{"run.id": "abc123"},
		Iteration:       1,
		OutputSpec:      "", // empty — should not appear in config.json
	}

	err := acts.ExecuteAINodeFromSnapshot(context.Background(), params)
	assert.ErrorIs(t, err, activity.ErrResultPending)

	_, exists := receivedConfigJSON["output_spec"]
	assert.False(t, exists, "output_spec should be absent from config.json when OutputSpec is empty")
}

// TestExecuteAINodeFromSnapshot_OutputSpec_EmptyObject verifies that when OutputSpec is "{}",
// "output_spec" is absent from config.json (treated the same as empty).
func TestExecuteAINodeFromSnapshot_OutputSpec_EmptyObject(t *testing.T) {
	var receivedConfigJSON map[string]interface{}

	apiServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodPost && strings.Contains(r.URL.Path, "/internal/workloads/"):
			var req map[string]interface{}
			json.NewDecoder(r.Body).Decode(&req)
			if cj, ok := req["configJson"].(map[string]interface{}); ok {
				receivedConfigJSON = cj
			}
			w.WriteHeader(http.StatusCreated)
			json.NewEncoder(w).Encode(map[string]interface{}{
				"executionHandle": "agent-abc12345",
				"jobSecretHash":   "hash123",
			})
		case r.Method == http.MethodPost && strings.HasSuffix(r.URL.Path, "/logs"):
			w.WriteHeader(http.StatusCreated)
		default:
			w.WriteHeader(http.StatusOK)
		}
	}))
	defer apiServer.Close()

	client := apiclient.NewClient(apiServer.URL)
	cfg := &config.Config{
		APIServerURL: apiServer.URL,
		Callback:     config.CallbackConfig{URL: "http://callback:9090/api/v1/callback"},
	}
	acts := NewActivities(client, prompt.NewResolver(), cfg, nil)

	params := ExecuteAINodeFromSnapshotParams{
		NodeExecutionID: uuid.New(),
		RunID:           uuid.New(),
		TemplateNodeID:  uuid.New(),
		Label:           "simple-node",
		ExecutorType:    "ai",
		PromptTemplate:  "Do something",
		Variables:       map[string]string{"run.id": "abc123"},
		Iteration:       1,
		OutputSpec:      "{}", // empty object — should not appear in config.json
	}

	err := acts.ExecuteAINodeFromSnapshot(context.Background(), params)
	assert.ErrorIs(t, err, activity.ErrResultPending)

	_, exists := receivedConfigJSON["output_spec"]
	assert.False(t, exists, "output_spec should be absent from config.json when OutputSpec is \"{}\"")
}

// TestExecuteAINodeFromSnapshot_ModelInConfigJson verifies that NodeDefinition.model
// is propagated to the agent via config.json["model"] when set.
func TestExecuteAINodeFromSnapshot_ModelInConfigJson(t *testing.T) {
	var receivedConfigJSON map[string]interface{}

	apiServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodPost && strings.Contains(r.URL.Path, "/internal/workloads/"):
			var req map[string]interface{}
			json.NewDecoder(r.Body).Decode(&req)
			if cj, ok := req["configJson"].(map[string]interface{}); ok {
				receivedConfigJSON = cj
			}
			w.WriteHeader(http.StatusCreated)
			json.NewEncoder(w).Encode(map[string]interface{}{
				"executionHandle": "agent-abc12345",
				"jobSecretHash":   "hash123",
			})
		default:
			w.WriteHeader(http.StatusOK)
		}
	}))
	defer apiServer.Close()

	client := apiclient.NewClient(apiServer.URL)
	cfg := &config.Config{
		APIServerURL: apiServer.URL,
		Callback:     config.CallbackConfig{URL: "http://callback:9090/api/v1/callback"},
	}
	acts := NewActivities(client, prompt.NewResolver(), cfg, nil)

	err := acts.ExecuteAINodeFromSnapshot(context.Background(), ExecuteAINodeFromSnapshotParams{
		NodeExecutionID: uuid.New(),
		RunID:           uuid.New(),
		TemplateNodeID:  uuid.New(),
		Label:           "push_pr",
		ExecutorType:    "ai",
		PromptTemplate:  "irrelevant",
		Model:           "claude-haiku-4-5-20251001",
	})
	assert.ErrorIs(t, err, activity.ErrResultPending)

	assert.Equal(t, "claude-haiku-4-5-20251001", receivedConfigJSON["model"],
		"config.json must include model when NodeDefinition.model is set")
}

// TestExecuteAINodeFromSnapshot_ModelOmittedWhenEmpty verifies model is omitted from
// config.json when not set (so the agent falls back to its default model).
func TestExecuteAINodeFromSnapshot_ModelOmittedWhenEmpty(t *testing.T) {
	var receivedConfigJSON map[string]interface{}

	apiServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodPost && strings.Contains(r.URL.Path, "/internal/workloads/"):
			var req map[string]interface{}
			json.NewDecoder(r.Body).Decode(&req)
			if cj, ok := req["configJson"].(map[string]interface{}); ok {
				receivedConfigJSON = cj
			}
			w.WriteHeader(http.StatusCreated)
			json.NewEncoder(w).Encode(map[string]interface{}{
				"executionHandle": "agent-abc12345",
				"jobSecretHash":   "hash123",
			})
		default:
			w.WriteHeader(http.StatusOK)
		}
	}))
	defer apiServer.Close()

	client := apiclient.NewClient(apiServer.URL)
	cfg := &config.Config{
		APIServerURL: apiServer.URL,
		Callback:     config.CallbackConfig{URL: "http://callback:9090/api/v1/callback"},
	}
	acts := NewActivities(client, prompt.NewResolver(), cfg, nil)

	err := acts.ExecuteAINodeFromSnapshot(context.Background(), ExecuteAINodeFromSnapshotParams{
		NodeExecutionID: uuid.New(),
		RunID:           uuid.New(),
		TemplateNodeID:  uuid.New(),
		Label:           "spec_review",
		ExecutorType:    "ai",
		PromptTemplate:  "irrelevant",
		// Model intentionally not set
	})
	assert.ErrorIs(t, err, activity.ErrResultPending)

	_, hasModel := receivedConfigJSON["model"]
	assert.False(t, hasModel, "config.json must omit model when not set on the snapshot")
}

// TestLoadReviewHistoryJSON_PreservesFeedbackText is the end-to-end regression test for
// the bug where the reviewer's feedback never reached the Roadmap Analyzer's
// {review_history} prompt variable. It exercises the full path from the API server's
// HTTP response through GetReviewHistory's decode into the final JSON string handed to
// the prompt resolver, covering both a combined live-chat+feedback entry and a
// plain-approve entry with blank feedback.
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

	jsonStr, err := acts.LoadReviewHistoryJSON(context.Background(), LoadReviewHistoryJSONParams{
		RunID:     runID,
		LoopGroup: "proposal-review",
	})
	require.NoError(t, err)

	// (a) the combined live-chat + feedback text must round-trip intact.
	assert.Contains(t, jsonStr, "## Chat Transcript")
	assert.Contains(t, jsonStr, "## Reviewer Feedback")
	assert.Contains(t, jsonStr, "Please avoid duplicating the CSV import epic")

	// (b) the blank-feedback entry must marshal as a clean empty string, not a bare
	// null literal or an omitted key, and the whole blob must remain valid JSON.
	var decoded []map[string]interface{}
	require.NoError(t, json.Unmarshal([]byte(jsonStr), &decoded))
	require.Len(t, decoded, 2)
	assert.Equal(t, "", decoded[1]["Result"])
	assert.NotContains(t, jsonStr, `"Result":null`)
}

func testConfig() *config.Config {
	return &config.Config{
		APIServerURL: "http://localhost:8080",
		Callback: config.CallbackConfig{
			URL: "http://orchestrator:9090/api/v1/callback",
		},
	}
}
