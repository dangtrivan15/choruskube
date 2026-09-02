package activity

import (
	"context"
	"testing"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	temporalactivity "go.temporal.io/sdk/activity"
	"go.temporal.io/sdk/testsuite"
	"go.temporal.io/sdk/workflow"

	"github.com/dangtrivan15/choruskube/orchestrator/internal/apiclient"
)

// withWorkflowRunID points activityInfo at a fake workflow scheduled by runID for the
// duration of the calling test, then restores it, and hands back context.Background()
// unchanged for use as the call's ctx argument. Package tests call guarded activity methods
// directly rather than through a live Temporal worker, and the SDK's own test harness has no
// way to pin an activity context to a chosen workflow id, so this is the seam that stands in
// for one.
func withWorkflowRunID(t *testing.T, runID uuid.UUID) context.Context {
	t.Helper()
	prev := activityInfo
	activityInfo = func(context.Context) temporalactivity.Info {
		return temporalactivity.Info{WorkflowExecution: workflow.Execution{ID: workflowIDPrefix + runID.String()}}
	}
	t.Cleanup(func() { activityInfo = prev })
	return context.Background()
}

// TestGuardRunRejectsAForeignRunThroughARealActivityContext exercises guardRun via a genuine
// Temporal activity context (not the withWorkflowRunID seam) to prove the guard wired into
// UpdateWorkflowRunStatus actually fires: the SDK test harness always assigns a fixed
// workflow id that is never a run's, so any claimed RunID must be rejected.
func TestGuardRunRejectsAForeignRunThroughARealActivityContext(t *testing.T) {
	var ts testsuite.WorkflowTestSuite
	env := ts.NewTestActivityEnvironment()

	acts := NewActivities(apiclient.NewClient("http://unused.invalid"), nil, nil, nil)
	env.RegisterActivity(acts.UpdateWorkflowRunStatus)

	_, err := env.ExecuteActivity(acts.UpdateWorkflowRunStatus, UpdateRunStatusParams{RunID: uuid.New(), Status: "running"})
	require.Error(t, err)
	assert.Contains(t, err.Error(), "is not a run's")
}

// TestGetGraphRuntimeRejectsAForeignRun, TestDeleteAgentJobRejectsAForeignRun and
// TestFetchPodLogsRejectsAForeignRun cover the three methods a first pass at this guard
// missed: GetGraphRuntime takes its run id as a bare argument rather than a params field, and
// DeleteAgentJob/FetchPodLogs previously carried no run id at all — a hijacked workflow task
// could delete another tenant's agent job, or exfiltrate its pod logs, by NodeExecutionID
// alone. Each test schedules the activity under one run and claims a different one.

func TestGetGraphRuntimeRejectsAForeignRun(t *testing.T) {
	acts := NewActivities(apiclient.NewClient("http://unused.invalid"), nil, nil, nil)

	_, err := acts.GetGraphRuntime(withWorkflowRunID(t, uuid.New()), uuid.New())
	require.Error(t, err)
	assert.Contains(t, err.Error(), "but was scheduled by run")
}

func TestDeleteAgentJobRejectsAForeignRun(t *testing.T) {
	acts := NewActivities(apiclient.NewClient("http://unused.invalid"), nil, nil, nil)

	err := acts.DeleteAgentJob(withWorkflowRunID(t, uuid.New()), DeleteAgentJobParams{
		RunID:           uuid.New(),
		NodeExecutionID: uuid.New(),
	})
	require.Error(t, err)
	assert.Contains(t, err.Error(), "but was scheduled by run")
}

func TestFetchPodLogsRejectsAForeignRun(t *testing.T) {
	acts := NewActivities(apiclient.NewClient("http://unused.invalid"), nil, nil, nil)

	_, err := acts.FetchPodLogs(withWorkflowRunID(t, uuid.New()), FetchPodLogsParams{
		RunID:           uuid.New(),
		NodeExecutionID: uuid.New(),
		TailLines:       50,
	})
	require.Error(t, err)
	assert.Contains(t, err.Error(), "but was scheduled by run")
}
