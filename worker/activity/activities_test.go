package activity

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"reflect"
	"regexp"
	"strings"
	"testing"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	temporalactivity "go.temporal.io/sdk/activity"
	"go.temporal.io/sdk/workflow"

	"github.com/dangtrivan15/choruskube/worker/callback"
	"github.com/dangtrivan15/choruskube/worker/executor"
	"github.com/dangtrivan15/choruskube/worker/workload"
)

// newTestActivities starts a stub API server that accepts CreateWorkload POSTs, capturing the
// last request's configJson into *captured, and returns Activities wired to call it.
func newTestActivities(t *testing.T, captured *map[string]interface{}) *Activities {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodPost && strings.HasSuffix(r.URL.Path, "/workload") {
			var req map[string]interface{}
			_ = json.NewDecoder(r.Body).Decode(&req)
			if cj, ok := req["configJson"].(map[string]interface{}); ok {
				*captured = cj
			}
			w.WriteHeader(http.StatusCreated)
			_ = json.NewEncoder(w).Encode(map[string]interface{}{
				"executionHandle": "agent-abc12345",
				"jobSecretHash":   "hash123",
			})
			return
		}
		w.WriteHeader(http.StatusOK)
	}))
	t.Cleanup(srv.Close)

	acts := New(workload.NewClient(srv.URL, func() string { return "test-secret" }, srv.Client()))
	acts.CallbackURL = "http://callback:9090/api/v1/callback"
	acts.APIServerURL = srv.URL
	return acts
}

// stubbedRun pins the activity context to a fresh run and returns its id, so a test can pass the
// same id in the activity's parameters. Without it every activity call reaches the SDK's real
// GetInfo, which panics outside a running activity.
func stubbedRun(t *testing.T) uuid.UUID {
	t.Helper()
	id := uuid.New()
	original := activityInfo
	activityInfo = func(context.Context) temporalactivity.Info {
		return temporalactivity.Info{WorkflowExecution: workflow.Execution{ID: "choruskube-run-" + id.String()}}
	}
	t.Cleanup(func() { activityInfo = original })
	return id
}

func requirePending(t *testing.T, err error) {
	t.Helper()
	if !errors.Is(err, temporalactivity.ErrResultPending) {
		t.Fatalf("want temporalactivity.ErrResultPending, got %v", err)
	}
}

// mockExecutor implements executor.Executor with func fields, so a test wires up only the
// method it exercises; every other call panics via a nil func value, which fails the test
// loudly instead of masking an unexpected call with a quiet zero value.
type mockExecutor struct {
	executeFn func(ctx context.Context, params executor.ExecutionParams) (executor.ExecutionResult, error)
}

func (m *mockExecutor) Execute(ctx context.Context, params executor.ExecutionParams) (executor.ExecutionResult, error) {
	return m.executeFn(ctx, params)
}
func (m *mockExecutor) Cleanup(ctx context.Context, executionID uuid.UUID) error   { return nil }
func (m *mockExecutor) Terminate(ctx context.Context, executionID uuid.UUID) error { return nil }
func (m *mockExecutor) GetLogs(ctx context.Context, executionID uuid.UUID, tailLines int) (string, error) {
	return "", nil
}
func (m *mockExecutor) ResolveJobSecretHash(ctx context.Context, executionID uuid.UUID) (string, error) {
	return "", nil
}
func (m *mockExecutor) HealthCheck(ctx context.Context) error { return nil }

var _ executor.Executor = (*mockExecutor)(nil)

// mockWorkloadClient implements workloadClient with func fields, mirroring mockExecutor: a test
// stubs only the method(s) it exercises, and an un-stubbed one it does call fails loudly (nil
// func panic) instead of silently returning a zero value. WriteExecutionLog is the exception,
// like CleanupWorkload/GetWorkloadLogs above: it is a best-effort, fire-and-forget call made on
// every execution, so it defaults to a no-op and only tests asserting on it set writeLogFn.
type mockWorkloadClient struct {
	createFn   func(ctx context.Context, p workload.CreateWorkloadParams) (*workload.CreateWorkloadResponse, error)
	prepareFn  func(ctx context.Context, p workload.PrepareParams) (*workload.PrepareResponse, error)
	completeFn func(ctx context.Context, p workload.CompleteParams) error
	writeLogFn func(ctx context.Context, runID, nodeExecID uuid.UUID, level, message string)
}

func (m *mockWorkloadClient) CreateWorkload(ctx context.Context, p workload.CreateWorkloadParams) (*workload.CreateWorkloadResponse, error) {
	return m.createFn(ctx, p)
}
func (m *mockWorkloadClient) CleanupWorkload(ctx context.Context, runID, nodeExecID uuid.UUID) error {
	return nil
}
func (m *mockWorkloadClient) GetWorkloadLogs(ctx context.Context, runID, nodeExecID uuid.UUID, tailLines int) (string, error) {
	return "", nil
}
func (m *mockWorkloadClient) PrepareWorkload(ctx context.Context, p workload.PrepareParams) (*workload.PrepareResponse, error) {
	return m.prepareFn(ctx, p)
}
func (m *mockWorkloadClient) CompleteWorkload(ctx context.Context, p workload.CompleteParams) error {
	return m.completeFn(ctx, p)
}
func (m *mockWorkloadClient) WriteExecutionLog(ctx context.Context, runID, nodeExecID uuid.UUID, level, message string) {
	if m.writeLogFn != nil {
		m.writeLogFn(ctx, runID, nodeExecID, level, message)
	}
}

var _ workloadClient = (*mockWorkloadClient)(nil)

// TestExecuteAINodeFromSnapshot_CallsExecutor pins the restructured local-execution flow:
// prepare (resolve credentials) -> generate a job secret -> executor.Execute -> cache the
// returned hash -> complete (report the result). This is the path NewWithExecutor's Activities
// take instead of the legacy CreateWorkload delegation.
func TestExecuteAINodeFromSnapshot_CallsExecutor(t *testing.T) {
	nodeExecID := uuid.New()
	nodeID := uuid.New()
	runID := stubbedRun(t)

	prepareResp := &workload.PrepareResponse{
		Image:            "ghcr.io/test/agent:latest",
		EnableDocker:     false,
		ClaudeOAuthToken: "tok_test",
		GitHubTokenURL:   "http://api-server.invalid/internal/runs/x/node-executions/y/github-token",
		Namespace:        "org-ns",
		ServiceAccount:   "choruskube-agent",
	}

	var executedParams executor.ExecutionParams
	mockExec := &mockExecutor{
		executeFn: func(ctx context.Context, params executor.ExecutionParams) (executor.ExecutionResult, error) {
			executedParams = params
			return executor.ExecutionResult{PodName: "agent-abc", JobSecretHash: "hash123"}, nil
		},
	}

	var preparedParams workload.PrepareParams
	var completedParams workload.CompleteParams
	mockClient := &mockWorkloadClient{
		prepareFn: func(ctx context.Context, p workload.PrepareParams) (*workload.PrepareResponse, error) {
			preparedParams = p
			return prepareResp, nil
		},
		completeFn: func(ctx context.Context, p workload.CompleteParams) error {
			completedParams = p
			return nil
		},
	}

	hashCache := callback.NewHashCache()
	acts := NewWithExecutor(mockClient, mockExec, hashCache)
	acts.CallbackURL = "http://worker:9090/api/v1/callback"
	acts.APIServerURL = "http://api-server.invalid"

	params := ExecuteAINodeFromSnapshotParams{
		Identity: Identity{
			NodeExecutionID: nodeExecID,
			RunID:           runID,
			TemplateNodeID:  nodeID,
		},
		Node: Node{
			ExecutorType:   "ai",
			PromptTemplate: "irrelevant",
		},
	}

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), params)
	// Returns temporalactivity.ErrResultPending — the activity waits for the callback.
	assert.ErrorIs(t, err, temporalactivity.ErrResultPending)

	// prepare was called for this run/execution, carrying the same config.json the legacy
	// path would otherwise have sent straight to CreateWorkload.
	assert.Equal(t, runID, preparedParams.RunID)
	assert.Equal(t, nodeExecID, preparedParams.NodeExecID)
	assert.Equal(t, nodeExecID.String(), preparedParams.ConfigJSON["node_execution_id"])
	assert.Equal(t, acts.CallbackURL, preparedParams.ConfigJSON["callback_url"])

	// executor was called with prepare's resolved image/credentials/identity, plus a freshly
	// generated job secret and this Worker's own callback URL.
	assert.Equal(t, "ghcr.io/test/agent:latest", executedParams.Image)
	assert.NotEmpty(t, executedParams.JobSecret)
	assert.Equal(t, "http://worker:9090/api/v1/callback", executedParams.CallbackURL)
	assert.Equal(t, "tok_test", executedParams.Credentials.ClaudeOAuthToken)
	assert.Equal(t, "org-ns", executedParams.Identity.Namespace)
	assert.Equal(t, "choruskube-agent", executedParams.Identity.ServiceAccount)
	assert.Nil(t, executedParams.Credentials.Registry)

	// complete reported exactly what the executor returned.
	assert.Equal(t, runID, completedParams.RunID)
	assert.Equal(t, nodeExecID, completedParams.NodeExecID)
	assert.Equal(t, "agent-abc", completedParams.PodName)
	assert.Equal(t, "hash123", completedParams.JobSecretHash)

	// the hash was cached under the node execution id, for the callback handler to verify
	// the agent's own completion POST against.
	cached, ok := hashCache.Get(nodeExecID)
	assert.True(t, ok)
	assert.Equal(t, "hash123", cached)

	// the activity's own Temporal addressing was cached under the same id, for the Worker's
	// completer to complete-by-id once the agent calls back.
	pending, ok := acts.Pending.Get(nodeExecID)
	assert.True(t, ok)
	assert.Equal(t, "choruskube-run-"+runID.String(), pending.WorkflowID)
}

// TestExecuteAINodeFromSnapshot_CallsExecutor_CachesFullPendingCompletion pins every field
// PendingCompletion carries, not just WorkflowID: the Worker's completer addresses both the
// right Temporal activity and the right per-Fleet connection from Namespace and TaskQueue alone,
// so a gap in either silently misroutes every completion for this execution.
func TestExecuteAINodeFromSnapshot_CallsExecutor_CachesFullPendingCompletion(t *testing.T) {
	nodeExecID := uuid.New()
	runID := uuid.New()

	original := activityInfo
	activityInfo = func(context.Context) temporalactivity.Info {
		return temporalactivity.Info{
			Namespace:         "org-ns",
			TaskQueue:         "org-queue",
			ActivityID:        nodeExecID.String(),
			WorkflowExecution: workflow.Execution{ID: "choruskube-run-" + runID.String()},
		}
	}
	t.Cleanup(func() { activityInfo = original })

	mockExec := &mockExecutor{
		executeFn: func(ctx context.Context, params executor.ExecutionParams) (executor.ExecutionResult, error) {
			return executor.ExecutionResult{PodName: "agent-abc", JobSecretHash: "hash123"}, nil
		},
	}
	mockClient := &mockWorkloadClient{
		prepareFn: func(ctx context.Context, p workload.PrepareParams) (*workload.PrepareResponse, error) {
			return &workload.PrepareResponse{Image: "ghcr.io/test/agent:latest"}, nil
		},
		completeFn: func(ctx context.Context, p workload.CompleteParams) error { return nil },
	}

	acts := NewWithExecutor(mockClient, mockExec, callback.NewHashCache())
	acts.CallbackURL = "http://worker:9090/api/v1/callback"
	acts.APIServerURL = "http://api-server.invalid"

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), ExecuteAINodeFromSnapshotParams{
		Identity: Identity{
			NodeExecutionID: nodeExecID,
			RunID:           runID,
		},
		Node: Node{
			ExecutorType:   "ai",
			PromptTemplate: "irrelevant",
		},
	})
	requirePending(t, err)

	pending, ok := acts.Pending.Get(nodeExecID)
	assert.True(t, ok)
	assert.Equal(t, "org-ns", pending.Namespace)
	assert.Equal(t, "org-queue", pending.TaskQueue)
	assert.Equal(t, "choruskube-run-"+runID.String(), pending.WorkflowID)
	assert.Equal(t, nodeExecID.String(), pending.ActivityID)
}

// TestExecuteAINodeFromSnapshot_CallsExecutor_ForwardsRegistryCredentials verifies the
// PrepareResponse.Registry -> ExecutionParams.Credentials.Registry translation, which the happy
// path above deliberately leaves nil to also cover the no-registry case.
func TestExecuteAINodeFromSnapshot_CallsExecutor_ForwardsRegistryCredentials(t *testing.T) {
	var executedParams executor.ExecutionParams
	mockExec := &mockExecutor{
		executeFn: func(ctx context.Context, params executor.ExecutionParams) (executor.ExecutionResult, error) {
			executedParams = params
			return executor.ExecutionResult{PodName: "agent-abc", JobSecretHash: "hash123"}, nil
		},
	}
	mockClient := &mockWorkloadClient{
		prepareFn: func(ctx context.Context, p workload.PrepareParams) (*workload.PrepareResponse, error) {
			return &workload.PrepareResponse{
				Image: "ghcr.io/test/agent:latest",
				Registry: &workload.RegistryCredentials{
					Host:     "registry.example.com",
					Username: "worker",
					Password: "s3cr3t",
				},
			}, nil
		},
		completeFn: func(ctx context.Context, p workload.CompleteParams) error { return nil },
	}

	acts := NewWithExecutor(mockClient, mockExec, callback.NewHashCache())
	acts.CallbackURL = "http://worker:9090/api/v1/callback"
	acts.APIServerURL = "http://api-server.invalid"

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), ExecuteAINodeFromSnapshotParams{
		Identity: Identity{
			NodeExecutionID: uuid.New(),
			RunID:           stubbedRun(t),
			TemplateNodeID:  uuid.New(),
		},
		Node: Node{
			ExecutorType:   "ai",
			PromptTemplate: "irrelevant",
		},
	})
	assert.ErrorIs(t, err, temporalactivity.ErrResultPending)

	if assert.NotNil(t, executedParams.Credentials.Registry) {
		assert.Equal(t, "registry.example.com", executedParams.Credentials.Registry.Host)
		assert.Equal(t, "worker", executedParams.Credentials.Registry.Username)
		assert.Equal(t, "s3cr3t", executedParams.Credentials.Registry.Password)
	}
}

// TestExecuteAINodeFromSnapshot_CallsExecutor_PrepareErrorPropagates verifies a prepare failure
// is returned as an ordinary error, not masked as ErrResultPending — and that it short-circuits
// before ever reaching the executor.
func TestExecuteAINodeFromSnapshot_CallsExecutor_PrepareErrorPropagates(t *testing.T) {
	executed := false
	mockExec := &mockExecutor{
		executeFn: func(ctx context.Context, params executor.ExecutionParams) (executor.ExecutionResult, error) {
			executed = true
			return executor.ExecutionResult{}, nil
		},
	}
	mockClient := &mockWorkloadClient{
		prepareFn: func(ctx context.Context, p workload.PrepareParams) (*workload.PrepareResponse, error) {
			return nil, errors.New("api-server unreachable")
		},
	}

	acts := NewWithExecutor(mockClient, mockExec, callback.NewHashCache())
	acts.CallbackURL = "http://worker:9090/api/v1/callback"
	acts.APIServerURL = "http://api-server.invalid"

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), ExecuteAINodeFromSnapshotParams{
		Identity: Identity{
			NodeExecutionID: uuid.New(),
			RunID:           stubbedRun(t),
			TemplateNodeID:  uuid.New(),
		},
		Node: Node{
			ExecutorType:   "ai",
			PromptTemplate: "irrelevant",
		},
	})
	assert.Error(t, err)
	assert.NotErrorIs(t, err, temporalactivity.ErrResultPending)
	assert.False(t, executed, "a failed prepare must not reach the executor")
}

// TestExecuteAINodeFromSnapshot_CallsExecutor_ExecuteErrorPropagates verifies an executor
// failure is returned as an ordinary error and never reaches complete or the hash cache.
func TestExecuteAINodeFromSnapshot_CallsExecutor_ExecuteErrorPropagates(t *testing.T) {
	nodeExecID := uuid.New()
	mockExec := &mockExecutor{
		executeFn: func(ctx context.Context, params executor.ExecutionParams) (executor.ExecutionResult, error) {
			return executor.ExecutionResult{}, errors.New("launch failed")
		},
	}
	completed := false
	mockClient := &mockWorkloadClient{
		prepareFn: func(ctx context.Context, p workload.PrepareParams) (*workload.PrepareResponse, error) {
			return &workload.PrepareResponse{Image: "ghcr.io/test/agent:latest"}, nil
		},
		completeFn: func(ctx context.Context, p workload.CompleteParams) error {
			completed = true
			return nil
		},
	}

	hashCache := callback.NewHashCache()
	acts := NewWithExecutor(mockClient, mockExec, hashCache)
	acts.CallbackURL = "http://worker:9090/api/v1/callback"
	acts.APIServerURL = "http://api-server.invalid"

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), ExecuteAINodeFromSnapshotParams{
		Identity: Identity{
			NodeExecutionID: nodeExecID,
			RunID:           stubbedRun(t),
			TemplateNodeID:  uuid.New(),
		},
		Node: Node{
			ExecutorType:   "ai",
			PromptTemplate: "irrelevant",
		},
	})
	assert.Error(t, err)
	assert.NotErrorIs(t, err, temporalactivity.ErrResultPending)
	assert.False(t, completed, "a failed execute must not reach complete")
	_, cached := hashCache.Get(nodeExecID)
	assert.False(t, cached, "a failed execute must not populate the hash cache")
}

// TestExecuteAINodeFromSnapshot_CallsExecutor_CompleteErrorPropagates verifies a complete
// failure is returned as an ordinary error — even though the hash was already cached and the
// workload is already running by that point.
func TestExecuteAINodeFromSnapshot_CallsExecutor_CompleteErrorPropagates(t *testing.T) {
	nodeExecID := uuid.New()
	mockExec := &mockExecutor{
		executeFn: func(ctx context.Context, params executor.ExecutionParams) (executor.ExecutionResult, error) {
			return executor.ExecutionResult{PodName: "agent-abc", JobSecretHash: "hash123"}, nil
		},
	}
	mockClient := &mockWorkloadClient{
		prepareFn: func(ctx context.Context, p workload.PrepareParams) (*workload.PrepareResponse, error) {
			return &workload.PrepareResponse{Image: "ghcr.io/test/agent:latest"}, nil
		},
		completeFn: func(ctx context.Context, p workload.CompleteParams) error {
			return errors.New("api-server unreachable")
		},
	}

	hashCache := callback.NewHashCache()
	acts := NewWithExecutor(mockClient, mockExec, hashCache)
	acts.CallbackURL = "http://worker:9090/api/v1/callback"
	acts.APIServerURL = "http://api-server.invalid"

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), ExecuteAINodeFromSnapshotParams{
		Identity: Identity{
			NodeExecutionID: nodeExecID,
			RunID:           stubbedRun(t),
			TemplateNodeID:  uuid.New(),
		},
		Node: Node{
			ExecutorType:   "ai",
			PromptTemplate: "irrelevant",
		},
	})
	assert.Error(t, err)
	assert.NotErrorIs(t, err, temporalactivity.ErrResultPending)

	// The hash is cached before complete is called (the workload can call back before this
	// activity's own report to the API server finishes), so it must survive complete's failure.
	cached, ok := hashCache.Get(nodeExecID)
	assert.True(t, ok)
	assert.Equal(t, "hash123", cached)

	// Same reasoning applies to the pending-completion cache: it must be populated before
	// complete is called, not after, so it too must survive complete's failure.
	_, ok = acts.Pending.Get(nodeExecID)
	assert.True(t, ok)
}

// TestExecuteLocally_RestoresAgentLaunchedLog pins Task 3's restore: the callback path used to
// write "Agent launched: <pod>" to the execution log when the API server itself created the
// workload; now that this Worker launches the workload directly via executeLocally, the write
// must live here or the line is gone from the run's log for good, not just moved.
func TestExecuteLocally_RestoresAgentLaunchedLog(t *testing.T) {
	nodeExecID := uuid.New()
	runID := stubbedRun(t)

	mockExec := &mockExecutor{
		executeFn: func(ctx context.Context, params executor.ExecutionParams) (executor.ExecutionResult, error) {
			return executor.ExecutionResult{PodName: "agent-abc123", JobSecretHash: "hash123"}, nil
		},
	}

	type logCall struct {
		runID, nodeExecID uuid.UUID
		level, message    string
	}
	var logCalls []logCall
	mockClient := &mockWorkloadClient{
		prepareFn: func(ctx context.Context, p workload.PrepareParams) (*workload.PrepareResponse, error) {
			return &workload.PrepareResponse{Image: "ghcr.io/test/agent:latest"}, nil
		},
		completeFn: func(ctx context.Context, p workload.CompleteParams) error { return nil },
		writeLogFn: func(ctx context.Context, runID, nodeExecID uuid.UUID, level, message string) {
			logCalls = append(logCalls, logCall{runID, nodeExecID, level, message})
		},
	}

	acts := NewWithExecutor(mockClient, mockExec, callback.NewHashCache())
	acts.CallbackURL = "http://worker:9090/api/v1/callback"
	acts.APIServerURL = "http://api-server.invalid"

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), ExecuteAINodeFromSnapshotParams{
		Identity: Identity{
			NodeExecutionID: nodeExecID,
			RunID:           runID,
			TemplateNodeID:  uuid.New(),
		},
		Node: Node{
			ExecutorType:   "ai",
			PromptTemplate: "irrelevant",
		},
	})
	requirePending(t, err)

	var found bool
	for _, c := range logCalls {
		if c.level == "info" && strings.HasPrefix(c.message, "Agent launched") && strings.Contains(c.message, "agent-abc123") {
			found = true
			assert.Equal(t, runID, c.runID)
			assert.Equal(t, nodeExecID, c.nodeExecID)
		}
	}
	assert.True(t, found, "expected an info log starting with 'Agent launched' and naming the pod, got %+v", logCalls)
}

var promptResolvedPattern = regexp.MustCompile(`^Prompt resolved \(\d+ chars\)$`)

// TestExecuteAINode_RestoresPromptResolvedLog pins the other half of Task 3's restore: a
// "Prompt resolved (N chars)" info log, once written by the orchestrator's callback path,
// now must come from the activity itself right after the prompt template is resolved.
func TestExecuteAINode_RestoresPromptResolvedLog(t *testing.T) {
	mockExec := &mockExecutor{
		executeFn: func(ctx context.Context, params executor.ExecutionParams) (executor.ExecutionResult, error) {
			return executor.ExecutionResult{PodName: "agent-abc", JobSecretHash: "hash123"}, nil
		},
	}

	type logCall struct{ level, message string }
	var logCalls []logCall
	mockClient := &mockWorkloadClient{
		prepareFn: func(ctx context.Context, p workload.PrepareParams) (*workload.PrepareResponse, error) {
			return &workload.PrepareResponse{Image: "ghcr.io/test/agent:latest"}, nil
		},
		completeFn: func(ctx context.Context, p workload.CompleteParams) error { return nil },
		writeLogFn: func(ctx context.Context, runID, nodeExecID uuid.UUID, level, message string) {
			logCalls = append(logCalls, logCall{level, message})
		},
	}

	acts := NewWithExecutor(mockClient, mockExec, callback.NewHashCache())
	acts.CallbackURL = "http://worker:9090/api/v1/callback"
	acts.APIServerURL = "http://api-server.invalid"

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), ExecuteAINodeFromSnapshotParams{
		Identity: Identity{
			NodeExecutionID: uuid.New(),
			RunID:           stubbedRun(t),
			TemplateNodeID:  uuid.New(),
		},
		Node: Node{
			ExecutorType:   "ai",
			PromptTemplate: "hello world",
		},
	})
	requirePending(t, err)

	var found bool
	for _, c := range logCalls {
		if c.level == "info" && promptResolvedPattern.MatchString(c.message) {
			found = true
		}
	}
	assert.True(t, found, "expected an info log matching 'Prompt resolved (N chars)', got %+v", logCalls)
}

// TestExecuteAINodeFromSnapshot_CallbackAndAPIServerURLsInConfigJson verifies that
// CallbackURL and APIServerURL reach config.json, and that github_token_url is built as an
// absolute URL — a relative one would be uncallable from inside the agent pod.
func TestExecuteAINodeFromSnapshot_CallbackAndAPIServerURLsInConfigJson(t *testing.T) {
	var receivedConfigJSON map[string]interface{}
	acts := newTestActivities(t, &receivedConfigJSON)

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), ExecuteAINodeFromSnapshotParams{
		Identity: Identity{
			NodeExecutionID: uuid.New(),
			RunID:           stubbedRun(t),
			TemplateNodeID:  uuid.New(),
		},
		Node: Node{
			ExecutorType:   "ai",
			PromptTemplate: "irrelevant",
		},
	})
	requirePending(t, err)

	if receivedConfigJSON["callback_url"] != acts.CallbackURL {
		t.Fatalf("callback_url = %v, want %v", receivedConfigJSON["callback_url"], acts.CallbackURL)
	}
	if receivedConfigJSON["api_server_url"] != acts.APIServerURL {
		t.Fatalf("api_server_url = %v, want %v", receivedConfigJSON["api_server_url"], acts.APIServerURL)
	}
	githubTokenURL, ok := receivedConfigJSON["github_token_url"].(string)
	if !ok {
		t.Fatal("github_token_url should be a string in config.json")
	}
	if !strings.HasPrefix(githubTokenURL, "http://") && !strings.HasPrefix(githubTokenURL, "https://") {
		t.Fatalf("github_token_url must be absolute, got %q", githubTokenURL)
	}
}

// TestExecuteAINodeFromSnapshot_FailsFastWhenCallbackURLEmpty guards against a config.json
// that silently ships with an empty callback_url: the agent pod would launch and then have no
// way to report back, hanging the activity until StartToClose instead of failing immediately.
func TestExecuteAINodeFromSnapshot_FailsFastWhenCallbackURLEmpty(t *testing.T) {
	acts := New(workload.NewClient("http://unused.invalid", func() string { return "secret" }, nil))
	acts.APIServerURL = "http://api.invalid"
	// CallbackURL intentionally left empty.

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), ExecuteAINodeFromSnapshotParams{
		Identity: Identity{
			NodeExecutionID: uuid.New(),
			RunID:           uuid.New(),
			TemplateNodeID:  uuid.New(),
		},
		Node: Node{
			ExecutorType:   "ai",
			PromptTemplate: "irrelevant",
		},
	})
	if err == nil || errors.Is(err, temporalactivity.ErrResultPending) {
		t.Fatalf("want an immediate config error, got %v", err)
	}
}

// TestExecuteAINodeFromSnapshot_FailsFastWhenAPIServerURLEmpty is the APIServerURL half of
// TestExecuteAINodeFromSnapshot_FailsFastWhenCallbackURLEmpty.
func TestExecuteAINodeFromSnapshot_FailsFastWhenAPIServerURLEmpty(t *testing.T) {
	acts := New(workload.NewClient("http://unused.invalid", func() string { return "secret" }, nil))
	acts.CallbackURL = "http://callback.invalid"
	// APIServerURL intentionally left empty.

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), ExecuteAINodeFromSnapshotParams{
		Identity: Identity{
			NodeExecutionID: uuid.New(),
			RunID:           uuid.New(),
			TemplateNodeID:  uuid.New(),
		},
		Node: Node{
			ExecutorType:   "ai",
			PromptTemplate: "irrelevant",
		},
	})
	if err == nil || errors.Is(err, temporalactivity.ErrResultPending) {
		t.Fatalf("want an immediate config error, got %v", err)
	}
}

// The workflow id is fixed in history and the parameters are not, so a pair that disagrees means
// something built this activity wrongly. Launching anyway would address a run this Worker's
// credential cannot act on, surfacing as a node failure with no stated cause.
func TestExecuteAINodeFromSnapshot_RejectsParamsNamingAnotherRun(t *testing.T) {
	var receivedConfigJSON map[string]interface{}
	acts := newTestActivities(t, &receivedConfigJSON)
	workflowRun := stubbedRun(t)

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), ExecuteAINodeFromSnapshotParams{
		Identity: Identity{
			NodeExecutionID: uuid.New(),
			RunID:           uuid.New(),
			TemplateNodeID:  uuid.New(),
		},
		Node: Node{
			ExecutorType:   "ai",
			PromptTemplate: "irrelevant",
		},
	})
	if err == nil || errors.Is(err, temporalactivity.ErrResultPending) {
		t.Fatalf("want a mismatch error, got %v", err)
	}
	if !strings.Contains(err.Error(), workflowRun.String()) {
		t.Fatalf("error must name the workflow's run %s, got %v", workflowRun, err)
	}
	if receivedConfigJSON != nil {
		t.Fatal("a mismatched pair must not reach the API server")
	}
}

func TestExecuteAINodeFromSnapshot_OutputPathKeyedByExecutionID(t *testing.T) {
	execID := uuid.New()
	runID := stubbedRun(t)
	templateNodeID := uuid.New()

	var receivedConfigJSON map[string]interface{}
	acts := newTestActivities(t, &receivedConfigJSON)

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), ExecuteAINodeFromSnapshotParams{
		Identity: Identity{
			NodeExecutionID: execID,
			RunID:           runID,
			TemplateNodeID:  templateNodeID,
		},
		Node: Node{
			ExecutorType:   "ai",
			PromptTemplate: "irrelevant",
			Iteration:      2,
		},
	})
	requirePending(t, err)

	outputPath, _ := receivedConfigJSON["output_path"].(string)
	if !strings.Contains(outputPath, execID.String()) {
		t.Fatalf("output_path must include the execution ID so iterations own their own prefix: %q", outputPath)
	}
	if strings.Contains(outputPath, templateNodeID.String()) {
		t.Fatalf("output_path must NOT be keyed by template node ID — that causes iterations to overwrite each other: %q", outputPath)
	}
}

func TestExecuteAINodeFromSnapshot_ScriptNode_ConfigJson(t *testing.T) {
	var receivedConfigJSON map[string]interface{}
	acts := newTestActivities(t, &receivedConfigJSON)

	params := ExecuteAINodeFromSnapshotParams{
		Identity: Identity{
			NodeExecutionID: uuid.New(),
			RunID:           stubbedRun(t),
			TemplateNodeID:  uuid.New(),
		},
		Node: Node{
			ExecutorType:   "script",
			Command:        "cd /workspace/repo && npm test",
			PromptTemplate: "", // script nodes have no prompt
			Iteration:      1,
		},
		Inputs: Inputs{
			Variables: map[string]string{"run.id": "abc123"},
		},
		Repos: Repos{
			RepoURL:       "https://github.com/test/repo",
			WorkingBranch: "choruskube-run-abc123",
		},
	}

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), params)
	requirePending(t, err)

	if receivedConfigJSON["executor_type"] != "script" {
		t.Fatalf("executor_type = %v, want script", receivedConfigJSON["executor_type"])
	}
	if receivedConfigJSON["command"] != "cd /workspace/repo && npm test" {
		t.Fatalf("command = %v", receivedConfigJSON["command"])
	}
	if receivedConfigJSON["repo_url"] != "https://github.com/test/repo" {
		t.Fatalf("repo_url = %v", receivedConfigJSON["repo_url"])
	}
	if receivedConfigJSON["working_branch"] != "choruskube-run-abc123" {
		t.Fatalf("working_branch = %v", receivedConfigJSON["working_branch"])
	}
	if receivedConfigJSON["prompt"] != "" {
		t.Fatalf("prompt = %v, want empty", receivedConfigJSON["prompt"])
	}

	// Object storage fields should NOT be in config.json
	if receivedConfigJSON["minio_endpoint"] != nil || receivedConfigJSON["minio_bucket"] != nil {
		t.Fatalf("object storage fields leaked into config.json: %v", receivedConfigJSON)
	}
}

func TestExecuteAINodeFromSnapshot_NoSystemPromptInConfigJson(t *testing.T) {
	runID := stubbedRun(t)

	var receivedConfigJSON map[string]interface{}
	acts := newTestActivities(t, &receivedConfigJSON)

	params := ExecuteAINodeFromSnapshotParams{
		Identity: Identity{
			NodeExecutionID: uuid.New(),
			RunID:           runID,
			TemplateNodeID:  uuid.New(),
		},
		Node: Node{
			ExecutorType:   "ai",
			PromptTemplate: "Do the thing",
			Iteration:      1,
		},
		Inputs: Inputs{
			Variables: map[string]string{"run.id": runID.String()},
		},
	}

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), params)
	requirePending(t, err)

	// System prompt is built by the entrypoint from the image-local template, not passed
	// through config.json.
	if _, hasSystemPrompt := receivedConfigJSON["system_prompt"]; hasSystemPrompt {
		t.Fatal("system_prompt should not be in config.json")
	}
	want := "runs/" + runID.String() + "/run_log.md"
	if receivedConfigJSON["run_log_path"] != want {
		t.Fatalf("run_log_path = %v, want %v", receivedConfigJSON["run_log_path"], want)
	}
}

func TestExecuteAINodeFromSnapshot_TaskContextInConfigJson(t *testing.T) {
	runID := stubbedRun(t)
	taskID := uuid.New()
	storyID := uuid.New()
	epicID := uuid.New()

	var receivedConfigJSON map[string]interface{}
	acts := newTestActivities(t, &receivedConfigJSON)

	params := ExecuteAINodeFromSnapshotParams{
		Identity: Identity{
			NodeExecutionID: uuid.New(),
			RunID:           runID,
			TemplateNodeID:  uuid.New(),
		},
		Node: Node{
			ExecutorType:   "ai",
			PromptTemplate: "Do the thing",
		},
		Inputs: Inputs{
			Variables: map[string]string{"run.id": runID.String()},
		},
		TaskContext: TaskContext{
			TaskID:     taskID.String(),
			TaskTitle:  "Wire up task_context",
			StoryID:    storyID.String(),
			StoryTitle: "Agent identity threading",
			EpicID:     epicID.String(),
			EpicTitle:  "Roadmap-aware agents",
		},
	}

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), params)
	requirePending(t, err)

	taskContext, ok := receivedConfigJSON["task_context"].(map[string]interface{})
	if !ok {
		t.Fatal("task_context should be present in config.json when TaskID is set")
	}
	checks := map[string]string{
		"task_id":     taskID.String(),
		"task_title":  "Wire up task_context",
		"story_id":    storyID.String(),
		"story_title": "Agent identity threading",
		"epic_id":     epicID.String(),
		"epic_title":  "Roadmap-aware agents",
	}
	for key, want := range checks {
		if taskContext[key] != want {
			t.Fatalf("task_context[%q] = %v, want %v", key, taskContext[key], want)
		}
	}
}

func TestExecuteAINodeFromSnapshot_NoTaskContextWhenTaskIDEmpty(t *testing.T) {
	runID := stubbedRun(t)

	var receivedConfigJSON map[string]interface{}
	acts := newTestActivities(t, &receivedConfigJSON)

	// Manually-started run: no TaskID set at all.
	params := ExecuteAINodeFromSnapshotParams{
		Identity: Identity{
			NodeExecutionID: uuid.New(),
			RunID:           runID,
			TemplateNodeID:  uuid.New(),
		},
		Node: Node{
			ExecutorType:   "ai",
			PromptTemplate: "Do the thing",
		},
		Inputs: Inputs{
			Variables: map[string]string{"run.id": runID.String()},
		},
	}

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), params)
	requirePending(t, err)

	if _, hasTaskContext := receivedConfigJSON["task_context"]; hasTaskContext {
		t.Fatal("task_context should be absent from config.json when TaskID is empty")
	}
}

func TestExecuteAINodeFromSnapshot_TaskContextIncludesOpenBlockers(t *testing.T) {
	runID := stubbedRun(t)
	taskID := uuid.New()
	blockerID := uuid.New()

	var receivedConfigJSON map[string]interface{}
	acts := newTestActivities(t, &receivedConfigJSON)

	params := ExecuteAINodeFromSnapshotParams{
		Identity: Identity{
			NodeExecutionID: uuid.New(),
			RunID:           runID,
			TemplateNodeID:  uuid.New(),
		},
		Node: Node{
			ExecutorType:   "ai",
			PromptTemplate: "Do the thing",
		},
		Inputs: Inputs{
			Variables: map[string]string{"run.id": runID.String()},
		},
		TaskContext: TaskContext{
			TaskID:    taskID.String(),
			TaskTitle: "Wire up open blockers",
			OpenBlockers: []OpenBlockerParam{
				{ItemType: "task", ItemID: blockerID.String(), Title: "Prerequisite Task", Status: "in_progress"},
			},
		},
	}

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), params)
	requirePending(t, err)

	taskContext, ok := receivedConfigJSON["task_context"].(map[string]interface{})
	if !ok {
		t.Fatal("task_context should be present in config.json when TaskID is set")
	}
	openBlockers, ok := taskContext["open_blockers"].([]interface{})
	if !ok || len(openBlockers) != 1 {
		t.Fatalf("open_blockers should have exactly one entry, got %v", taskContext["open_blockers"])
	}
	blocker, ok := openBlockers[0].(map[string]interface{})
	if !ok {
		t.Fatalf("open_blockers[0] has unexpected shape: %v", openBlockers[0])
	}
	if blocker["item_type"] != "task" || blocker["item_id"] != blockerID.String() ||
		blocker["title"] != "Prerequisite Task" || blocker["status"] != "in_progress" {
		t.Fatalf("unexpected blocker contents: %v", blocker)
	}
}

func TestExecuteAINodeFromSnapshot_NoOpenBlockersKeyWhenEmpty(t *testing.T) {
	runID := stubbedRun(t)
	taskID := uuid.New()

	var receivedConfigJSON map[string]interface{}
	acts := newTestActivities(t, &receivedConfigJSON)

	// TaskID set (task_context present) but OpenBlockers left empty — the run's Task has no
	// open blockers today.
	params := ExecuteAINodeFromSnapshotParams{
		Identity: Identity{
			NodeExecutionID: uuid.New(),
			RunID:           runID,
			TemplateNodeID:  uuid.New(),
		},
		Node: Node{
			ExecutorType:   "ai",
			PromptTemplate: "Do the thing",
		},
		Inputs: Inputs{
			Variables: map[string]string{"run.id": runID.String()},
		},
		TaskContext: TaskContext{
			TaskID:    taskID.String(),
			TaskTitle: "No blockers here",
		},
	}

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), params)
	requirePending(t, err)

	taskContext, ok := receivedConfigJSON["task_context"].(map[string]interface{})
	if !ok {
		t.Fatal("task_context should be present in config.json when TaskID is set")
	}
	if _, hasOpenBlockers := taskContext["open_blockers"]; hasOpenBlockers {
		t.Fatal("open_blockers key should be absent from task_context when OpenBlockers is empty")
	}
}

func TestExecuteAINodeFromSnapshot_IterationInConfigJson(t *testing.T) {
	var receivedConfigJSON map[string]interface{}
	acts := newTestActivities(t, &receivedConfigJSON)

	params := ExecuteAINodeFromSnapshotParams{
		Identity: Identity{
			NodeExecutionID: uuid.New(),
			RunID:           stubbedRun(t),
			TemplateNodeID:  uuid.New(),
		},
		Node: Node{
			ExecutorType:   "script",
			Command:        "echo hello",
			PromptTemplate: "",
			Iteration:      3,
		},
		Inputs: Inputs{
			Variables: map[string]string{"run.id": "test123"},
		},
	}

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), params)
	requirePending(t, err)

	if receivedConfigJSON["iteration"] != float64(3) {
		t.Fatalf("iteration = %v, want 3", receivedConfigJSON["iteration"])
	}
	// Regression guard: the iteration-cap epoch machinery was removed; make sure
	// it doesn't silently reappear in the agent's config.json.
	if _, hasEpochKey := receivedConfigJSON["iteration_in_epoch"]; hasEpochKey {
		t.Fatal("iteration_in_epoch should not be present in config.json")
	}
}

func TestExecuteAINodeFromSnapshot_IterationZeroOmitted(t *testing.T) {
	var receivedConfigJSON map[string]interface{}
	acts := newTestActivities(t, &receivedConfigJSON)

	params := ExecuteAINodeFromSnapshotParams{
		Identity: Identity{
			NodeExecutionID: uuid.New(),
			RunID:           stubbedRun(t),
			TemplateNodeID:  uuid.New(),
		},
		Node: Node{
			ExecutorType:   "ai",
			PromptTemplate: "Do something",
			Iteration:      0,
		},
		Inputs: Inputs{
			Variables: map[string]string{"run.id": "test123"},
		},
	}

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), params)
	requirePending(t, err)

	if _, exists := receivedConfigJSON["iteration"]; exists {
		t.Fatal("iteration=0 should not be in config.json")
	}
}

func TestExecuteAINodeFromSnapshot_PredecessorArtifactAnnotation(t *testing.T) {
	var receivedConfigJSON map[string]interface{}
	acts := newTestActivities(t, &receivedConfigJSON)

	params := ExecuteAINodeFromSnapshotParams{
		Identity: Identity{
			NodeExecutionID: uuid.New(),
			RunID:           stubbedRun(t),
			TemplateNodeID:  uuid.New(),
		},
		Node: Node{
			ExecutorType:   "ai",
			PromptTemplate: "Do the thing",
			Iteration:      1,
		},
		Inputs: Inputs{
			Variables: map[string]string{
				"run.id":                "abc123",
				"input.gate.result":     "approved",          // .result entry — must be excluded
				"input.gate.file.png":   "orgs/x/gate.png",   // artifact — must be included
				"input.gate.report.pdf": "orgs/x/report.pdf", // artifact — must be included
			},
		},
	}

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), params)
	requirePending(t, err)

	prompt, ok := receivedConfigJSON["prompt"].(string)
	if !ok {
		t.Fatal("prompt should be a string in config.json")
	}
	if !strings.Contains(prompt, "**Predecessor Artifacts**") {
		t.Fatal("prompt should contain the annotation header")
	}
	if !strings.Contains(prompt, "input.gate.file.png") || !strings.Contains(prompt, "input.gate.report.pdf") {
		t.Fatalf("artifact keys should appear in annotation: %q", prompt)
	}
	if strings.Contains(prompt, "input.gate.result") {
		t.Fatalf("result key must not appear in annotation: %q", prompt)
	}
}

// TestExecuteAINodeFromSnapshot_OnlyResultEntries_NoAnnotation verifies that when all
// input.* variables end in ".result", no artifact annotation block is appended.
func TestExecuteAINodeFromSnapshot_OnlyResultEntries_NoAnnotation(t *testing.T) {
	var receivedConfigJSON map[string]interface{}
	acts := newTestActivities(t, &receivedConfigJSON)

	params := ExecuteAINodeFromSnapshotParams{
		Identity: Identity{
			NodeExecutionID: uuid.New(),
			RunID:           stubbedRun(t),
			TemplateNodeID:  uuid.New(),
		},
		Node: Node{
			ExecutorType:   "ai",
			PromptTemplate: "Do the thing",
			Iteration:      1,
		},
		Inputs: Inputs{
			Variables: map[string]string{
				"run.id":            "abc123",
				"input.gate.result": "approved", // only .result entries — no annotation
			},
		},
	}

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), params)
	requirePending(t, err)

	prompt, ok := receivedConfigJSON["prompt"].(string)
	if !ok {
		t.Fatal("prompt should be a string in config.json")
	}
	if strings.Contains(prompt, "**Predecessor Artifacts**") {
		t.Fatal("no annotation block expected when only .result entries present")
	}
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
			acts := newTestActivities(t, &receivedConfigJSON)

			_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), ExecuteAINodeFromSnapshotParams{
				Identity: Identity{
					NodeExecutionID: uuid.New(),
					RunID:           stubbedRun(t),
					TemplateNodeID:  uuid.New(),
				},
				Node: Node{
					ExecutorType:   "ai",
					PromptTemplate: "Do the thing",
					Iteration:      1,
				},
				Inputs: Inputs{
					Variables:      tt.variables,
					InputArtifacts: tt.inputArtifacts,
				},
			})
			requirePending(t, err)

			resolvedPrompt, ok := receivedConfigJSON["prompt"].(string)
			if !ok {
				t.Fatal("prompt should be a string in config.json")
			}

			hasBlock := strings.Contains(resolvedPrompt, "**Predecessor Artifacts**")
			if hasBlock != tt.wantBlock {
				t.Fatalf("annotation block present = %v, want %v", hasBlock, tt.wantBlock)
			}
			for _, want := range tt.wantPresent {
				if !strings.Contains(resolvedPrompt, want) {
					t.Fatalf("expected entry %q missing from annotation: %q", want, resolvedPrompt)
				}
			}
			for _, notWant := range tt.wantAbsent {
				if strings.Contains(resolvedPrompt, notWant) {
					t.Fatalf("materialised file %q must not be advertised as a manual download: %q", notWant, resolvedPrompt)
				}
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
	acts := newTestActivities(t, &receivedConfigJSON)

	params := ExecuteAINodeFromSnapshotParams{
		Identity: Identity{
			NodeExecutionID: uuid.New(),
			RunID:           stubbedRun(t),
			TemplateNodeID:  uuid.New(),
		},
		Node: Node{
			ExecutorType:   "ai",
			PromptTemplate: "Do the thing",
			Iteration:      1,
		},
		Inputs: Inputs{
			InputArtifacts: map[string]string{
				"run_input/mockup.png": "system/runs/abc/inputs/mockup.png",  // run-level — must appear
				"run_input/spec.md":    "system/runs/abc/inputs/spec.md",     // run-level — must appear
				"input/gate/feedback":  "system/runs/abc/gate-attachments/x", // not run_input/ — must NOT appear in Run Inputs block
			},
		},
	}

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), params)
	requirePending(t, err)

	prompt, ok := receivedConfigJSON["prompt"].(string)
	if !ok {
		t.Fatal("prompt should be a string in config.json")
	}
	if !strings.Contains(prompt, "**Run Inputs**") {
		t.Fatal("prompt should contain the Run Inputs annotation header")
	}
	if !strings.Contains(prompt, "run_input/mockup.png") || !strings.Contains(prompt, "run_input/spec.md") {
		t.Fatalf("run-level input keys should appear in annotation: %q", prompt)
	}
	if !strings.Contains(prompt, "system/runs/abc/inputs/mockup.png") {
		t.Fatalf("run-level object storage path should appear in annotation: %q", prompt)
	}

	// Lines should be sorted (deterministic output).
	mockupIdx := strings.Index(prompt, "run_input/mockup.png")
	specIdx := strings.Index(prompt, "run_input/spec.md")
	if mockupIdx <= 0 || specIdx <= 0 || mockupIdx >= specIdx {
		t.Fatalf("annotation lines should be sorted alphabetically: mockupIdx=%d specIdx=%d", mockupIdx, specIdx)
	}

	// The non-run_input key must not be hoisted into the Run Inputs block, and no
	// Predecessor Artifacts block should appear since Variables has no input.* keys.
	if strings.Contains(prompt, "**Predecessor Artifacts**") {
		t.Fatal("no predecessor block expected when Variables has no input.* keys")
	}
	if strings.Contains(prompt, "input/gate/feedback") {
		t.Fatal("non-run_input/ keys must not be announced")
	}
}

// TestExecuteAINodeFromSnapshot_NoRunInputs_NoAnnotation verifies that when no
// "run_input/" keys are present, the Run Inputs block is not added.
func TestExecuteAINodeFromSnapshot_NoRunInputs_NoAnnotation(t *testing.T) {
	var receivedConfigJSON map[string]interface{}
	acts := newTestActivities(t, &receivedConfigJSON)

	params := ExecuteAINodeFromSnapshotParams{
		Identity: Identity{
			NodeExecutionID: uuid.New(),
			RunID:           stubbedRun(t),
			TemplateNodeID:  uuid.New(),
		},
		Node: Node{
			ExecutorType:   "ai",
			PromptTemplate: "Do the thing",
			Iteration:      1,
		},
		Inputs: Inputs{
			InputArtifacts: map[string]string{}, // empty — nothing to announce
		},
	}

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), params)
	requirePending(t, err)

	prompt, ok := receivedConfigJSON["prompt"].(string)
	if !ok {
		t.Fatal("prompt should be a string in config.json")
	}
	if strings.Contains(prompt, "**Run Inputs**") {
		t.Fatal("no Run Inputs block expected when InputArtifacts has no run_input/ keys")
	}
}

// TestExecuteAINodeFromSnapshot_OutputSpec_Present verifies that when OutputSpec is a
// non-empty, non-"{}" JSON string, it is forwarded as "output_spec" in config.json.
func TestExecuteAINodeFromSnapshot_OutputSpec_Present(t *testing.T) {
	var receivedConfigJSON map[string]interface{}
	acts := newTestActivities(t, &receivedConfigJSON)

	outputSpec := `{"files":[{"name":"report.pdf","required":true}]}`
	params := ExecuteAINodeFromSnapshotParams{
		Identity: Identity{
			NodeExecutionID: uuid.New(),
			RunID:           stubbedRun(t),
			TemplateNodeID:  uuid.New(),
		},
		Node: Node{
			ExecutorType:   "ai",
			PromptTemplate: "Generate a report",
			Iteration:      1,
			OutputSpec:     outputSpec,
		},
		Inputs: Inputs{
			Variables: map[string]string{"run.id": "abc123"},
		},
	}

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), params)
	requirePending(t, err)

	if receivedConfigJSON["output_spec"] != outputSpec {
		t.Fatalf("output_spec = %v, want forwarded verbatim", receivedConfigJSON["output_spec"])
	}
}

// TestExecuteAINodeFromSnapshot_OutputSpec_Empty verifies that when OutputSpec is empty,
// "output_spec" is absent from config.json.
func TestExecuteAINodeFromSnapshot_OutputSpec_Empty(t *testing.T) {
	var receivedConfigJSON map[string]interface{}
	acts := newTestActivities(t, &receivedConfigJSON)

	params := ExecuteAINodeFromSnapshotParams{
		Identity: Identity{
			NodeExecutionID: uuid.New(),
			RunID:           stubbedRun(t),
			TemplateNodeID:  uuid.New(),
		},
		Node: Node{
			ExecutorType:   "ai",
			PromptTemplate: "Do something",
			Iteration:      1,
			OutputSpec:     "", // empty — should not appear in config.json
		},
		Inputs: Inputs{
			Variables: map[string]string{"run.id": "abc123"},
		},
	}

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), params)
	requirePending(t, err)

	if _, exists := receivedConfigJSON["output_spec"]; exists {
		t.Fatal("output_spec should be absent from config.json when OutputSpec is empty")
	}
}

// TestExecuteAINodeFromSnapshot_OutputSpec_EmptyObject verifies that when OutputSpec is "{}",
// "output_spec" is absent from config.json (treated the same as empty).
func TestExecuteAINodeFromSnapshot_OutputSpec_EmptyObject(t *testing.T) {
	var receivedConfigJSON map[string]interface{}
	acts := newTestActivities(t, &receivedConfigJSON)

	params := ExecuteAINodeFromSnapshotParams{
		Identity: Identity{
			NodeExecutionID: uuid.New(),
			RunID:           stubbedRun(t),
			TemplateNodeID:  uuid.New(),
		},
		Node: Node{
			ExecutorType:   "ai",
			PromptTemplate: "Do something",
			Iteration:      1,
			OutputSpec:     "{}", // empty object — should not appear in config.json
		},
		Inputs: Inputs{
			Variables: map[string]string{"run.id": "abc123"},
		},
	}

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), params)
	requirePending(t, err)

	if _, exists := receivedConfigJSON["output_spec"]; exists {
		t.Fatal("output_spec should be absent from config.json when OutputSpec is \"{}\"")
	}
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
		{name: "label set — supervisor key present with label and name", supervisorLabel: "qa-lead", wantPresent: true},
		{name: "label empty — supervisor key absent entirely", supervisorLabel: "", wantPresent: false},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			var receivedConfigJSON map[string]interface{}
			acts := newTestActivities(t, &receivedConfigJSON)

			params := ExecuteAINodeFromSnapshotParams{
				Identity: Identity{
					NodeExecutionID: uuid.New(),
					RunID:           stubbedRun(t),
					TemplateNodeID:  uuid.New(),
				},
				Node: Node{
					ExecutorType:    "ai",
					PromptTemplate:  "Do the thing",
					Iteration:       1,
					SupervisorLabel: tt.supervisorLabel,
				},
				Inputs: Inputs{
					Variables: map[string]string{"run.id": "abc123"},
				},
			}

			_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), params)
			requirePending(t, err)

			supervisor, exists := receivedConfigJSON["supervisor"]
			if !tt.wantPresent {
				if exists {
					t.Fatal("supervisor key should be absent from config.json when SupervisorLabel is empty")
				}
				return
			}
			if !exists {
				t.Fatal("supervisor key should be present in config.json when SupervisorLabel is set")
			}
			want := map[string]interface{}{"label": tt.supervisorLabel, "name": "Supervisor"}
			if !reflect.DeepEqual(supervisor, want) {
				t.Fatalf("supervisor = %v, want %v", supervisor, want)
			}
		})
	}
}

// TestExecuteAINodeFromSnapshot_ModelInConfigJson verifies that NodeDefinition.model
// is propagated to the agent via config.json["model"] when set.
func TestExecuteAINodeFromSnapshot_ModelInConfigJson(t *testing.T) {
	var receivedConfigJSON map[string]interface{}
	acts := newTestActivities(t, &receivedConfigJSON)

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), ExecuteAINodeFromSnapshotParams{
		Identity: Identity{
			NodeExecutionID: uuid.New(),
			RunID:           stubbedRun(t),
			TemplateNodeID:  uuid.New(),
		},
		Node: Node{
			ExecutorType:   "ai",
			PromptTemplate: "irrelevant",
			Model:          "claude-haiku-4-5-20251001",
		},
	})
	requirePending(t, err)

	if receivedConfigJSON["model"] != "claude-haiku-4-5-20251001" {
		t.Fatalf("config.json must include model when NodeDefinition.model is set, got %v", receivedConfigJSON["model"])
	}
}

// TestExecuteAINodeFromSnapshot_ModelOmittedWhenEmpty verifies model is omitted from
// config.json when not set (so the agent falls back to its default model).
func TestExecuteAINodeFromSnapshot_ModelOmittedWhenEmpty(t *testing.T) {
	var receivedConfigJSON map[string]interface{}
	acts := newTestActivities(t, &receivedConfigJSON)

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), ExecuteAINodeFromSnapshotParams{
		Identity: Identity{
			NodeExecutionID: uuid.New(),
			RunID:           stubbedRun(t),
			TemplateNodeID:  uuid.New(),
		},
		Node: Node{
			ExecutorType:   "ai",
			PromptTemplate: "irrelevant",
			// Model intentionally not set
		},
	})
	requirePending(t, err)

	if _, hasModel := receivedConfigJSON["model"]; hasModel {
		t.Fatal("config.json must omit model when not set on the snapshot")
	}
}

// TestExecuteAINodeFromSnapshot_EffortInConfigJson verifies that the effort override
// extracted from config_overrides is propagated to the agent via config.json["effort"]
// when set.
func TestExecuteAINodeFromSnapshot_EffortInConfigJson(t *testing.T) {
	var receivedConfigJSON map[string]interface{}
	acts := newTestActivities(t, &receivedConfigJSON)

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), ExecuteAINodeFromSnapshotParams{
		Identity: Identity{
			NodeExecutionID: uuid.New(),
			RunID:           stubbedRun(t),
			TemplateNodeID:  uuid.New(),
		},
		Node: Node{
			ExecutorType:   "ai",
			PromptTemplate: "irrelevant",
			Effort:         "xhigh",
		},
	})
	requirePending(t, err)

	if receivedConfigJSON["effort"] != "xhigh" {
		t.Fatalf("config.json must include effort when set on the snapshot, got %v", receivedConfigJSON["effort"])
	}
}

// TestExecuteAINodeFromSnapshot_EffortOmittedWhenEmpty verifies effort is omitted from
// config.json when not set (not present as an empty string).
func TestExecuteAINodeFromSnapshot_EffortOmittedWhenEmpty(t *testing.T) {
	var receivedConfigJSON map[string]interface{}
	acts := newTestActivities(t, &receivedConfigJSON)

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), ExecuteAINodeFromSnapshotParams{
		Identity: Identity{
			NodeExecutionID: uuid.New(),
			RunID:           stubbedRun(t),
			TemplateNodeID:  uuid.New(),
		},
		Node: Node{
			ExecutorType:   "ai",
			PromptTemplate: "irrelevant",
			// Effort intentionally not set
		},
	})
	requirePending(t, err)

	if _, hasEffort := receivedConfigJSON["effort"]; hasEffort {
		t.Fatal("config.json must omit effort when not set on the snapshot")
	}
}

// TestExecuteAINodeFromSnapshot_ResolvedModelEffortReachConfigJSONUnchanged_BothIterationBands
// verifies that Model and Effort reach config.json unchanged as plain "model"/"effort" keys
// for two independently chosen values, and that no iteration-suffixed key name (e.g.
// model_first_iteration) is ever introduced — a caller resolves iteration-aware overrides
// down to one concrete pair before calling this activity, which must stay agnostic to that.
func TestExecuteAINodeFromSnapshot_ResolvedModelEffortReachConfigJSONUnchanged_BothIterationBands(t *testing.T) {
	// First band: an arbitrary Model/Effort pair, as if resolved for an early iteration.
	var firstIterationConfigJSON map[string]interface{}
	firstActs := newTestActivities(t, &firstIterationConfigJSON)
	_, err := firstActs.ExecuteAINodeFromSnapshot(context.Background(), ExecuteAINodeFromSnapshotParams{
		Identity: Identity{
			NodeExecutionID: uuid.New(),
			RunID:           stubbedRun(t),
			TemplateNodeID:  uuid.New(),
		},
		Node: Node{
			ExecutorType:   "ai",
			PromptTemplate: "irrelevant",
			Model:          "opus-x",
			Effort:         "xhigh",
			Iteration:      1,
		},
	})
	requirePending(t, err)
	if firstIterationConfigJSON["model"] != "opus-x" || firstIterationConfigJSON["effort"] != "xhigh" {
		t.Fatalf("config.json must carry the resolved first-iteration model/effort unchanged: %v", firstIterationConfigJSON)
	}
	if _, ok := firstIterationConfigJSON["model_first_iteration"]; ok {
		t.Fatal("config.json must never carry the raw iteration-suffixed key")
	}
	if _, ok := firstIterationConfigJSON["model_subsequent_iteration"]; ok {
		t.Fatal("config.json must never carry the raw iteration-suffixed key")
	}

	// Second band: a different Model/Effort pair, as if resolved for a later iteration.
	var subsequentIterationConfigJSON map[string]interface{}
	subsequentActs := newTestActivities(t, &subsequentIterationConfigJSON)
	_, err = subsequentActs.ExecuteAINodeFromSnapshot(context.Background(), ExecuteAINodeFromSnapshotParams{
		Identity: Identity{
			NodeExecutionID: uuid.New(),
			RunID:           stubbedRun(t),
			TemplateNodeID:  uuid.New(),
		},
		Node: Node{
			ExecutorType:   "ai",
			PromptTemplate: "irrelevant",
			Model:          "sonnet-y",
			Effort:         "high",
			Iteration:      2,
		},
	})
	requirePending(t, err)
	if subsequentIterationConfigJSON["model"] != "sonnet-y" || subsequentIterationConfigJSON["effort"] != "high" {
		t.Fatalf("config.json must carry the resolved subsequent-iteration model/effort unchanged: %v", subsequentIterationConfigJSON)
	}
	if _, ok := subsequentIterationConfigJSON["effort_first_iteration"]; ok {
		t.Fatal("config.json must never carry the raw iteration-suffixed key")
	}
	if _, ok := subsequentIterationConfigJSON["effort_subsequent_iteration"]; ok {
		t.Fatal("config.json must never carry the raw iteration-suffixed key")
	}
}

// TestExecuteAINodeFromSnapshot_TurnBudgetInConfigJson verifies that the per-node
// max_turns/max_retries overrides extracted from config_overrides reach the agent via
// config.json when set.
func TestExecuteAINodeFromSnapshot_TurnBudgetInConfigJson(t *testing.T) {
	var receivedConfigJSON map[string]interface{}
	acts := newTestActivities(t, &receivedConfigJSON)

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), ExecuteAINodeFromSnapshotParams{
		Identity: Identity{
			NodeExecutionID: uuid.New(),
			RunID:           stubbedRun(t),
			TemplateNodeID:  uuid.New(),
		},
		Node: Node{
			ExecutorType:   "ai",
			PromptTemplate: "irrelevant",
			MaxTurns:       "250",
			MaxRetries:     "5",
		},
	})
	requirePending(t, err)

	if receivedConfigJSON["max_turns"] != "250" {
		t.Fatalf("max_turns = %v, want 250", receivedConfigJSON["max_turns"])
	}
	if receivedConfigJSON["max_retries"] != "5" {
		t.Fatalf("max_retries = %v, want 5", receivedConfigJSON["max_retries"])
	}
}

// TestExecuteAINodeFromSnapshot_TurnBudgetOmittedWhenEmpty verifies max_turns/max_retries
// are absent from config.json when unset — not present as empty strings, which the agent
// would have to special-case instead of simply falling back to its own defaults. The two
// default independently, so this also covers one being set without the other.
func TestExecuteAINodeFromSnapshot_TurnBudgetOmittedWhenEmpty(t *testing.T) {
	var receivedConfigJSON map[string]interface{}
	acts := newTestActivities(t, &receivedConfigJSON)

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), ExecuteAINodeFromSnapshotParams{
		Identity: Identity{
			NodeExecutionID: uuid.New(),
			RunID:           stubbedRun(t),
			TemplateNodeID:  uuid.New(),
		},
		Node: Node{
			ExecutorType:   "ai",
			PromptTemplate: "irrelevant",
			MaxTurns:       "150",
			// MaxRetries intentionally not set
		},
	})
	requirePending(t, err)

	if receivedConfigJSON["max_turns"] != "150" {
		t.Fatalf("max_turns = %v, want 150", receivedConfigJSON["max_turns"])
	}
	if _, hasMaxRetries := receivedConfigJSON["max_retries"]; hasMaxRetries {
		t.Fatal("config.json must omit max_retries when not set on the snapshot")
	}

	receivedConfigJSON = nil
	_, err = acts.ExecuteAINodeFromSnapshot(context.Background(), ExecuteAINodeFromSnapshotParams{
		Identity: Identity{
			NodeExecutionID: uuid.New(),
			RunID:           stubbedRun(t),
			TemplateNodeID:  uuid.New(),
		},
		Node: Node{
			ExecutorType:   "ai",
			PromptTemplate: "irrelevant",
			// Neither MaxTurns nor MaxRetries set
		},
	})
	requirePending(t, err)

	if _, hasMaxTurns := receivedConfigJSON["max_turns"]; hasMaxTurns {
		t.Fatal("config.json must omit max_turns when not set on the snapshot")
	}
	if _, hasMaxRetries := receivedConfigJSON["max_retries"]; hasMaxRetries {
		t.Fatal("config.json must omit max_retries when not set on the snapshot")
	}
}

// TestExecuteAINodeFromSnapshot_SessionInConfigJson verifies that a session parked by a
// previous iteration (SessionID/SessionArtifactPath set by the workflow's rate-limited
// re-queue) reaches the agent via config.json's session_id/session_artifact_path — the
// exact keys the entrypoint reads to resume instead of starting a fresh session.
func TestExecuteAINodeFromSnapshot_SessionInConfigJson(t *testing.T) {
	var receivedConfigJSON map[string]interface{}
	acts := newTestActivities(t, &receivedConfigJSON)

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), ExecuteAINodeFromSnapshotParams{
		Identity: Identity{
			NodeExecutionID: uuid.New(),
			RunID:           stubbedRun(t),
			TemplateNodeID:  uuid.New(),
		},
		Node: Node{
			ExecutorType:   "ai",
			PromptTemplate: "irrelevant",
		},
		Session: Session{
			ID:           "sess-1",
			ArtifactPath: "runs/r/e/session/sess-1.jsonl",
		},
	})
	requirePending(t, err)

	if receivedConfigJSON["session_id"] != "sess-1" {
		t.Fatalf("session_id = %v, want sess-1", receivedConfigJSON["session_id"])
	}
	if receivedConfigJSON["session_artifact_path"] != "runs/r/e/session/sess-1.jsonl" {
		t.Fatalf("session_artifact_path = %v", receivedConfigJSON["session_artifact_path"])
	}
}

// TestExecuteAINodeFromSnapshot_SessionOmittedWhenEmpty verifies session_id/
// session_artifact_path are absent from config.json for an ordinary iteration that
// resumes no parked session — not present as empty strings, which the agent would have
// to special-case instead of simply starting a fresh session.
func TestExecuteAINodeFromSnapshot_SessionOmittedWhenEmpty(t *testing.T) {
	var receivedConfigJSON map[string]interface{}
	acts := newTestActivities(t, &receivedConfigJSON)

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), ExecuteAINodeFromSnapshotParams{
		Identity: Identity{
			NodeExecutionID: uuid.New(),
			RunID:           stubbedRun(t),
			TemplateNodeID:  uuid.New(),
		},
		Node: Node{
			ExecutorType:   "ai",
			PromptTemplate: "irrelevant",
		},
		// SessionID intentionally not set
	})
	requirePending(t, err)

	if _, hasSessionID := receivedConfigJSON["session_id"]; hasSessionID {
		t.Fatal("config.json must omit session_id when the iteration resumes no parked session")
	}
	if _, hasSessionArtifactPath := receivedConfigJSON["session_artifact_path"]; hasSessionArtifactPath {
		t.Fatal("config.json must omit session_artifact_path when the iteration resumes no parked session")
	}
}

// TestExecuteAINodeFromSnapshot_NeedsPRInConfigJson verifies that NeedsPR true (derived
// from config_overrides.needs_pr == "true") is propagated to the agent via
// config.json["needs_pr"].
func TestExecuteAINodeFromSnapshot_NeedsPRInConfigJson(t *testing.T) {
	var receivedConfigJSON map[string]interface{}
	acts := newTestActivities(t, &receivedConfigJSON)

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), ExecuteAINodeFromSnapshotParams{
		Identity: Identity{
			NodeExecutionID: uuid.New(),
			RunID:           stubbedRun(t),
			TemplateNodeID:  uuid.New(),
		},
		Node: Node{
			ExecutorType:   "ai",
			PromptTemplate: "irrelevant",
			NeedsPR:        true,
		},
	})
	requirePending(t, err)

	if receivedConfigJSON["needs_pr"] != true {
		t.Fatalf("config.json must include needs_pr=true when NeedsPR is set on the snapshot, got %v", receivedConfigJSON["needs_pr"])
	}
}

// TestExecuteAINodeFromSnapshot_NeedsPROmittedWhenFalse verifies needs_pr is omitted from
// config.json when NeedsPR is false (the zero value, i.e. not set via config_overrides).
func TestExecuteAINodeFromSnapshot_NeedsPROmittedWhenFalse(t *testing.T) {
	var receivedConfigJSON map[string]interface{}
	acts := newTestActivities(t, &receivedConfigJSON)

	_, err := acts.ExecuteAINodeFromSnapshot(context.Background(), ExecuteAINodeFromSnapshotParams{
		Identity: Identity{
			NodeExecutionID: uuid.New(),
			RunID:           stubbedRun(t),
			TemplateNodeID:  uuid.New(),
		},
		Node: Node{
			ExecutorType:   "ai",
			PromptTemplate: "irrelevant",
			// NeedsPR intentionally not set
		},
	})
	requirePending(t, err)

	if _, hasNeedsPR := receivedConfigJSON["needs_pr"]; hasNeedsPR {
		t.Fatal("config.json must omit needs_pr when not set on the snapshot")
	}
}

func TestFetchPodLogs_DelegatesToAPIServer(t *testing.T) {
	execID := uuid.New()
	runID := stubbedRun(t)
	var gotPath string

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path
		_ = json.NewEncoder(w).Encode(map[string]interface{}{
			"logs": "(no pod found)",
		})
	}))
	defer srv.Close()

	acts := New(workload.NewClient(srv.URL, func() string { return "test-secret" }, srv.Client()))

	logs, err := acts.FetchPodLogs(context.Background(), FetchPodLogsParams{
		NodeExecutionID: execID,
		TailLines:       50,
	})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if logs != "(no pod found)" {
		t.Fatalf("logs = %q, want %q", logs, "(no pod found)")
	}
	// The run comes from the workflow, not the parameters, which carry no run id at all.
	if want := "/worker/runs/" + runID.String() + "/node-executions/" + execID.String() + "/workload/logs"; gotPath != want {
		t.Fatalf("path = %q, want %q", gotPath, want)
	}
}

func TestDeleteAgentJob_DelegatesToAPIServer(t *testing.T) {
	execID := uuid.New()
	runID := stubbedRun(t)
	var gotMethod, gotPath string

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotMethod, gotPath = r.Method, r.URL.Path
		w.WriteHeader(http.StatusNoContent)
	}))
	defer srv.Close()

	acts := New(workload.NewClient(srv.URL, func() string { return "test-secret" }, srv.Client()))

	if err := acts.DeleteAgentJob(context.Background(), DeleteAgentJobParams{NodeExecutionID: execID}); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if gotMethod != http.MethodDelete {
		t.Fatalf("method = %q, want DELETE", gotMethod)
	}
	// The run comes from the workflow, not the parameters, which carry no run id at all.
	if want := "/worker/runs/" + runID.String() + "/node-executions/" + execID.String() + "/workload"; gotPath != want {
		t.Fatalf("path = %q, want %q", gotPath, want)
	}
}

// TestParamsHasNoDeadFields pins the deletion of three fields the orchestrator's struct
// carries but this activity's body never reads (RunLogPath, Label, LoopGroup) — each is read
// only by activities that stay behind in the orchestrator, so here they would be pure replay
// ballast that a future port could silently reintroduce.
func TestParamsHasNoDeadFields(t *testing.T) {
	typ := reflect.TypeOf(ExecuteAINodeFromSnapshotParams{})
	for _, name := range []string{"RunLogPath", "Label", "LoopGroup"} {
		if _, ok := typ.FieldByName(name); ok {
			t.Fatalf("%s was reintroduced: it is replay ballast this activity never reads", name)
		}
	}
}

// TestExecuteAINodeFromSnapshotParams_JSONRoundTrip pins the wire shape the orchestrator's
// mirror of this struct must also produce: Temporal's data converter serializes that struct to
// JSON and deserializes into this one, so the two are only compatible if they agree field for
// field. wantJSON is written against the field names directly, not derived by marshaling this
// struct, so a field renamed or moved to the wrong group on either side of that mirror would
// redden this test even though marshal/unmarshal on this type alone still round-trips fine.
func TestExecuteAINodeFromSnapshotParams_JSONRoundTrip(t *testing.T) {
	nodeExecID := uuid.New()
	runID := uuid.New()
	templateNodeID := uuid.New()

	original := ExecuteAINodeFromSnapshotParams{
		Identity: Identity{
			NodeExecutionID: nodeExecID,
			RunID:           runID,
			TemplateNodeID:  templateNodeID,
		},
		Node: Node{
			ExecutorType:    "ai",
			Command:         "run.sh",
			PromptTemplate:  "do the thing",
			Model:           "opus",
			Effort:          "high",
			MaxTurns:        "10",
			MaxRetries:      "3",
			OutputSpec:      "{}",
			SupervisorLabel: "supervisor-node",
			Iteration:       2,
			NeedDecision:    true,
			NeedsPR:         true,
		},
		Inputs: Inputs{
			InputArtifacts:         map[string]string{"gate/file.png": "orgs/x/file.png"},
			Variables:              map[string]string{"run.id": "abc123"},
			RequiredInputArtifacts: []string{"gate/file.png"},
		},
		Repos: Repos{
			RepoURL:       "https://github.com/example/repo",
			WorkingBranch: "choruskube-run-abc123",
			List:          []map[string]any{{"name": "repo-a", "url": "https://github.com/example/repo-a"}},
		},
		TaskContext: TaskContext{
			TaskID:     "task-1",
			TaskTitle:  "Task Title",
			StoryID:    "story-1",
			StoryTitle: "Story Title",
			EpicID:     "epic-1",
			EpicTitle:  "Epic Title",
			OpenBlockers: []OpenBlockerParam{
				{ItemType: "task", ItemID: "blocker-1", Title: "Blocker", Status: "open"},
			},
		},
		Session: Session{
			ID:           "sess-1",
			ArtifactPath: "runs/x/session.jsonl",
		},
		OrgSlug: "acme",
	}

	wantJSON := `{
		"Identity": {
			"NodeExecutionID": "` + nodeExecID.String() + `",
			"RunID": "` + runID.String() + `",
			"TemplateNodeID": "` + templateNodeID.String() + `"
		},
		"Node": {
			"ExecutorType": "ai",
			"Command": "run.sh",
			"PromptTemplate": "do the thing",
			"Model": "opus",
			"Effort": "high",
			"MaxTurns": "10",
			"MaxRetries": "3",
			"OutputSpec": "{}",
			"SupervisorLabel": "supervisor-node",
			"Iteration": 2,
			"NeedDecision": true,
			"NeedsPR": true
		},
		"Inputs": {
			"InputArtifacts": {"gate/file.png": "orgs/x/file.png"},
			"Variables": {"run.id": "abc123"},
			"RequiredInputArtifacts": ["gate/file.png"]
		},
		"Repos": {
			"RepoURL": "https://github.com/example/repo",
			"WorkingBranch": "choruskube-run-abc123",
			"List": [{"name": "repo-a", "url": "https://github.com/example/repo-a"}]
		},
		"TaskContext": {
			"TaskID": "task-1",
			"TaskTitle": "Task Title",
			"StoryID": "story-1",
			"StoryTitle": "Story Title",
			"EpicID": "epic-1",
			"EpicTitle": "Epic Title",
			"OpenBlockers": [{"ItemType": "task", "ItemID": "blocker-1", "Title": "Blocker", "Status": "open"}]
		},
		"Session": {
			"ID": "sess-1",
			"ArtifactPath": "runs/x/session.jsonl"
		},
		"OrgSlug": "acme"
	}`

	gotBytes, err := json.Marshal(original)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	assert.JSONEq(t, wantJSON, string(gotBytes))

	var roundTripped ExecuteAINodeFromSnapshotParams
	if err := json.Unmarshal(gotBytes, &roundTripped); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	assert.Equal(t, original, roundTripped)

	// The golden JSON must also deserialize into the exact same value on its own -- this is
	// the direction a renamed field or a field moved to the wrong group would break even if
	// this type's own Marshal/Unmarshal pair still agreed with each other.
	var fromGolden ExecuteAINodeFromSnapshotParams
	if err := json.Unmarshal([]byte(wantJSON), &fromGolden); err != nil {
		t.Fatalf("unmarshal golden: %v", err)
	}
	assert.Equal(t, original, fromGolden)
}
