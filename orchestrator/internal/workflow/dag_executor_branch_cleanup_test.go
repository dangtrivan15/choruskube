package workflow

import (
	"fmt"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/mock"
	"go.temporal.io/sdk/workflow"

	"github.com/dangtrivan15/choruskube/orchestrator/internal/activity"
)

// branchCleanupRun stubs a one-AI-node run that completes successfully, so Step 6's cleanup is
// reachable, and returns the run id and a reader of the run statuses written, in order. Callers
// add their own stub for whichever cleanup activities the test exercises.
func (s *DAGExecutorTestSuite) branchCleanupRun() (uuid.UUID, func() []string) {
	nodeA := uuid.New()
	execA := uuid.New()
	runID := s.dagRunID()

	snapshot := `{
		"nodes": [
			{"templateNodeId": "` + nodeA.String() + `", "label": "A", "executorType": "ai", "timeoutSeconds": 1800, "isEntrypoint": true}
		],
		"edges": []
	}`

	var mu sync.Mutex
	var statuses []string
	s.env.OnActivity("UpdateWorkflowRunStatus", mock.Anything, mock.Anything).
		Run(func(args mock.Arguments) {
			mu.Lock()
			defer mu.Unlock()
			statuses = append(statuses, args.Get(1).(activity.UpdateRunStatusParams).Status)
		}).Return(nil)
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

	return runID, func() []string {
		mu.Lock()
		defer mu.Unlock()
		return append([]string(nil), statuses...)
	}
}

// assertCleanupPlanned guards a test that asserts no Worker step against passing because cleanup
// returned before it asked for a plan at all.
func (s *DAGExecutorTestSuite) assertCleanupPlanned(runID uuid.UUID) {
	s.env.AssertActivityCalled(s.T(), "PlanBranchCleanup", mock.Anything, mock.MatchedBy(func(p activity.BranchCleanupPlanParams) bool {
		return p.RunID == runID
	}))
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
	s.cleanupPlan = plan

	s.env.OnActivity("CleanupRunBranches", mock.Anything, mock.MatchedBy(func(p activity.BranchCleanupParams) bool {
		return p.RunID == runID && len(p.Repos) == 1 && p.Repos[0] == plan.Repos[0]
	})).Return(activity.BranchCleanupResult{}, nil).Once()

	s.env.ExecuteWorkflow(DAGExecutorWorkflow, DAGExecutorParams{
		RunID: runID, GraphVersion: 1, WorkerTaskQueue: "fleet-acme",
	})

	s.True(s.env.IsWorkflowCompleted())
	s.NoError(s.env.GetWorkflowError())
	s.env.AssertActivityNotCalled(s.T(), "DeleteStaleBranches", mock.Anything, mock.Anything)
	s.assertCleanupPlanned(runID)

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
	s.cleanupPlan = plan
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
	// The suite's default PlanBranchCleanup stub already answers with an empty plan.

	s.env.ExecuteWorkflow(DAGExecutorWorkflow, DAGExecutorParams{
		RunID: runID, GraphVersion: 1, WorkerTaskQueue: "fleet-acme",
	})

	s.True(s.env.IsWorkflowCompleted())
	s.NoError(s.env.GetWorkflowError())
	s.assertCleanupPlanned(runID)
	s.env.AssertActivityNotCalled(s.T(), "CleanupRunBranches", mock.Anything, mock.Anything)
}

// TestBranchCleanupPlanErrorStillCompletes: a plan failure is logged and the workflow still
// completes without error, and schedules no Worker step.
func (s *DAGExecutorTestSuite) TestBranchCleanupPlanErrorStillCompletes() {
	runID, _ := s.branchCleanupRun()
	s.cleanupPlanErr = fmt.Errorf("api error 500: internal error")

	s.env.ExecuteWorkflow(DAGExecutorWorkflow, DAGExecutorParams{
		RunID: runID, GraphVersion: 1, WorkerTaskQueue: "fleet-acme",
	})

	s.True(s.env.IsWorkflowCompleted())
	s.NoError(s.env.GetWorkflowError())
	s.assertCleanupPlanned(runID)
	s.env.AssertActivityNotCalled(s.T(), "CleanupRunBranches", mock.Anything, mock.Anything)
}

// TestBranchCleanupWorkerErrorStillCompletes: a Worker-step failure is tolerated the same way —
// the workflow's own error is nil, the run's last status write is "completed", and no status
// write follows the cleanup step that failed.
func (s *DAGExecutorTestSuite) TestBranchCleanupWorkerErrorStillCompletes() {
	runID, statuses := s.branchCleanupRun()
	starts := s.recordStarts(nil)
	plan := activity.BranchCleanupPlan{Repos: []activity.BranchCleanupRepo{
		{GitRepoID: uuid.New(), OwnerRepo: "acme/app", DefaultBranch: "main"},
	}}
	s.cleanupPlan = plan
	// No .Once(): the Worker step's own RetryPolicy (MaximumAttempts: 3) retries an erroring
	// attempt, so this stub must stay eligible across every attempt.
	s.env.OnActivity("CleanupRunBranches", mock.Anything, mock.Anything).
		Return(activity.BranchCleanupResult{}, fmt.Errorf("obtain GitHub token: boom"))

	s.env.ExecuteWorkflow(DAGExecutorWorkflow, DAGExecutorParams{
		RunID: runID, GraphVersion: 1, WorkerTaskQueue: "fleet-acme",
	})

	s.True(s.env.IsWorkflowCompleted())
	s.NoError(s.env.GetWorkflowError())
	written := statuses()
	s.Require().NotEmpty(written)
	s.Equal("completed", written[len(written)-1])
	s.NotContains(written, "failed")

	seq := starts.sequence()
	lastStatusWrite, firstCleanup := -1, -1
	for i, name := range seq {
		if name == "UpdateWorkflowRunStatus" {
			lastStatusWrite = i
		}
		if name == "CleanupRunBranches" && firstCleanup < 0 {
			firstCleanup = i
		}
	}
	s.Require().GreaterOrEqual(firstCleanup, 0, "the Worker step must have run: %v", seq)
	s.Less(lastStatusWrite, firstCleanup, "no run status write may follow the cleanup step: %v", seq)
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
