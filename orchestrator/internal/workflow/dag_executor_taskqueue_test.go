package workflow

import (
	"context"

	"github.com/google/uuid"
	"github.com/stretchr/testify/mock"
	temporalactivity "go.temporal.io/sdk/activity"
	"go.temporal.io/sdk/converter"

	"github.com/dangtrivan15/choruskube/orchestrator/internal/activity"
)

// TestAIActivityUsesWorkerTaskQueueFromInput verifies that a configured
// WorkerTaskQueue reaches the AI activity's dispatch queue verbatim.
func (s *DAGExecutorTestSuite) TestAIActivityUsesWorkerTaskQueueFromInput() {
	nodeA := uuid.New()
	execA := uuid.New()
	runID := s.dagRunID()

	snapshot := `{
		"nodes": [
			{"templateNodeId": "` + nodeA.String() + `", "label": "AI", "executorType": "ai", "timeoutSeconds": 1800, "isEntrypoint": true}
		],
		"edges": []
	}`

	var seenQueue string
	s.env.SetOnActivityStartedListener(func(info *temporalactivity.Info, _ context.Context, _ converter.EncodedValues) {
		if info.ActivityType.Name == "ExecuteAINodeFromSnapshot" {
			seenQueue = info.TaskQueue
		}
	})

	s.env.OnActivity("UpdateWorkflowRunStatus", mock.Anything, mock.Anything).Return(nil)
	s.env.OnActivity("GetGraphRuntime", mock.Anything, runID).Return(snapshot, nil)
	s.env.OnActivity("WriteExecutionLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("InitRunLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("AppendRunLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("LoadPredecessorInputs", mock.Anything, mock.Anything).Return(map[string]string{}, nil).Maybe()
	s.env.OnActivity("LoadRequiredInputArtifacts", mock.Anything, mock.Anything).Return(activity.LoadRequiredInputArtifactsResult{}, nil).Maybe()
	s.env.OnActivity("LoadReviewHistoryJSON", mock.Anything, mock.Anything).Return("[]", nil).Maybe()

	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == nodeA
	})).Return(execA, nil).Once()

	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.Identity.TemplateNodeID == nodeA
	})).Return(activity.CallbackResult{}, nil).Once()

	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execA
	})).Return("no_decision", nil).Once()

	s.env.ExecuteWorkflow(DAGExecutorWorkflow, DAGExecutorParams{
		RunID:           runID,
		GraphVersion:    1,
		WorkerTaskQueue: "fleet-acme",
	})

	s.True(s.env.IsWorkflowCompleted())
	s.NoError(s.env.GetWorkflowError())
	s.Equal("fleet-acme", seenQueue)
}

// TestAIActivityFallsBackWhenNoWorkerTaskQueue verifies that an unset
// WorkerTaskQueue lets the SDK default the AI activity onto the
// workflow's own task queue, matching what an in-flight workflow started
// before this field existed would recompute on replay.
func (s *DAGExecutorTestSuite) TestAIActivityFallsBackWhenNoWorkerTaskQueue() {
	nodeA := uuid.New()
	execA := uuid.New()
	runID := s.dagRunID()

	snapshot := `{
		"nodes": [
			{"templateNodeId": "` + nodeA.String() + `", "label": "AI", "executorType": "ai", "timeoutSeconds": 1800, "isEntrypoint": true}
		],
		"edges": []
	}`

	var seenQueue string
	s.env.SetOnActivityStartedListener(func(info *temporalactivity.Info, _ context.Context, _ converter.EncodedValues) {
		if info.ActivityType.Name == "ExecuteAINodeFromSnapshot" {
			seenQueue = info.TaskQueue
		}
	})

	s.env.OnActivity("UpdateWorkflowRunStatus", mock.Anything, mock.Anything).Return(nil)
	s.env.OnActivity("GetGraphRuntime", mock.Anything, runID).Return(snapshot, nil)
	s.env.OnActivity("WriteExecutionLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("InitRunLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("AppendRunLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("LoadPredecessorInputs", mock.Anything, mock.Anything).Return(map[string]string{}, nil).Maybe()
	s.env.OnActivity("LoadRequiredInputArtifacts", mock.Anything, mock.Anything).Return(activity.LoadRequiredInputArtifactsResult{}, nil).Maybe()
	s.env.OnActivity("LoadReviewHistoryJSON", mock.Anything, mock.Anything).Return("[]", nil).Maybe()

	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == nodeA
	})).Return(execA, nil).Once()

	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.Identity.TemplateNodeID == nodeA
	})).Return(activity.CallbackResult{}, nil).Once()

	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execA
	})).Return("no_decision", nil).Once()

	s.env.ExecuteWorkflow(DAGExecutorWorkflow, DAGExecutorParams{
		RunID:        runID,
		GraphVersion: 1,
	})

	s.True(s.env.IsWorkflowCompleted())
	s.NoError(s.env.GetWorkflowError())
	s.Equal("default-test-taskqueue", seenQueue)
}
