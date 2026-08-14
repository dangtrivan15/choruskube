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

func TestExecuteAINodeFromSnapshot_TaskContextIncludesOpenBlockers(t *testing.T) {
	var receivedConfigJSON map[string]interface{}

	runID := uuid.New()
	taskID := uuid.New()
	blockerID := uuid.New()

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
		TaskTitle:       "Wire up open blockers",
		OpenBlockers: []OpenBlockerParam{
			{ItemType: "task", ItemID: blockerID.String(), Title: "Prerequisite Task", Status: "in_progress"},
		},
	}

	err := acts.ExecuteAINodeFromSnapshot(context.Background(), params)
	assert.ErrorIs(t, err, activity.ErrResultPending)

	taskContext, ok := receivedConfigJSON["task_context"].(map[string]interface{})
	require.True(t, ok, "task_context should be present in config.json when TaskID is set")
	openBlockers, ok := taskContext["open_blockers"].([]interface{})
	require.True(t, ok, "open_blockers should be present in task_context when OpenBlockers is non-empty")
	require.Len(t, openBlockers, 1)
	blocker, ok := openBlockers[0].(map[string]interface{})
	require.True(t, ok)
	assert.Equal(t, "task", blocker["item_type"])
	assert.Equal(t, blockerID.String(), blocker["item_id"])
	assert.Equal(t, "Prerequisite Task", blocker["title"])
	assert.Equal(t, "in_progress", blocker["status"])
}

func TestExecuteAINodeFromSnapshot_NoOpenBlockersKeyWhenEmpty(t *testing.T) {
	var receivedConfigJSON map[string]interface{}

	runID := uuid.New()
	taskID := uuid.New()

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

	// TaskID set (task_context present) but OpenBlockers left empty — the run's Task has no
	// open blockers today.
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
		TaskTitle:       "No blockers here",
	}

	err := acts.ExecuteAINodeFromSnapshot(context.Background(), params)
	assert.ErrorIs(t, err, activity.ErrResultPending)

	taskContext, ok := receivedConfigJSON["task_context"].(map[string]interface{})
	require.True(t, ok, "task_context should be present in config.json when TaskID is set")
	_, hasOpenBlockers := taskContext["open_blockers"]
	assert.False(t, hasOpenBlockers, "open_blockers key should be absent from task_context when OpenBlockers is empty")
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
	// Regression guard: the iteration-cap epoch machinery was removed; make sure
	// it doesn't silently reappear in the agent's config.json.
	_, hasEpochKey := receivedConfigJSON["iteration_in_epoch"]
	assert.False(t, hasEpochKey, "iteration_in_epoch should not be present in config.json")
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

// TestExecuteAINodeFromSnapshot_PredecessorArtifactAnnotation_MaterialisedFiltered pins the
// interaction between the two maps that describe the same predecessor files: Variables drives
// the prompt suffix (keyed "input.{label}.{filename}") while InputArtifacts drives what the
// entrypoint downloads to /workspace/in/{label}/{filename} before the agent starts. A file in
// the latter is already on disk, so advertising it as something to `artifact get` is wrong —
// it must be filtered out, matching on the translated key rather than the raw string.
func TestExecuteAINodeFromSnapshot_PredecessorArtifactAnnotation_MaterialisedFiltered(t *testing.T) {
	tests := []struct {
		name           string
		variables      map[string]string
		inputArtifacts map[string]string
		wantBlock      bool
		wantPresent    []string
		wantAbsent     []string
	}{
		{
			name: "materialised entry is omitted, un-materialised sibling stays",
			variables: map[string]string{
				"input.spec_review.result":           "approved",
				"input.spec_review.spec_and_plan.md": "system/runs/abc/out/spec_and_plan.md",
				"input.spec_review.notes.md":         "system/runs/abc/out/notes.md",
			},
			inputArtifacts: map[string]string{
				"spec_review/spec_and_plan.md": "system/runs/abc/out/spec_and_plan.md",
			},
			wantBlock:   true,
			wantPresent: []string{"input.spec_review.notes.md", "system/runs/abc/out/notes.md"},
			wantAbsent:  []string{"input.spec_review.spec_and_plan.md"},
		},
		{
			name: "un-materialised entry still appears when nothing was downloaded",
			variables: map[string]string{
				"input.gate.human_guidance.md": "system/runs/abc/gate/human_guidance.md",
			},
			inputArtifacts: map[string]string{},
			wantBlock:      true,
			wantPresent:    []string{"input.gate.human_guidance.md"},
		},
		{
			name: "same filename under a different label is not treated as materialised",
			variables: map[string]string{
				"input.gate.human_guidance.md": "system/runs/abc/gate/human_guidance.md",
			},
			inputArtifacts: map[string]string{
				"older_gate/human_guidance.md": "system/runs/abc/older-gate/human_guidance.md",
			},
			wantBlock:   true,
			wantPresent: []string{"input.gate.human_guidance.md"},
		},
		{
			name: "fully materialised set produces no suffix at all",
			variables: map[string]string{
				"input.spec_review.result":           "approved",
				"input.spec_review.spec_and_plan.md": "system/runs/abc/out/spec_and_plan.md",
				"input.gate.human_guidance.md":       "system/runs/abc/gate/human_guidance.md",
			},
			inputArtifacts: map[string]string{
				"spec_review/spec_and_plan.md": "system/runs/abc/out/spec_and_plan.md",
				"gate/human_guidance.md":       "system/runs/abc/gate/human_guidance.md",
			},
			wantBlock:  false,
			wantAbsent: []string{"input.spec_review.spec_and_plan.md", "input.gate.human_guidance.md"},
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
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

			err := acts.ExecuteAINodeFromSnapshot(context.Background(), ExecuteAINodeFromSnapshotParams{
				NodeExecutionID: uuid.New(),
				RunID:           uuid.New(),
				TemplateNodeID:  uuid.New(),
				Label:           "implement",
				ExecutorType:    "ai",
				PromptTemplate:  "Do the thing",
				Variables:       tt.variables,
				InputArtifacts:  tt.inputArtifacts,
				Iteration:       1,
			})
			assert.ErrorIs(t, err, activity.ErrResultPending)

			resolvedPrompt, ok := receivedConfigJSON["prompt"].(string)
			require.True(t, ok, "prompt should be a string in config.json")

			if tt.wantBlock {
				assert.Contains(t, resolvedPrompt, "**Predecessor Artifacts**",
					"un-materialised entries remain, so the block should be emitted")
			} else {
				assert.NotContains(t, resolvedPrompt, "**Predecessor Artifacts**",
					"nothing left after filtering, so no block should be emitted")
			}
			for _, want := range tt.wantPresent {
				assert.Contains(t, resolvedPrompt, want, "expected entry missing from annotation")
			}
			for _, notWant := range tt.wantAbsent {
				assert.NotContains(t, resolvedPrompt, notWant,
					"materialised file must not be advertised as a manual download")
			}
		})
	}
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

// TestConfigJSON_SupervisorEmittedOnlyWhenDeclared verifies that config.json carries a
// "supervisor" key exactly when SupervisorLabel is set, and that the key is absent
// entirely — not present-but-empty — when it isn't, so an older template's config.json
// keeps its exact current shape.
func TestConfigJSON_SupervisorEmittedOnlyWhenDeclared(t *testing.T) {
	tests := []struct {
		name            string
		supervisorLabel string
		wantPresent     bool
	}{
		{
			name:            "label set — supervisor key present with label and name",
			supervisorLabel: "qa-lead",
			wantPresent:     true,
		},
		{
			name:            "label empty — supervisor key absent entirely",
			supervisorLabel: "",
			wantPresent:     false,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
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
				Variables:       map[string]string{"run.id": "abc123"},
				Iteration:       1,
				SupervisorLabel: tt.supervisorLabel,
			}

			err := acts.ExecuteAINodeFromSnapshot(context.Background(), params)
			assert.ErrorIs(t, err, activity.ErrResultPending)

			supervisor, exists := receivedConfigJSON["supervisor"]
			if !tt.wantPresent {
				assert.False(t, exists, "supervisor key should be absent from config.json when SupervisorLabel is empty")
				return
			}
			require.True(t, exists, "supervisor key should be present in config.json when SupervisorLabel is set")
			assert.Equal(t, map[string]interface{}{
				"label": tt.supervisorLabel,
				"name":  "Supervisor",
			}, supervisor)
		})
	}
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

// TestExecuteAINodeFromSnapshot_EffortInConfigJson verifies that the effort override
// extracted from config_overrides is propagated to the agent via config.json["effort"]
// when set.
func TestExecuteAINodeFromSnapshot_EffortInConfigJson(t *testing.T) {
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
		Label:           "code_review",
		ExecutorType:    "ai",
		PromptTemplate:  "irrelevant",
		Effort:          "xhigh",
	})
	assert.ErrorIs(t, err, activity.ErrResultPending)

	assert.Equal(t, "xhigh", receivedConfigJSON["effort"],
		"config.json must include effort when set on the snapshot")
}

// TestExecuteAINodeFromSnapshot_EffortOmittedWhenEmpty verifies effort is omitted from
// config.json when not set (not present as an empty string).
func TestExecuteAINodeFromSnapshot_EffortOmittedWhenEmpty(t *testing.T) {
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
		// Effort intentionally not set
	})
	assert.ErrorIs(t, err, activity.ErrResultPending)

	_, hasEffort := receivedConfigJSON["effort"]
	assert.False(t, hasEffort, "config.json must omit effort when not set on the snapshot")
}

// TestExecuteAINodeFromSnapshot_ResolvedModelEffortReachConfigJSONUnchanged_BothIterationBands
// covers the spec's Integration testing bullet for the per-node-type
// model/effort feature: dag_executor.go resolves the four new iteration-aware
// config_overrides keys (model_first_iteration/model_subsequent_iteration/
// effort_first_iteration/effort_subsequent_iteration) down to a single
// concrete Model/Effort pair BEFORE calling this activity — this activity
// itself is unchanged (Decision 2/§3.2) and only ever sees that resolved
// pair via ExecuteAINodeFromSnapshotParams.Model/.Effort, never the raw
// iteration-suffixed keys. This test simulates both bands the DAG executor
// can hand it (a first-iteration resolution and a subsequent-iteration one)
// and confirms each reaches config.json verbatim as plain "model"/"effort"
// keys, proving the pass-through stays generic across both.
func TestExecuteAINodeFromSnapshot_ResolvedModelEffortReachConfigJSONUnchanged_BothIterationBands(t *testing.T) {
	newServerAndActs := func(dst *map[string]interface{}) *Activities {
		apiServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			switch {
			case r.Method == http.MethodPost && strings.Contains(r.URL.Path, "/internal/workloads/"):
				var req map[string]interface{}
				json.NewDecoder(r.Body).Decode(&req)
				if cj, ok := req["configJson"].(map[string]interface{}); ok {
					*dst = cj
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
		t.Cleanup(apiServer.Close)

		client := apiclient.NewClient(apiServer.URL)
		cfg := &config.Config{
			APIServerURL: apiServer.URL,
			Callback:     config.CallbackConfig{URL: "http://callback:9090/api/v1/callback"},
		}
		return NewActivities(client, prompt.NewResolver(), cfg, nil)
	}

	// Band 1: as dag_executor.go resolves it on tracker.reviewPass == 1 (from
	// model_first_iteration/effort_first_iteration).
	var firstIterationConfigJSON map[string]interface{}
	firstActs := newServerAndActs(&firstIterationConfigJSON)
	err := firstActs.ExecuteAINodeFromSnapshot(context.Background(), ExecuteAINodeFromSnapshotParams{
		NodeExecutionID: uuid.New(),
		RunID:           uuid.New(),
		TemplateNodeID:  uuid.New(),
		Label:           "code_review",
		ExecutorType:    "ai",
		PromptTemplate:  "irrelevant",
		Model:           "opus-x",
		Effort:          "xhigh",
		Iteration:       1,
	})
	assert.ErrorIs(t, err, activity.ErrResultPending)
	assert.Equal(t, "opus-x", firstIterationConfigJSON["model"],
		"config.json must carry the resolved first-iteration model unchanged")
	assert.Equal(t, "xhigh", firstIterationConfigJSON["effort"],
		"config.json must carry the resolved first-iteration effort unchanged")
	assert.NotContains(t, firstIterationConfigJSON, "model_first_iteration",
		"config.json must never carry the raw iteration-suffixed key")
	assert.NotContains(t, firstIterationConfigJSON, "model_subsequent_iteration",
		"config.json must never carry the raw iteration-suffixed key")

	// Band 2: as dag_executor.go resolves it on tracker.reviewPass > 1 (from
	// model_subsequent_iteration/effort_subsequent_iteration).
	var subsequentIterationConfigJSON map[string]interface{}
	subsequentActs := newServerAndActs(&subsequentIterationConfigJSON)
	err = subsequentActs.ExecuteAINodeFromSnapshot(context.Background(), ExecuteAINodeFromSnapshotParams{
		NodeExecutionID: uuid.New(),
		RunID:           uuid.New(),
		TemplateNodeID:  uuid.New(),
		Label:           "code_review",
		ExecutorType:    "ai",
		PromptTemplate:  "irrelevant",
		Model:           "sonnet-y",
		Effort:          "high",
		Iteration:       2,
	})
	assert.ErrorIs(t, err, activity.ErrResultPending)
	assert.Equal(t, "sonnet-y", subsequentIterationConfigJSON["model"],
		"config.json must carry the resolved subsequent-iteration model unchanged")
	assert.Equal(t, "high", subsequentIterationConfigJSON["effort"],
		"config.json must carry the resolved subsequent-iteration effort unchanged")
	assert.NotContains(t, subsequentIterationConfigJSON, "effort_first_iteration",
		"config.json must never carry the raw iteration-suffixed key")
	assert.NotContains(t, subsequentIterationConfigJSON, "effort_subsequent_iteration",
		"config.json must never carry the raw iteration-suffixed key")
}

// TestExecuteAINodeFromSnapshot_TurnBudgetInConfigJson verifies that the per-node
// max_turns/max_retries overrides extracted from config_overrides reach the agent via
// config.json when set.
func TestExecuteAINodeFromSnapshot_TurnBudgetInConfigJson(t *testing.T) {
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
		Label:           "implement",
		ExecutorType:    "ai",
		PromptTemplate:  "irrelevant",
		MaxTurns:        "250",
		MaxRetries:      "5",
	})
	assert.ErrorIs(t, err, activity.ErrResultPending)

	assert.Equal(t, "250", receivedConfigJSON["max_turns"],
		"config.json must include max_turns when set on the snapshot")
	assert.Equal(t, "5", receivedConfigJSON["max_retries"],
		"config.json must include max_retries when set on the snapshot")
}

// TestExecuteAINodeFromSnapshot_TurnBudgetOmittedWhenEmpty verifies max_turns/max_retries
// are absent from config.json when unset — not present as empty strings, which the agent
// would have to special-case instead of simply falling back to its own defaults. The two
// default independently, so this also covers one being set without the other.
func TestExecuteAINodeFromSnapshot_TurnBudgetOmittedWhenEmpty(t *testing.T) {
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
		MaxTurns:        "150",
		// MaxRetries intentionally not set
	})
	assert.ErrorIs(t, err, activity.ErrResultPending)

	assert.Equal(t, "150", receivedConfigJSON["max_turns"],
		"config.json must include max_turns when set on the snapshot")
	_, hasMaxRetries := receivedConfigJSON["max_retries"]
	assert.False(t, hasMaxRetries, "config.json must omit max_retries when not set on the snapshot")

	receivedConfigJSON = nil
	err = acts.ExecuteAINodeFromSnapshot(context.Background(), ExecuteAINodeFromSnapshotParams{
		NodeExecutionID: uuid.New(),
		RunID:           uuid.New(),
		TemplateNodeID:  uuid.New(),
		Label:           "spec_review",
		ExecutorType:    "ai",
		PromptTemplate:  "irrelevant",
		// Neither MaxTurns nor MaxRetries set
	})
	assert.ErrorIs(t, err, activity.ErrResultPending)

	_, hasMaxTurns := receivedConfigJSON["max_turns"]
	assert.False(t, hasMaxTurns, "config.json must omit max_turns when not set on the snapshot")
	_, hasMaxRetries = receivedConfigJSON["max_retries"]
	assert.False(t, hasMaxRetries, "config.json must omit max_retries when not set on the snapshot")
}

// TestExecuteAINodeFromSnapshot_NeedsPRInConfigJson verifies that NeedsPR true (derived
// from config_overrides.needs_pr == "true") is propagated to the agent via
// config.json["needs_pr"].
func TestExecuteAINodeFromSnapshot_NeedsPRInConfigJson(t *testing.T) {
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
		Label:           "implement",
		ExecutorType:    "ai",
		PromptTemplate:  "irrelevant",
		NeedsPR:         true,
	})
	assert.ErrorIs(t, err, activity.ErrResultPending)

	assert.Equal(t, true, receivedConfigJSON["needs_pr"],
		"config.json must include needs_pr=true when NeedsPR is set on the snapshot")
}

// TestExecuteAINodeFromSnapshot_NeedsPROmittedWhenFalse verifies needs_pr is omitted from
// config.json when NeedsPR is false (the zero value, i.e. not set via config_overrides).
func TestExecuteAINodeFromSnapshot_NeedsPROmittedWhenFalse(t *testing.T) {
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
		// NeedsPR intentionally not set
	})
	assert.ErrorIs(t, err, activity.ErrResultPending)

	_, hasNeedsPR := receivedConfigJSON["needs_pr"]
	assert.False(t, hasNeedsPR, "config.json must omit needs_pr when not set on the snapshot")
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
