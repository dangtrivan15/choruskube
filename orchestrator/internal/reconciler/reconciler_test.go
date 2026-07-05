package reconciler

import (
	"context"
	"fmt"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// mockAPIClient implements APIClient for testing.
type mockAPIClient struct {
	statuses          map[string]string
	errors            map[string]error
	workloads         []WorkloadInfo
	terminatedExecIDs []uuid.UUID
}

func (m *mockAPIClient) GetWorkflowRunStatus(_ context.Context, runID uuid.UUID) (string, error) {
	if err, ok := m.errors[runID.String()]; ok {
		return "", err
	}
	if status, ok := m.statuses[runID.String()]; ok {
		return status, nil
	}
	return "", fmt.Errorf("api error 404: not found")
}

func (m *mockAPIClient) ListWorkloads(_ context.Context) ([]WorkloadInfo, error) {
	return m.workloads, nil
}

func (m *mockAPIClient) TerminateWorkload(_ context.Context, executionID uuid.UUID) error {
	m.terminatedExecIDs = append(m.terminatedExecIDs, executionID)
	return nil
}

func TestReconcileOnce_TerminatesOrphanedWorkloadsForTerminalRuns(t *testing.T) {
	runID := uuid.New()
	execID := uuid.New()

	api := &mockAPIClient{
		statuses: map[string]string{
			runID.String(): "completed",
		},
		workloads: []WorkloadInfo{
			{NodeExecutionID: execID, RunID: runID, ExecutionHandle: "agent-abc12345"},
		},
	}

	rec := New(api, DefaultConfig())
	rec.reconcileOnce(context.Background())

	require.Len(t, api.terminatedExecIDs, 1)
	assert.Equal(t, execID, api.terminatedExecIDs[0])
}

func TestReconcileOnce_KeepsWorkloadsForActiveRuns(t *testing.T) {
	runID := uuid.New()
	execID := uuid.New()

	api := &mockAPIClient{
		statuses: map[string]string{
			runID.String(): "running",
		},
		workloads: []WorkloadInfo{
			{NodeExecutionID: execID, RunID: runID, ExecutionHandle: "agent-abc12345"},
		},
	}

	rec := New(api, DefaultConfig())
	rec.reconcileOnce(context.Background())

	assert.Len(t, api.terminatedExecIDs, 0, "workload for running workflow should be kept")
}

func TestReconcileOnce_GracefullyHandlesAPIErrors(t *testing.T) {
	runID := uuid.New()
	execID := uuid.New()

	api := &mockAPIClient{
		errors: map[string]error{
			runID.String(): fmt.Errorf("connection refused"),
		},
		workloads: []WorkloadInfo{
			{NodeExecutionID: execID, RunID: runID, ExecutionHandle: "agent-abc12345"},
		},
	}

	rec := New(api, DefaultConfig())
	rec.reconcileOnce(context.Background())

	assert.Len(t, api.terminatedExecIDs, 0, "workload should survive API errors")
}

func TestReconcileOnce_TerminatesWorkloadsForFailedRuns(t *testing.T) {
	runID := uuid.New()
	execID := uuid.New()

	api := &mockAPIClient{
		statuses: map[string]string{
			runID.String(): "failed",
		},
		workloads: []WorkloadInfo{
			{NodeExecutionID: execID, RunID: runID, ExecutionHandle: "agent-abc12345"},
		},
	}

	rec := New(api, DefaultConfig())
	rec.reconcileOnce(context.Background())

	require.Len(t, api.terminatedExecIDs, 1)
	assert.Equal(t, execID, api.terminatedExecIDs[0])
}

func TestReconcileOnce_TerminatesWorkloadsForCancelledRuns(t *testing.T) {
	runID := uuid.New()
	execID := uuid.New()

	api := &mockAPIClient{
		statuses: map[string]string{
			runID.String(): "cancelled",
		},
		workloads: []WorkloadInfo{
			{NodeExecutionID: execID, RunID: runID, ExecutionHandle: "agent-abc12345"},
		},
	}

	rec := New(api, DefaultConfig())
	rec.reconcileOnce(context.Background())

	require.Len(t, api.terminatedExecIDs, 1)
	assert.Equal(t, execID, api.terminatedExecIDs[0])
}

func TestReconcileOnce_CachesRunStatusLookups(t *testing.T) {
	runID := uuid.New()
	execID1 := uuid.New()
	execID2 := uuid.New()

	callCount := 0
	api := &countingAPIClient{
		statuses:  map[string]string{runID.String(): "completed"},
		callCount: &callCount,
		workloads: []WorkloadInfo{
			{NodeExecutionID: execID1, RunID: runID, ExecutionHandle: "agent-1"},
			{NodeExecutionID: execID2, RunID: runID, ExecutionHandle: "agent-2"},
		},
	}

	rec := New(api, DefaultConfig())
	rec.reconcileOnce(context.Background())

	assert.Equal(t, 1, callCount, "run status should be cached across workloads in same reconciliation pass")
	assert.Len(t, api.terminatedExecIDs, 2, "both workloads should be terminated")
}

func TestReconcileOnce_SkipsWorkloadsWithoutRunID(t *testing.T) {
	api := &mockAPIClient{
		statuses: map[string]string{},
		workloads: []WorkloadInfo{
			{NodeExecutionID: uuid.New(), RunID: uuid.Nil, ExecutionHandle: "agent-nolabel"},
		},
	}

	rec := New(api, DefaultConfig())
	rec.reconcileOnce(context.Background())

	assert.Len(t, api.terminatedExecIDs, 0, "workload without run ID should be skipped")
}

func TestStartAndStop(t *testing.T) {
	api := &mockAPIClient{statuses: map[string]string{}}

	cfg := DefaultConfig()
	cfg.Interval = 50 * time.Millisecond

	rec := New(api, cfg)

	ctx, cancel := context.WithTimeout(context.Background(), 200*time.Millisecond)
	defer cancel()

	done := make(chan struct{})
	go func() {
		rec.Start(ctx)
		close(done)
	}()

	select {
	case <-done:
		// Reconciler stopped cleanly
	case <-time.After(2 * time.Second):
		t.Fatal("reconciler did not stop within timeout")
	}
}

// countingAPIClient tracks how many times GetWorkflowRunStatus is called.
type countingAPIClient struct {
	statuses          map[string]string
	callCount         *int
	workloads         []WorkloadInfo
	terminatedExecIDs []uuid.UUID
}

func (m *countingAPIClient) GetWorkflowRunStatus(_ context.Context, runID uuid.UUID) (string, error) {
	*m.callCount++
	if status, ok := m.statuses[runID.String()]; ok {
		return status, nil
	}
	return "", fmt.Errorf("api error 404: not found")
}

func (m *countingAPIClient) ListWorkloads(_ context.Context) ([]WorkloadInfo, error) {
	return m.workloads, nil
}

func (m *countingAPIClient) TerminateWorkload(_ context.Context, executionID uuid.UUID) error {
	m.terminatedExecIDs = append(m.terminatedExecIDs, executionID)
	return nil
}
