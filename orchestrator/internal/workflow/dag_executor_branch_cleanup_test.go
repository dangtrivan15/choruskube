package workflow

import (
	"fmt"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/mock"
	"go.temporal.io/sdk/workflow"

	"github.com/dangtrivan15/choruskube/orchestrator/internal/activity"
)

// branchCleanupRun stubs a one-AI-node run that completes successfully, so Step 6's cleanup is
// reachable. Callers add their own stub for whichever cleanup activities the test exercises.
func (s *DAGExecutorTestSuite) branchCleanupRun() (uuid.UUID, uuid.UUID) {
	nodeA := uuid.New()
	execA := uuid.New()
	runID := s.dagRunID()

	snapshot := `{
		"nodes": [
			{"templateNodeId": "` + nodeA.String() + `", "label": "A", "executorType": "ai", "timeoutSeconds": 1800, "isEntrypoint": true}
		],
		"edges": []
	}`

	s.env.OnActivity("UpdateWorkflowRunStatus", mock.Anything, mock.Anything).Return(nil)
	s.env.OnActivity("GetGraphRuntime", mock.Anything, runID).Return(snapshot, nil)
	s.env.OnActivity("WriteExecutionLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("InitRunLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("AppendRunLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == nodeA
	})).Return(execA, nil).Once()
	s.env.OnActivity("LoadPredecessorInputs", mock.Anything, mock.Anything).Return(map[string]string{}, nil).Maybe()
	s.env.OnActivity("LoadRequiredInputArtifacts", mock.Anything, mock.Anything).
		Return(activity.LoadRequiredInputArtifactsResult{}, nil).Maybe()
	s.env.OnActivity("LoadReviewHistoryJSON", mock.Anything, mock.Anything).Return("[]", nil).Maybe()
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.Anything).Return("no_decision", nil).Maybe()
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.Anything).
		Return(activity.CallbackResult{}, nil)

	return runID, nodeA
}

// TestBranchCleanupRunsOnTheWorkerQueue proves the plan runs on the workflow's own queue and the
// Worker step runs on the run's Worker queue with the plan's repos and the run's id, and that the
// legacy activity is never scheduled on the new version.
func (s *DAGExecutorTestSuite) TestBranchCleanupRunsOnTheWorkerQueue() {
	runID, _ := s.branchCleanupRun()
	starts := s.recordStarts(nil)
	repoID := uuid.New()
	plan := activity.BranchCleanupPlan{Repos: []activity.BranchCleanupRepo{
		{GitRepoID: repoID, OwnerRepo: "acme/app", DefaultBranch: "main"},
	}}

	s.env.OnActivity("PlanBranchCleanup", mock.Anything, mock.MatchedBy(func(p activity.BranchCleanupPlanParams) bool {
		return p.RunID == runID
	})).Return(plan, nil).Once()
	s.env.OnActivity("CleanupRunBranches", mock.Anything, mock.MatchedBy(func(p activity.BranchCleanupParams) bool {
		return p.RunID == runID && len(p.Repos) == 1 && p.Repos[0] == plan.Repos[0]
	})).Return(activity.BranchCleanupResult{}, nil).Once()

	s.env.ExecuteWorkflow(DAGExecutorWorkflow, DAGExecutorParams{
		RunID: runID, GraphVersion: 1, WorkerTaskQueue: "fleet-acme",
	})

	s.True(s.env.IsWorkflowCompleted())
	s.NoError(s.env.GetWorkflowError())
	s.env.AssertActivityNotCalled(s.T(), "DeleteStaleBranches", mock.Anything, mock.Anything)

	planInfos, _ := starts.of("PlanBranchCleanup")
	s.Require().Len(planInfos, 1)
	s.NotEqual("fleet-acme", planInfos[0].TaskQueue, "the plan step stays on the workflow's own queue")

	stepInfos, _ := starts.of("CleanupRunBranches")
	s.Require().Len(stepInfos, 1)
	s.Equal("fleet-acme", stepInfos[0].TaskQueue)
}

// TestBranchCleanupWorkerStepTimeouts pins the Worker step's activity options: the SDK's
// activity info has no ScheduleToStartTimeout field (see
// dag_executor_finalize_test.go's TestHumanGateFinalizeWorkerNeverPickedUpRefuses for the same
// pattern), so that one is read from branchCleanupActivityOptions directly; HeartbeatTimeout and
// TaskQueue are read from the started activity's own info.
func (s *DAGExecutorTestSuite) TestBranchCleanupWorkerStepTimeouts() {
	s.Equal(10*time.Minute, branchCleanupActivityOptions("fleet-acme").ScheduleToStartTimeout)

	runID, _ := s.branchCleanupRun()
	starts := s.recordStarts(nil)
	plan := activity.BranchCleanupPlan{Repos: []activity.BranchCleanupRepo{
		{GitRepoID: uuid.New(), OwnerRepo: "acme/app", DefaultBranch: "main"},
	}}
	s.env.OnActivity("PlanBranchCleanup", mock.Anything, mock.Anything).Return(plan, nil).Once()
	s.env.OnActivity("CleanupRunBranches", mock.Anything, mock.Anything).Return(activity.BranchCleanupResult{}, nil).Once()

	s.env.ExecuteWorkflow(DAGExecutorWorkflow, DAGExecutorParams{
		RunID: runID, GraphVersion: 1, WorkerTaskQueue: "fleet-acme",
	})

	s.True(s.env.IsWorkflowCompleted())
	stepInfos, _ := starts.of("CleanupRunBranches")
	s.Require().Len(stepInfos, 1)
	s.Equal("fleet-acme", stepInfos[0].TaskQueue)
	s.Equal(time.Minute, stepInfos[0].HeartbeatTimeout)
}

// TestBranchCleanupEmptyPlanSkipsTheWorker: an empty plan schedules no Worker step at all.
func (s *DAGExecutorTestSuite) TestBranchCleanupEmptyPlanSkipsTheWorker() {
	runID, _ := s.branchCleanupRun()
	s.env.OnActivity("PlanBranchCleanup", mock.Anything, mock.MatchedBy(func(p activity.BranchCleanupPlanParams) bool {
		return p.RunID == runID
	})).Return(activity.BranchCleanupPlan{}, nil).Once()

	s.env.ExecuteWorkflow(DAGExecutorWorkflow, DAGExecutorParams{
		RunID: runID, GraphVersion: 1, WorkerTaskQueue: "fleet-acme",
	})

	s.True(s.env.IsWorkflowCompleted())
	s.NoError(s.env.GetWorkflowError())
	s.env.AssertActivityNotCalled(s.T(), "CleanupRunBranches", mock.Anything, mock.Anything)
}

// TestBranchCleanupPlanErrorStillCompletes: a plan failure is logged and the workflow still
// completes without error, and schedules no Worker step.
func (s *DAGExecutorTestSuite) TestBranchCleanupPlanErrorStillCompletes() {
	runID, _ := s.branchCleanupRun()
	s.env.OnActivity("PlanBranchCleanup", mock.Anything, mock.Anything).
		Return(activity.BranchCleanupPlan{}, fmt.Errorf("api error 500: internal error"))

	s.env.ExecuteWorkflow(DAGExecutorWorkflow, DAGExecutorParams{
		RunID: runID, GraphVersion: 1, WorkerTaskQueue: "fleet-acme",
	})

	s.True(s.env.IsWorkflowCompleted())
	s.NoError(s.env.GetWorkflowError())
	s.env.AssertActivityNotCalled(s.T(), "CleanupRunBranches", mock.Anything, mock.Anything)
}

// TestBranchCleanupWorkerErrorStillCompletes: a Worker-step failure is tolerated the same way —
// the workflow's own error is nil, and the run's final status write (already stubbed to
// "completed" by branchCleanupRun, which is this single-node graph's only possible outcome)
// happened before cleanup ran and is unaffected by its failure.
func (s *DAGExecutorTestSuite) TestBranchCleanupWorkerErrorStillCompletes() {
	runID, _ := s.branchCleanupRun()
	starts := s.recordStarts(nil)
	plan := activity.BranchCleanupPlan{Repos: []activity.BranchCleanupRepo{
		{GitRepoID: uuid.New(), OwnerRepo: "acme/app", DefaultBranch: "main"},
	}}
	s.env.OnActivity("PlanBranchCleanup", mock.Anything, mock.Anything).Return(plan, nil).Once()
	// No .Once(): the Worker step's own RetryPolicy (MaximumAttempts: 3) retries an erroring
	// attempt, so this stub must stay eligible across every attempt.
	s.env.OnActivity("CleanupRunBranches", mock.Anything, mock.Anything).
		Return(activity.BranchCleanupResult{}, fmt.Errorf("obtain GitHub token: boom"))

	s.env.ExecuteWorkflow(DAGExecutorWorkflow, DAGExecutorParams{
		RunID: runID, GraphVersion: 1, WorkerTaskQueue: "fleet-acme",
	})

	s.True(s.env.IsWorkflowCompleted())
	s.NoError(s.env.GetWorkflowError())
	statusInfos, statusAt := starts.of("UpdateWorkflowRunStatus")
	cleanupInfos, cleanupAt := starts.of("CleanupRunBranches")
	s.Require().NotEmpty(statusInfos)
	s.Require().NotEmpty(cleanupInfos)
	s.True(statusAt[len(statusAt)-1].Before(cleanupAt[0]) || statusAt[len(statusAt)-1].Equal(cleanupAt[0]),
		"the run's final status write happens before the Worker cleanup step runs")
}

// TestBranchCleanupWithoutAWorkerQueueKeepsBranches: on the new version, a run with no Worker
// task queue schedules none of the three cleanup activities.
func (s *DAGExecutorTestSuite) TestBranchCleanupWithoutAWorkerQueueKeepsBranches() {
	runID, _ := s.branchCleanupRun()

	s.env.ExecuteWorkflow(DAGExecutorWorkflow, DAGExecutorParams{
		RunID: runID, GraphVersion: 1,
	})

	s.True(s.env.IsWorkflowCompleted())
	s.NoError(s.env.GetWorkflowError())
	s.env.AssertActivityNotCalled(s.T(), "DeleteStaleBranches", mock.Anything, mock.Anything)
	s.env.AssertActivityNotCalled(s.T(), "PlanBranchCleanup", mock.Anything, mock.Anything)
	s.env.AssertActivityNotCalled(s.T(), "CleanupRunBranches", mock.Anything, mock.Anything)
}

// TestBranchCleanupOldVersionUsesTheAPI: a history recorded before the marker calls only the
// legacy activity, even when the run carries a Worker task queue.
func (s *DAGExecutorTestSuite) TestBranchCleanupOldVersionUsesTheAPI() {
	runID, _ := s.branchCleanupRun()
	s.env.OnGetVersion("branch-cleanup-on-worker", workflow.DefaultVersion, 1).Return(workflow.DefaultVersion)
	s.env.OnActivity("DeleteStaleBranches", mock.Anything, mock.MatchedBy(func(p activity.DeleteStaleBranchesParams) bool {
		return p.RunID == runID
	})).Return(nil).Once()

	s.env.ExecuteWorkflow(DAGExecutorWorkflow, DAGExecutorParams{
		RunID: runID, GraphVersion: 1, WorkerTaskQueue: "fleet-acme",
	})

	s.True(s.env.IsWorkflowCompleted())
	s.NoError(s.env.GetWorkflowError())
	s.env.AssertActivityNotCalled(s.T(), "PlanBranchCleanup", mock.Anything, mock.Anything)
	s.env.AssertActivityNotCalled(s.T(), "CleanupRunBranches", mock.Anything, mock.Anything)
}
