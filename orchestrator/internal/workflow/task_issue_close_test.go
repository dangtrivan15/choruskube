package workflow

import (
	"context"
	"errors"
	"sync"
	"testing"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
	"github.com/stretchr/testify/require"
	temporalactivity "go.temporal.io/sdk/activity"
	"go.temporal.io/sdk/converter"
	"go.temporal.io/sdk/temporal"
	"go.temporal.io/sdk/testsuite"

	"github.com/dangtrivan15/choruskube/orchestrator/internal/activity"
	"github.com/dangtrivan15/choruskube/orchestrator/internal/apiclient"
)

// taskIssueCloseEnv is a test workflow environment that records the task queue of every activity
// it starts, keyed by activity type.
type taskIssueCloseEnv struct {
	*testsuite.TestWorkflowEnvironment
	mu     sync.Mutex
	queues map[string][]string
}

func newTaskIssueCloseEnv(t *testing.T) *taskIssueCloseEnv {
	t.Helper()
	var ts testsuite.WorkflowTestSuite
	e := &taskIssueCloseEnv{TestWorkflowEnvironment: ts.NewTestWorkflowEnvironment(), queues: map[string][]string{}}
	var a *activity.Activities
	e.RegisterActivity(a)
	e.SetOnActivityStartedListener(func(info *temporalactivity.Info, _ context.Context, _ converter.EncodedValues) {
		e.mu.Lock()
		defer e.mu.Unlock()
		e.queues[info.ActivityType.Name] = append(e.queues[info.ActivityType.Name], info.TaskQueue)
	})
	t.Cleanup(func() { e.AssertExpectations(t) })
	return e
}

func (e *taskIssueCloseEnv) started(activityType string) []string {
	e.mu.Lock()
	defer e.mu.Unlock()
	return append([]string(nil), e.queues[activityType]...)
}

func taskIssueCloseInput() activity.CloseTaskIssueParams {
	return activity.CloseTaskIssueParams{
		TaskID:          uuid.New(),
		RunID:           uuid.New(),
		OwnerRepo:       "acme/api",
		IssueNumber:     42,
		WorkerTaskQueue: "fleet-acme",
	}
}

func TestCloseTaskIssueWorkflow_ClosesOnTheWorkerThenRecordsOnTheAPI(t *testing.T) {
	env := newTaskIssueCloseEnv(t)
	p := taskIssueCloseInput()

	env.OnActivity("CloseTaskIssue", mock.Anything, activity.TaskIssueCloseParams{
		TaskID: p.TaskID, RunID: p.RunID, OwnerRepo: p.OwnerRepo, IssueNumber: p.IssueNumber,
	}).Return(activity.TaskIssueCloseResult{Outcome: activity.TaskIssueClosed}, nil).Once()
	env.OnActivity("RecordTaskIssueClosed", mock.Anything, activity.RecordTaskIssueClosedParams{
		TaskID: p.TaskID, IssueNumber: p.IssueNumber,
	}).Return(nil).Once()

	env.ExecuteWorkflow(CloseTaskIssueWorkflow, p)

	require.True(t, env.IsWorkflowCompleted())
	require.NoError(t, env.GetWorkflowError())
	assert.Equal(t, []string{"fleet-acme"}, env.started("CloseTaskIssue"))
	record := env.started("RecordTaskIssueClosed")
	require.Len(t, record, 1)
	assert.NotEqual(t, "fleet-acme", record[0], "the record step stays on the workflow's own queue")
}

func TestTaskIssueCloseWorkerOptions_WaitRatherThanFail(t *testing.T) {
	opts := taskIssueCloseWorkerOptions("q")

	assert.Equal(t, "q", opts.TaskQueue)
	assert.Zero(t, opts.ScheduleToStartTimeout)
	assert.Zero(t, opts.ScheduleToCloseTimeout)
	require.NotNil(t, opts.RetryPolicy)
	assert.Equal(t, int32(10), opts.RetryPolicy.MaximumAttempts)
}

func TestTaskIssueCloseAPIOptions_StayOnTheWorkflowsQueue(t *testing.T) {
	opts := taskIssueCloseAPIOptions()

	assert.Empty(t, opts.TaskQueue)
	require.NotNil(t, opts.RetryPolicy)
	assert.Equal(t, int32(10), opts.RetryPolicy.MaximumAttempts)
}

func TestCloseTaskIssueWorkflow_ARefusalRecordsNothing(t *testing.T) {
	env := newTaskIssueCloseEnv(t)

	env.OnActivity("CloseTaskIssue", mock.Anything, mock.Anything).
		Return(activity.TaskIssueCloseResult{Outcome: activity.TaskIssueRefused, Status: 404}, nil).Once()

	env.ExecuteWorkflow(CloseTaskIssueWorkflow, taskIssueCloseInput())

	require.True(t, env.IsWorkflowCompleted())
	require.NoError(t, env.GetWorkflowError())
	assert.Empty(t, env.started("RecordTaskIssueClosed"))
}

func TestCloseTaskIssueWorkflow_AGitHubStepFailureFailsTheWorkflow(t *testing.T) {
	env := newTaskIssueCloseEnv(t)

	env.OnActivity("CloseTaskIssue", mock.Anything, mock.Anything).
		Return(activity.TaskIssueCloseResult{}, errors.New("github unavailable"))

	env.ExecuteWorkflow(CloseTaskIssueWorkflow, taskIssueCloseInput())

	require.True(t, env.IsWorkflowCompleted())
	require.Error(t, env.GetWorkflowError())
	assert.Len(t, env.started("CloseTaskIssue"), 10, "the retry policy's attempts are exhausted first")
	assert.Empty(t, env.started("RecordTaskIssueClosed"))
}

func TestCloseTaskIssueWorkflow_ReturnsTheRecordStepsRejection(t *testing.T) {
	env := newTaskIssueCloseEnv(t)

	env.OnActivity("CloseTaskIssue", mock.Anything, mock.Anything).
		Return(activity.TaskIssueCloseResult{Outcome: activity.TaskIssueClosed}, nil).Once()
	env.OnActivity("RecordTaskIssueClosed", mock.Anything, mock.Anything).
		Return(temporal.NewNonRetryableApplicationError("mark task issue closed", apiclient.TaskIssueRejectedType, nil)).Once()

	env.ExecuteWorkflow(CloseTaskIssueWorkflow, taskIssueCloseInput())

	require.True(t, env.IsWorkflowCompleted())
	err := env.GetWorkflowError()
	var appErr *temporal.ApplicationError
	require.True(t, errors.As(err, &appErr), "got %v", err)
	assert.Equal(t, apiclient.TaskIssueRejectedType, appErr.Type())
}

func TestCloseTaskIssueWorkflow_InvalidInputSchedulesNothing(t *testing.T) {
	blankQueue := taskIssueCloseInput()
	blankQueue.WorkerTaskQueue = ""
	noIssue := taskIssueCloseInput()
	noIssue.IssueNumber = 0

	for name, p := range map[string]activity.CloseTaskIssueParams{"blank worker queue": blankQueue, "issue 0": noIssue} {
		t.Run(name, func(t *testing.T) {
			env := newTaskIssueCloseEnv(t)

			env.ExecuteWorkflow(CloseTaskIssueWorkflow, p)

			require.True(t, env.IsWorkflowCompleted())
			err := env.GetWorkflowError()
			var appErr *temporal.ApplicationError
			require.True(t, errors.As(err, &appErr), "got %v", err)
			assert.True(t, appErr.NonRetryable())
			assert.Empty(t, env.started("CloseTaskIssue"))
			assert.Empty(t, env.started("RecordTaskIssueClosed"))
		})
	}
}
