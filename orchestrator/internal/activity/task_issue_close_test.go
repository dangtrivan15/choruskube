package activity

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	temporalactivity "go.temporal.io/sdk/activity"
	"go.temporal.io/sdk/temporal"
	"go.temporal.io/sdk/testsuite"
	"go.temporal.io/sdk/workflow"

	"github.com/dangtrivan15/choruskube/orchestrator/internal/apiclient"
)

// withIssueCloseWorkflowTaskID is withWorkflowRunID for the task issue close workflow's binding.
func withIssueCloseWorkflowTaskID(t *testing.T, taskID uuid.UUID) context.Context {
	t.Helper()
	prev := activityInfo
	activityInfo = func(context.Context) temporalactivity.Info {
		return temporalactivity.Info{WorkflowExecution: workflow.Execution{ID: taskIssueCloseWorkflowIDPrefix + taskID.String()}}
	}
	t.Cleanup(func() { activityInfo = prev })
	return context.Background()
}

func newTaskIssueCloseActivities(t *testing.T, handler http.HandlerFunc) (*Activities, *atomic.Int32) {
	t.Helper()
	var hits atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		assert.Equal(t, "Bearer orchestrator-secret", r.Header.Get("Authorization"))
		handler(w, r)
	}))
	t.Cleanup(server.Close)
	return NewActivities(apiclient.NewAuthenticatedClient(server.URL, "orchestrator-secret"), nil, nil, nil), &hits
}

func TestTaskIDFromIssueCloseWorkflowID(t *testing.T) {
	taskID := uuid.New()

	got, err := taskIDFromIssueCloseWorkflowID("choruskube-task-issue-close-" + taskID.String())
	require.NoError(t, err)
	assert.Equal(t, taskID, got)

	for _, id := range []string{
		workflowIDPrefix + taskID.String(),
		"choruskube-task-issue-close-not-a-uuid",
		"garbage",
		"",
	} {
		_, err := taskIDFromIssueCloseWorkflowID(id)
		assert.Errorf(t, err, "want %q rejected", id)
	}
}

func TestGuardTaskIssueCloseRejectsAForeignTask(t *testing.T) {
	ctx := withIssueCloseWorkflowTaskID(t, uuid.New())

	err := guardTaskIssueClose(ctx, uuid.New())

	var appErr *temporal.ApplicationError
	require.True(t, errors.As(err, &appErr))
	assert.True(t, appErr.NonRetryable())
	assert.Equal(t, taskIssueCloseBindingType, appErr.Type())
	assert.Contains(t, err.Error(), "but was scheduled by task")
}

func TestGuardTaskIssueCloseRejectsARunWorkflow(t *testing.T) {
	taskID := uuid.New()
	ctx := withWorkflowRunID(t, taskID)

	err := guardTaskIssueClose(ctx, taskID)

	var appErr *temporal.ApplicationError
	require.True(t, errors.As(err, &appErr))
	assert.True(t, appErr.NonRetryable())
	assert.Equal(t, taskIssueCloseBindingType, appErr.Type())
}

// TestGuardTaskIssueCloseRejectsAForeignTaskThroughARealActivityContext mirrors
// TestGuardRunRejectsAForeignRunThroughARealActivityContext: the SDK test harness always assigns
// a fixed workflow id that is never a task issue close's, so any claimed Task must be rejected.
func TestGuardTaskIssueCloseRejectsAForeignTaskThroughARealActivityContext(t *testing.T) {
	var ts testsuite.WorkflowTestSuite
	env := ts.NewTestActivityEnvironment()

	acts := NewActivities(apiclient.NewClient("http://unused.invalid"), nil, nil, nil)
	env.RegisterActivity(acts.RecordTaskIssueClosed)

	_, err := env.ExecuteActivity(acts.RecordTaskIssueClosed, RecordTaskIssueClosedParams{TaskID: uuid.New(), IssueNumber: 7})
	require.Error(t, err)
	assert.Contains(t, err.Error(), "is not a task issue close's")
}

func TestRecordTaskIssueClosed_MarksTheBoundTasksIssue(t *testing.T) {
	taskID := uuid.New()
	acts, hits := newTaskIssueCloseActivities(t, func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodPost, r.Method)
		assert.Equal(t, "/internal/tasks/"+taskID.String()+"/github-issue/closed", r.URL.Path)
		raw, err := io.ReadAll(r.Body)
		require.NoError(t, err)
		assert.JSONEq(t, `{"issueNumber":42}`, string(raw))
		w.WriteHeader(http.StatusNoContent)
	})

	err := acts.RecordTaskIssueClosed(withIssueCloseWorkflowTaskID(t, taskID),
		RecordTaskIssueClosedParams{TaskID: taskID, IssueNumber: 42})

	require.NoError(t, err)
	assert.Equal(t, int32(1), hits.Load())
}

func TestRecordTaskIssueClosed_RefusesAnotherTasksID(t *testing.T) {
	acts, hits := newTaskIssueCloseActivities(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	})

	err := acts.RecordTaskIssueClosed(withIssueCloseWorkflowTaskID(t, uuid.New()),
		RecordTaskIssueClosedParams{TaskID: uuid.New(), IssueNumber: 42})

	require.Error(t, err)
	assert.Zero(t, hits.Load(), "a close workflow must not record another Task's issue")
}

func TestRecordTaskIssueClosed_PassesARejectionThroughUnwrapped(t *testing.T) {
	taskID := uuid.New()
	acts, _ := newTaskIssueCloseActivities(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusConflict)
	})

	err := acts.RecordTaskIssueClosed(withIssueCloseWorkflowTaskID(t, taskID),
		RecordTaskIssueClosedParams{TaskID: taskID, IssueNumber: 42})

	appErr, ok := err.(*temporal.ApplicationError)
	require.Truef(t, ok, "want the *ApplicationError itself, got %T: %v", err, err)
	assert.True(t, appErr.NonRetryable())
	assert.Equal(t, apiclient.TaskIssueRejectedType, appErr.Type())
}

func TestCloseTaskIssueStubAlwaysErrors(t *testing.T) {
	var acts *Activities
	_, err := acts.CloseTaskIssue(context.Background(), TaskIssueCloseParams{
		TaskID: uuid.New(), RunID: uuid.New(), OwnerRepo: "acme/api", IssueNumber: 1,
	})
	require.Error(t, err)
	assert.Contains(t, err.Error(), "dispatched to Worker task queue")
}

// The Go half of the api-server's CloseTaskIssueWorkflowInput wire-contract pin.
func TestCloseTaskIssueParamsDecodesTheAPIServersInput(t *testing.T) {
	const literal = `{"taskId":"0b0e6f43-6b0a-4a8e-9f4e-2f6a1c9d1a01",` +
		`"runId":"5c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f",` +
		`"ownerRepo":"acme/api","issueNumber":42,"workerTaskQueue":"fleet-acme"}`

	var p CloseTaskIssueParams
	require.NoError(t, json.Unmarshal([]byte(literal), &p))

	assert.Equal(t, CloseTaskIssueParams{
		TaskID:          uuid.MustParse("0b0e6f43-6b0a-4a8e-9f4e-2f6a1c9d1a01"),
		RunID:           uuid.MustParse("5c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f"),
		OwnerRepo:       "acme/api",
		IssueNumber:     42,
		WorkerTaskQueue: "fleet-acme",
	}, p)
}

// The orchestrator's half of the Worker's TaskIssueCloseParams/TaskIssueCloseResult pin: the
// same literals, compared as strings.
func TestTaskIssueCloseWireTypesMarshalToTheWorkersJSON(t *testing.T) {
	params, err := json.Marshal(TaskIssueCloseParams{
		TaskID:      uuid.MustParse("0b0e6f43-6b0a-4a8e-9f4e-2f6a1c9d1a01"),
		RunID:       uuid.MustParse("5c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f"),
		OwnerRepo:   "acme/api",
		IssueNumber: 42,
	})
	require.NoError(t, err)
	assert.Equal(t, `{"taskId":"0b0e6f43-6b0a-4a8e-9f4e-2f6a1c9d1a01",`+
		`"runId":"5c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f","ownerRepo":"acme/api","issueNumber":42}`, string(params))

	closed, err := json.Marshal(TaskIssueCloseResult{Outcome: TaskIssueClosed})
	require.NoError(t, err)
	assert.Equal(t, `{"outcome":"CLOSED","status":0}`, string(closed))

	refused, err := json.Marshal(TaskIssueCloseResult{Outcome: TaskIssueRefused, Status: 404})
	require.NoError(t, err)
	assert.Equal(t, `{"outcome":"REFUSED","status":404}`, string(refused))
}
