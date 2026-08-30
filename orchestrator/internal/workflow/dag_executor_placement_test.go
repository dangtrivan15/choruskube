package workflow

import (
	"github.com/google/uuid"
	"github.com/stretchr/testify/mock"

	"github.com/dangtrivan15/choruskube/orchestrator/internal/activity"
)

// TestDeniedPlacementFailsTheNodeAndSkipsDispatch verifies that a denied placement
// check stamps the node execution "failed" with the denial reason and fails the run,
// without ever dispatching the agent step.
func (s *DAGExecutorTestSuite) TestDeniedPlacementFailsTheNodeAndSkipsDispatch() {
	nodeA := uuid.New()
	execA := uuid.New()
	runID := uuid.New()

	snapshot := `{
		"nodes": [
			{"templateNodeId": "` + nodeA.String() + `", "label": "A", "executorType": "ai", "timeoutSeconds": 1800, "isEntrypoint": true}
		],
		"edges": []
	}`

	s.placementDecision = activity.CheckNodePlacementResult{Allowed: false, Reason: "fleet offline"}

	s.env.OnActivity("UpdateWorkflowRunStatus", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("GetGraphRuntime", mock.Anything, runID).Return(snapshot, nil)
	s.env.OnActivity("WriteExecutionLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("InitRunLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("AppendRunLog", mock.Anything, mock.Anything).Return(nil).Maybe()

	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == nodeA
	})).Return(execA, nil).Once()

	// The reason must land on the node execution row so an operator can see why the
	// step never started.
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.MatchedBy(func(p activity.UpdateNodeExecStatusParams) bool {
		return p.NodeExecutionID == execA && p.Status == "failed" &&
			p.ErrorMessage != nil && *p.ErrorMessage == "fleet offline"
	})).Return(nil).Once()

	// ExecuteAINodeFromSnapshot is deliberately NOT stubbed: if the workflow dispatches
	// it anyway the test fails on an unexpected activity rather than passing quietly.

	s.env.ExecuteWorkflow(DAGExecutorWorkflow, DAGExecutorParams{
		RunID: runID, GraphVersion: 1,
	})

	s.True(s.env.IsWorkflowCompleted())
	err := s.env.GetWorkflowError()
	s.Error(err)
	s.Contains(err.Error(), "fleet offline")
}
