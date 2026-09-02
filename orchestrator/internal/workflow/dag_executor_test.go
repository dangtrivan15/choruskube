package workflow

import (
	"context"
	"fmt"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/mock"
	"github.com/stretchr/testify/suite"
	"go.temporal.io/sdk/testsuite"

	"github.com/dangtrivan15/choruskube/orchestrator/internal/activity"
	"github.com/dangtrivan15/choruskube/orchestrator/internal/state"
)

type DAGExecutorTestSuite struct {
	suite.Suite
	testsuite.WorkflowTestSuite
	env *testsuite.TestWorkflowEnvironment
	// placementDecision backs the single shared CheckNodePlacement stub below (default:
	// allowed); a test overrides the field directly rather than adding a second On()
	// call, because testify matches overlapping On() calls by registration order, not
	// specificity, so a later, more specific one for the same method never fires.
	placementDecision activity.CheckNodePlacementResult
	// placementError makes the shared stub fail transport-wise instead of answering, the
	// other way the gate can end a run.
	placementError error
}

func (s *DAGExecutorTestSuite) SetupTest() {
	s.env = s.NewTestWorkflowEnvironment()
	var a *activity.Activities
	s.env.RegisterActivity(a)

	s.placementDecision = activity.CheckNodePlacementResult{Allowed: true}
	s.placementError = nil
	s.env.OnActivity("CheckNodePlacement", mock.Anything, mock.Anything).
		Return(func(_ context.Context, _ activity.CheckNodePlacementParams) (activity.CheckNodePlacementResult, error) {
			return s.placementDecision, s.placementError
		}).Maybe()
}

func (s *DAGExecutorTestSuite) AfterTest(suiteName, testName string) {
	s.env.AssertExpectations(s.T())
}

func TestDAGExecutorSuite(t *testing.T) {
	suite.Run(t, new(DAGExecutorTestSuite))
}

// TestLinearTwoNodeGraph verifies: A → B (unconditional), both AI nodes.
// In the test suite, ExecuteAINodeFromSnapshot returns nil (synchronous completion).
// The result is empty string, but unconditional edges fire regardless of result.
func (s *DAGExecutorTestSuite) TestLinearTwoNodeGraph() {
	nodeA := uuid.New()
	nodeB := uuid.New()
	execA := uuid.New()
	execB := uuid.New()
	runID := uuid.New()

	snapshot := `{
		"nodes": [
			{"templateNodeId": "` + nodeA.String() + `", "label": "A", "executorType": "ai", "timeoutSeconds": 1800, "isEntrypoint": true},
			{"templateNodeId": "` + nodeB.String() + `", "label": "B", "executorType": "ai", "timeoutSeconds": 1800}
		],
		"edges": [
			{"sourceNodeId": "` + nodeA.String() + `", "targetNodeId": "` + nodeB.String() + `"}
		]
	}`

	s.env.OnActivity("UpdateWorkflowRunStatus", mock.Anything, mock.Anything).Return(nil)
	s.env.OnActivity("GetGraphRuntime", mock.Anything, runID).Return(snapshot, nil)
	s.env.OnActivity("WriteExecutionLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("InitRunLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("AppendRunLog", mock.Anything, mock.Anything).Return(nil).Maybe()

	// Entry node A created
	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == nodeA
	})).Return(execA, nil).Once()

	// Load predecessor inputs for A (no predecessors, returns empty)
	s.env.OnActivity("LoadPredecessorInputs", mock.Anything, mock.Anything).Return(map[string]string{}, nil).Maybe()
	s.env.OnActivity("LoadRequiredInputArtifacts", mock.Anything, mock.Anything).Return(activity.LoadRequiredInputArtifactsResult{}, nil).Maybe()
	s.env.OnActivity("LoadReviewHistoryJSON", mock.Anything, mock.Anything).Return("[]", nil).Maybe()

	// AI node A executes (async completion — returns nil in test, result is zero-valued)
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.TemplateNodeID == nodeA
	})).Return(activity.CallbackResult{}, nil)

	// Node B created after A completes (unconditional edge fires with empty result)
	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == nodeB
	})).Return(execB, nil).Once()

	// AI node B executes
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.TemplateNodeID == nodeB
	})).Return(activity.CallbackResult{}, nil)

	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execA
	})).Return("no_decision", nil).Once()
	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execB
	})).Return("no_decision", nil).Once()

	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.Anything).Return(nil).Maybe()

	s.env.ExecuteWorkflow(DAGExecutorWorkflow, DAGExecutorParams{
		RunID: runID, GraphVersion: 1,
	})

	s.True(s.env.IsWorkflowCompleted())
	s.NoError(s.env.GetWorkflowError())
}

// TestLinearTwoNodeGraph_TaskContextPropagatesToAllNodes verifies that a
// snapshot's taskContext (Task -> Story -> Epic identity, and its openBlockers)
// must reach EVERY node execution's config.json, not just the
// entrypoint — A -> B, both AI nodes.
func (s *DAGExecutorTestSuite) TestLinearTwoNodeGraph_TaskContextPropagatesToAllNodes() {
	nodeA := uuid.New()
	nodeB := uuid.New()
	execA := uuid.New()
	execB := uuid.New()
	runID := uuid.New()
	taskID := uuid.New()
	storyID := uuid.New()
	epicID := uuid.New()
	blockerID := uuid.New()

	snapshot := `{
		"nodes": [
			{"templateNodeId": "` + nodeA.String() + `", "label": "A", "executorType": "ai", "timeoutSeconds": 1800, "isEntrypoint": true},
			{"templateNodeId": "` + nodeB.String() + `", "label": "B", "executorType": "ai", "timeoutSeconds": 1800}
		],
		"edges": [
			{"sourceNodeId": "` + nodeA.String() + `", "targetNodeId": "` + nodeB.String() + `"}
		],
		"taskContext": {
			"taskId": "` + taskID.String() + `",
			"taskTitle": "Wire up task_context",
			"storyId": "` + storyID.String() + `",
			"storyTitle": "Agent identity threading",
			"epicId": "` + epicID.String() + `",
			"epicTitle": "Roadmap-aware agents",
			"openBlockers": [
				{"itemType": "task", "itemId": "` + blockerID.String() + `", "title": "Prerequisite", "status": "in_progress"}
			]
		}
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
	s.env.OnActivity("LoadRequiredInputArtifacts", mock.Anything, mock.Anything).Return(activity.LoadRequiredInputArtifactsResult{}, nil).Maybe()
	s.env.OnActivity("LoadReviewHistoryJSON", mock.Anything, mock.Anything).Return("[]", nil).Maybe()

	taskContextMatches := func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.TaskID == taskID.String() &&
			p.TaskTitle == "Wire up task_context" &&
			p.StoryID == storyID.String() &&
			p.StoryTitle == "Agent identity threading" &&
			p.EpicID == epicID.String() &&
			p.EpicTitle == "Roadmap-aware agents" &&
			len(p.OpenBlockers) == 1 &&
			p.OpenBlockers[0].ItemType == "task" &&
			p.OpenBlockers[0].ItemID == blockerID.String() &&
			p.OpenBlockers[0].Title == "Prerequisite" &&
			p.OpenBlockers[0].Status == "in_progress"
	}

	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.TemplateNodeID == nodeA && taskContextMatches(p)
	})).Return(activity.CallbackResult{}, nil)

	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == nodeB
	})).Return(execB, nil).Once()

	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.TemplateNodeID == nodeB && taskContextMatches(p)
	})).Return(activity.CallbackResult{}, nil)

	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execA
	})).Return("no_decision", nil).Once()
	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execB
	})).Return("no_decision", nil).Once()

	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.Anything).Return(nil).Maybe()

	s.env.ExecuteWorkflow(DAGExecutorWorkflow, DAGExecutorParams{
		RunID: runID, GraphVersion: 1,
	})

	s.True(s.env.IsWorkflowCompleted())
	s.NoError(s.env.GetWorkflowError())
}

// TestFanOutGraph verifies: A → B, A → C (both unconditional, parallel fan-out)
func (s *DAGExecutorTestSuite) TestFanOutGraph() {
	nodeA := uuid.New()
	nodeB := uuid.New()
	nodeC := uuid.New()
	execA := uuid.New()
	execB := uuid.New()
	execC := uuid.New()
	runID := uuid.New()

	snapshot := `{
		"nodes": [
			{"templateNodeId": "` + nodeA.String() + `", "label": "A", "executorType": "ai", "timeoutSeconds": 1800, "isEntrypoint": true},
			{"templateNodeId": "` + nodeB.String() + `", "label": "B", "executorType": "ai", "timeoutSeconds": 1800},
			{"templateNodeId": "` + nodeC.String() + `", "label": "C", "executorType": "ai", "timeoutSeconds": 1800}
		],
		"edges": [
			{"sourceNodeId": "` + nodeA.String() + `", "targetNodeId": "` + nodeB.String() + `"},
			{"sourceNodeId": "` + nodeA.String() + `", "targetNodeId": "` + nodeC.String() + `"}
		]
	}`

	s.env.OnActivity("UpdateWorkflowRunStatus", mock.Anything, mock.Anything).Return(nil)
	s.env.OnActivity("GetGraphRuntime", mock.Anything, runID).Return(snapshot, nil)
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("WriteExecutionLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("LoadPredecessorInputs", mock.Anything, mock.Anything).Return(map[string]string{}, nil).Maybe()
	s.env.OnActivity("LoadRequiredInputArtifacts", mock.Anything, mock.Anything).Return(activity.LoadRequiredInputArtifactsResult{}, nil).Maybe()
	s.env.OnActivity("LoadReviewHistoryJSON", mock.Anything, mock.Anything).Return("[]", nil).Maybe()
	s.env.OnActivity("InitRunLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("AppendRunLog", mock.Anything, mock.Anything).Return(nil).Maybe()

	// Entry node A
	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == nodeA
	})).Return(execA, nil).Once()

	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.TemplateNodeID == nodeA
	})).Return(activity.CallbackResult{}, nil)

	// Both B and C get created (fan-out)
	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == nodeB
	})).Return(execB, nil).Once()
	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == nodeC
	})).Return(execC, nil).Once()

	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.TemplateNodeID == nodeB
	})).Return(activity.CallbackResult{}, nil)
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.TemplateNodeID == nodeC
	})).Return(activity.CallbackResult{}, nil)

	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execA
	})).Return("no_decision", nil).Once()
	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execB
	})).Return("no_decision", nil).Once()
	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execC
	})).Return("no_decision", nil).Once()

	s.env.ExecuteWorkflow(DAGExecutorWorkflow, DAGExecutorParams{
		RunID: runID, GraphVersion: 1,
	})

	s.True(s.env.IsWorkflowCompleted())
	s.NoError(s.env.GetWorkflowError())
}

// TestHumanGateWithSignal verifies: A (human) receives a signal and completes.
func (s *DAGExecutorTestSuite) TestHumanGateWithSignal() {
	nodeA := uuid.New()
	execA := uuid.New()
	runID := uuid.New()

	snapshot := `{
		"nodes": [
			{"templateNodeId": "` + nodeA.String() + `", "label": "Review", "executorType": "human", "timeoutSeconds": 3600, "isEntrypoint": true}
		],
		"edges": []
	}`

	s.env.OnActivity("UpdateWorkflowRunStatus", mock.Anything, mock.Anything).Return(nil)
	s.env.OnActivity("GetGraphRuntime", mock.Anything, runID).Return(snapshot, nil)
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("WriteExecutionLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("InitRunLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("AppendRunLog", mock.Anything, mock.Anything).Return(nil).Maybe()

	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == nodeA
	})).Return(execA, nil).Once()

	s.env.OnActivity("SetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.SetNodeDecisionParams) bool {
		return p.NodeExecutionID == execA && p.Decision == "approved"
	})).Return(nil).Once()

	// Send human decision signal after workflow starts
	s.env.RegisterDelayedCallback(func() {
		s.env.SignalWorkflow(SignalHumanDecisionPrefix+execA.String(), HumanDecisionSignal{
			NodeExecutionID: execA.String(),
			Decision:        "approved",
			Feedback:        "Looks good",
		})
	}, 0)

	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execA
	})).Return("no_decision", nil).Once()

	s.env.ExecuteWorkflow(DAGExecutorWorkflow, DAGExecutorParams{
		RunID: runID, GraphVersion: 1,
	})

	s.True(s.env.IsWorkflowCompleted())
	s.NoError(s.env.GetWorkflowError())
}

// TestGraphWithLoopBackEdge verifies the happy path through a graph with back-edges.
// Graph: A (human) → B (AI) → C (human) --approved→ D (AI)
//
//	--rejected→ B (back-edge)
//
// Signals A (approved), then C (approved). B runs once, D completes the workflow.
// This is the exact shape that exposed the FindReadyNodes bug: when B first activates,
// C (its back-edge predecessor) hasn't been activated yet — it must not block B.
func (s *DAGExecutorTestSuite) TestGraphWithLoopBackEdge() {
	nodeA := uuid.New()
	nodeB := uuid.New()
	nodeC := uuid.New()
	nodeD := uuid.New()
	execA := uuid.New()
	execB := uuid.New()
	execC := uuid.New()
	execD := uuid.New()
	runID := uuid.New()

	snapshot := `{
		"nodes": [
			{"templateNodeId": "` + nodeA.String() + `", "label": "Gate A", "executorType": "human", "timeoutSeconds": 3600, "isEntrypoint": true},
			{"templateNodeId": "` + nodeB.String() + `", "label": "AI Draft", "executorType": "ai", "timeoutSeconds": 1800},
			{"templateNodeId": "` + nodeC.String() + `", "label": "Review", "executorType": "human", "timeoutSeconds": 3600},
			{"templateNodeId": "` + nodeD.String() + `", "label": "Final", "executorType": "ai", "timeoutSeconds": 1800}
		],
		"edges": [
			{"sourceNodeId": "` + nodeA.String() + `", "targetNodeId": "` + nodeB.String() + `"},
			{"sourceNodeId": "` + nodeB.String() + `", "targetNodeId": "` + nodeC.String() + `"},
			{"sourceNodeId": "` + nodeC.String() + `", "targetNodeId": "` + nodeD.String() + `", "condition": "approved"},
			{"sourceNodeId": "` + nodeC.String() + `", "targetNodeId": "` + nodeB.String() + `", "condition": "rejected"}
		]
	}`

	s.env.OnActivity("UpdateWorkflowRunStatus", mock.Anything, mock.Anything).Return(nil)
	s.env.OnActivity("GetGraphRuntime", mock.Anything, runID).Return(snapshot, nil)
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("WriteExecutionLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("LoadPredecessorInputs", mock.Anything, mock.Anything).Return(map[string]string{}, nil).Maybe()
	s.env.OnActivity("LoadRequiredInputArtifacts", mock.Anything, mock.Anything).Return(activity.LoadRequiredInputArtifactsResult{}, nil).Maybe()
	s.env.OnActivity("LoadReviewHistoryJSON", mock.Anything, mock.Anything).Return("[]", nil).Maybe()
	s.env.OnActivity("InitRunLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("AppendRunLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("SetNodeDecision", mock.Anything, mock.Anything).Return(nil).Maybe()

	// CreateNodeExecution — each node created exactly once
	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == nodeA
	})).Return(execA, nil).Once()
	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == nodeB
	})).Return(execB, nil).Once()
	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == nodeC
	})).Return(execC, nil).Once()
	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == nodeD
	})).Return(execD, nil).Once()

	// AI nodes execute exactly once each
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.TemplateNodeID == nodeB
	})).Return(activity.CallbackResult{}, nil).Once()
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.TemplateNodeID == nodeD
	})).Return(activity.CallbackResult{}, nil).Once()

	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execA
	})).Return("no_decision", nil).Once()
	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execB
	})).Return("no_decision", nil).Once()
	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execC
	})).Return("approved", nil).Once()
	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execD
	})).Return("no_decision", nil).Once()

	// Signal A: approved → activates B
	s.env.RegisterDelayedCallback(func() {
		s.env.SignalWorkflow(SignalHumanDecisionPrefix+execA.String(), HumanDecisionSignal{
			NodeExecutionID: execA.String(),
			Decision:        "approved",
			Feedback:        "Go ahead",
		})
	}, 0)

	// Signal C: approved → activates D (not the back-edge to B)
	s.env.RegisterDelayedCallback(func() {
		s.env.SignalWorkflow(SignalHumanDecisionPrefix+execC.String(), HumanDecisionSignal{
			NodeExecutionID: execC.String(),
			Decision:        "approved",
			Feedback:        "Looks good",
		})
	}, 0)

	s.env.ExecuteWorkflow(DAGExecutorWorkflow, DAGExecutorParams{
		RunID: runID, GraphVersion: 1,
	})

	s.True(s.env.IsWorkflowCompleted())
	s.NoError(s.env.GetWorkflowError())
}

// TestHumanGateSignal_AttachmentRefs verifies that a HumanDecisionSignal with
// AttachmentRefs causes UpdateNodeExecutionStatus to receive matching ArtifactRefs.
func (s *DAGExecutorTestSuite) TestHumanGateSignal_AttachmentRefs() {
	nodeA := uuid.New()
	execA := uuid.New()
	runID := uuid.New()

	snapshot := `{
		"nodes": [
			{"templateNodeId": "` + nodeA.String() + `", "label": "Gate", "executorType": "human", "timeoutSeconds": 3600, "isEntrypoint": true}
		],
		"edges": []
	}`

	attachmentRefs := `{"screenshot.png":"orgs/myorg/runs/abc/gate.png"}`

	s.env.OnActivity("UpdateWorkflowRunStatus", mock.Anything, mock.Anything).Return(nil)
	s.env.OnActivity("GetGraphRuntime", mock.Anything, runID).Return(snapshot, nil)
	s.env.OnActivity("WriteExecutionLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("InitRunLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("AppendRunLog", mock.Anything, mock.Anything).Return(nil).Maybe()

	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == nodeA
	})).Return(execA, nil).Once()

	s.env.OnActivity("SetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.SetNodeDecisionParams) bool {
		return p.NodeExecutionID == execA && p.Decision == "approved"
	})).Return(nil).Once()

	// UpdateNodeExecutionStatus for the "completed" call must receive matching ArtifactRefs
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.MatchedBy(func(p activity.UpdateNodeExecStatusParams) bool {
		return p.NodeExecutionID == execA &&
			p.Status == "completed" &&
			p.ArtifactRefs != nil &&
			*p.ArtifactRefs == attachmentRefs
	})).Return(nil).Once()
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.Anything).Return(nil).Maybe()

	s.env.RegisterDelayedCallback(func() {
		s.env.SignalWorkflow(SignalHumanDecisionPrefix+execA.String(), HumanDecisionSignal{
			NodeExecutionID: execA.String(),
			Decision:        "approved",
			Feedback:        "Looks good",
			AttachmentRefs:  attachmentRefs,
		})
	}, 0)

	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execA
	})).Return("no_decision", nil).Once()

	s.env.ExecuteWorkflow(DAGExecutorWorkflow, DAGExecutorParams{
		RunID: runID, GraphVersion: 1,
	})

	s.True(s.env.IsWorkflowCompleted())
	s.NoError(s.env.GetWorkflowError())
}

// TestHumanGateSignal_EmptyAttachmentRefs verifies that an empty AttachmentRefs in the
// signal causes "{}" to be used as the artifactRefs on the nodeCompletion (and thus
// the ArtifactRefs passed to UpdateNodeExecutionStatus equals "{}").
func (s *DAGExecutorTestSuite) TestHumanGateSignal_EmptyAttachmentRefs() {
	nodeA := uuid.New()
	execA := uuid.New()
	runID := uuid.New()

	snapshot := `{
		"nodes": [
			{"templateNodeId": "` + nodeA.String() + `", "label": "Gate", "executorType": "human", "timeoutSeconds": 3600, "isEntrypoint": true}
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

	s.env.OnActivity("SetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.SetNodeDecisionParams) bool {
		return p.NodeExecutionID == execA && p.Decision == "approved"
	})).Return(nil).Once()

	// UpdateNodeExecutionStatus for "completed" must receive ArtifactRefs = "{}"
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.MatchedBy(func(p activity.UpdateNodeExecStatusParams) bool {
		return p.NodeExecutionID == execA &&
			p.Status == "completed" &&
			p.ArtifactRefs != nil &&
			*p.ArtifactRefs == "{}"
	})).Return(nil).Once()
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.Anything).Return(nil).Maybe()

	// Signal with empty AttachmentRefs
	s.env.RegisterDelayedCallback(func() {
		s.env.SignalWorkflow(SignalHumanDecisionPrefix+execA.String(), HumanDecisionSignal{
			NodeExecutionID: execA.String(),
			Decision:        "approved",
			Feedback:        "OK",
			AttachmentRefs:  "", // empty → should default to "{}"
		})
	}, 0)

	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execA
	})).Return("no_decision", nil).Once()

	s.env.ExecuteWorkflow(DAGExecutorWorkflow, DAGExecutorParams{
		RunID: runID, GraphVersion: 1,
	})

	s.True(s.env.IsWorkflowCompleted())
	s.NoError(s.env.GetWorkflowError())
}

// TestHumanGateTerminalDecisionCompletesRun verifies the "roadmap_feature_creator
// removal" scenario: a human gate node has only a "rejected" conditional outgoing
// edge, plus "terminal_decisions": ["approved"] in its snapshot configOverrides.
// When the human approves, EvaluateEdges must treat "approved" as a legitimate
// end of this run branch (no matching edge, but a declared terminal decision) —
// not as a routing error. The downstream node must never be created, and the
// workflow (and run) must finish as "completed", not "failed".
func (s *DAGExecutorTestSuite) TestHumanGateTerminalDecisionCompletesRun() {
	nodeA := uuid.New()
	nodeB := uuid.New()
	execA := uuid.New()
	runID := uuid.New()

	snapshot := `{
		"nodes": [
			{"templateNodeId": "` + nodeA.String() + `", "label": "roadmap_human_gate", "executorType": "human", "timeoutSeconds": 3600, "isEntrypoint": true, "configOverrides": {"terminal_decisions": ["approved"]}},
			{"templateNodeId": "` + nodeB.String() + `", "label": "roadmap_feature_creator", "executorType": "ai", "timeoutSeconds": 1800}
		],
		"edges": [
			{"sourceNodeId": "` + nodeA.String() + `", "targetNodeId": "` + nodeB.String() + `", "condition": "rejected"}
		]
	}`

	// Assert the final run status is "completed" — registered before the general
	// catch-all below so it matches the terminal "completed" call specifically.
	s.env.OnActivity("UpdateWorkflowRunStatus", mock.Anything, mock.MatchedBy(func(p activity.UpdateRunStatusParams) bool {
		return p.RunID == runID && p.Status == "completed"
	})).Return(nil).Once()
	s.env.OnActivity("UpdateWorkflowRunStatus", mock.Anything, mock.Anything).Return(nil)

	s.env.OnActivity("GetGraphRuntime", mock.Anything, runID).Return(snapshot, nil)
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("WriteExecutionLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("InitRunLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("AppendRunLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("SetTraversedEdges", mock.Anything, mock.Anything).Return(nil).Maybe()

	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == nodeA
	})).Return(execA, nil).Once()

	s.env.OnActivity("SetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.SetNodeDecisionParams) bool {
		return p.NodeExecutionID == execA && p.Decision == "approved"
	})).Return(nil).Once()

	// Human approves the gate — with only a "rejected" conditional edge, this
	// result would previously fail EvaluateEdges outright.
	s.env.RegisterDelayedCallback(func() {
		s.env.SignalWorkflow(SignalHumanDecisionPrefix+execA.String(), HumanDecisionSignal{
			NodeExecutionID: execA.String(),
			Decision:        "approved",
			Feedback:        "Looks good",
		})
	}, 0)

	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execA
	})).Return("approved", nil).Once()

	s.env.ExecuteWorkflow(DAGExecutorWorkflow, DAGExecutorParams{
		RunID: runID, GraphVersion: 1,
	})

	s.True(s.env.IsWorkflowCompleted())
	s.NoError(s.env.GetWorkflowError())
}

// TestRunInputArtifactRefs_PassedToAINode verifies that when RunInputArtifactRefs is
// set on DAGExecutorParams, ExecuteAINodeFromSnapshot is called with InputArtifacts
// containing "run_input/" prefixed keys derived from the JSON.
func (s *DAGExecutorTestSuite) TestRunInputArtifactRefs_PassedToAINode() {
	nodeA := uuid.New()
	execA := uuid.New()
	runID := uuid.New()

	snapshot := `{
		"nodes": [
			{"templateNodeId": "` + nodeA.String() + `", "label": "AI", "executorType": "ai", "timeoutSeconds": 1800, "isEntrypoint": true}
		],
		"edges": []
	}`

	runInputJSON := `{"design.png":"orgs/myorg/runs/abc/design.png","spec.md":"orgs/myorg/runs/abc/spec.md"}`

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

	// AI node must receive InputArtifacts with "run_input/" prefixed keys
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		if p.TemplateNodeID != nodeA {
			return false
		}
		designPath, hasDesign := p.InputArtifacts["run_input/design.png"]
		specPath, hasSpec := p.InputArtifacts["run_input/spec.md"]
		return hasDesign && designPath == "orgs/myorg/runs/abc/design.png" &&
			hasSpec && specPath == "orgs/myorg/runs/abc/spec.md"
	})).Return(activity.CallbackResult{}, nil).Once()

	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execA
	})).Return("no_decision", nil).Once()

	s.env.ExecuteWorkflow(DAGExecutorWorkflow, DAGExecutorParams{
		RunID:                runID,
		GraphVersion:         1,
		RunInputArtifactRefs: runInputJSON,
	})

	s.True(s.env.IsWorkflowCompleted())
	s.NoError(s.env.GetWorkflowError())
}

// TestRunInputArtifactRefs_EmptyOrAbsent_NoInputArtifacts verifies that when
// RunInputArtifactRefs is empty (or "{}"), ExecuteAINodeFromSnapshot is called with
// an empty InputArtifacts map (not nil, and no "run_input/" keys).
func (s *DAGExecutorTestSuite) TestRunInputArtifactRefs_EmptyOrAbsent_NoInputArtifacts() {
	nodeA := uuid.New()
	execA := uuid.New()
	runID := uuid.New()

	snapshot := `{
		"nodes": [
			{"templateNodeId": "` + nodeA.String() + `", "label": "AI", "executorType": "ai", "timeoutSeconds": 1800, "isEntrypoint": true}
		],
		"edges": []
	}`

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

	// AI node must receive an empty InputArtifacts map (no run_input/ keys)
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		if p.TemplateNodeID != nodeA {
			return false
		}
		for key := range p.InputArtifacts {
			if len(key) >= 10 && key[:10] == "run_input/" {
				return false // must not have any run_input/ keys
			}
		}
		return true
	})).Return(activity.CallbackResult{}, nil).Once()

	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execA
	})).Return("no_decision", nil).Once()

	// Test with empty RunInputArtifactRefs
	s.env.ExecuteWorkflow(DAGExecutorWorkflow, DAGExecutorParams{
		RunID:                runID,
		GraphVersion:         1,
		RunInputArtifactRefs: "", // empty → no run-level inputs
	})

	s.True(s.env.IsWorkflowCompleted())
	s.NoError(s.env.GetWorkflowError())
}

// TestNeedsPR_ExtractedFromConfigOverrides verifies that a template node's
// configOverrides.needs_pr == "true" is threaded through to
// ExecuteAINodeFromSnapshotParams.NeedsPR (mirroring how needs_branch feeds
// WorkingBranch), and that a node with no needs_pr override gets NeedsPR == false.
func (s *DAGExecutorTestSuite) TestNeedsPR_ExtractedFromConfigOverrides() {
	nodeA := uuid.New()
	nodeB := uuid.New()
	execA := uuid.New()
	execB := uuid.New()
	runID := uuid.New()

	snapshot := `{
		"nodes": [
			{"templateNodeId": "` + nodeA.String() + `", "label": "implement", "executorType": "ai", "timeoutSeconds": 1800, "isEntrypoint": true, "configOverrides": {"needs_pr": "true"}},
			{"templateNodeId": "` + nodeB.String() + `", "label": "spec_review", "executorType": "ai", "timeoutSeconds": 1800}
		],
		"edges": [
			{"sourceNodeId": "` + nodeA.String() + `", "targetNodeId": "` + nodeB.String() + `"}
		]
	}`

	s.env.OnActivity("UpdateWorkflowRunStatus", mock.Anything, mock.Anything).Return(nil)
	s.env.OnActivity("GetGraphRuntime", mock.Anything, runID).Return(snapshot, nil)
	s.env.OnActivity("WriteExecutionLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("InitRunLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("AppendRunLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("LoadPredecessorInputs", mock.Anything, mock.Anything).Return(map[string]string{}, nil).Maybe()
	s.env.OnActivity("LoadReviewHistoryJSON", mock.Anything, mock.Anything).Return("[]", nil).Maybe()

	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == nodeA
	})).Return(execA, nil).Once()

	// Node A has needs_pr: "true" in its config_overrides → NeedsPR must be true.
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.TemplateNodeID == nodeA && p.NeedsPR == true
	})).Return(activity.CallbackResult{}, nil).Once()

	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == nodeB
	})).Return(execB, nil).Once()

	// Node B has no needs_pr override → NeedsPR must be false.
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.TemplateNodeID == nodeB && p.NeedsPR == false
	})).Return(activity.CallbackResult{}, nil).Once()

	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execA
	})).Return("no_decision", nil).Once()
	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execB
	})).Return("no_decision", nil).Once()

	s.env.ExecuteWorkflow(DAGExecutorWorkflow, DAGExecutorParams{
		RunID: runID, GraphVersion: 1,
	})

	s.True(s.env.IsWorkflowCompleted())
	s.NoError(s.env.GetWorkflowError())
}

// TestTimeoutCallsDeleteAgentJob verifies that when a node's activity returns an error
// (simulating a timeout), the workflow calls DeleteAgentJob for the node's execution.
func (s *DAGExecutorTestSuite) TestTimeoutCallsDeleteAgentJob() {
	nodeA := uuid.New()
	execA := uuid.New()
	runID := uuid.New()

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
	s.env.OnActivity("FetchPodLogs", mock.Anything, mock.MatchedBy(func(p activity.FetchPodLogsParams) bool {
		return p.RunID == runID
	})).Return("", nil).Once()

	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == nodeA
	})).Return(execA, nil).Once()

	s.env.OnActivity("LoadPredecessorInputs", mock.Anything, mock.Anything).Return(map[string]string{}, nil).Maybe()
	s.env.OnActivity("LoadRequiredInputArtifacts", mock.Anything, mock.Anything).Return(activity.LoadRequiredInputArtifactsResult{}, nil).Maybe()
	s.env.OnActivity("LoadReviewHistoryJSON", mock.Anything, mock.Anything).Return("[]", nil).Maybe()

	// AI node A fails (simulates timeout/error)
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.TemplateNodeID == nodeA
	})).Return(activity.CallbackResult{}, fmt.Errorf("activity timeout"))

	// UpdateNodeExecutionStatus must be called with failed
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.MatchedBy(func(p activity.UpdateNodeExecStatusParams) bool {
		return p.NodeExecutionID == execA && p.Status == "failed"
	})).Return(nil).Once()

	// DeleteAgentJob must be called after node timeout
	s.env.OnActivity("DeleteAgentJob", mock.Anything, mock.MatchedBy(func(p activity.DeleteAgentJobParams) bool {
		return p.RunID == runID && p.NodeExecutionID == execA
	})).Return(nil).Once()

	s.env.ExecuteWorkflow(DAGExecutorWorkflow, DAGExecutorParams{
		RunID: runID, GraphVersion: 1,
	})

	s.True(s.env.IsWorkflowCompleted())
}

// TestPauseDeletesRunningJobs verifies that when a pause signal is received,
// DeleteAgentJob is called for running non-human nodes.
//
// Design: the AI node's activity is given a 1-second virtual duration (.After)
// so that it is still in-flight (tracker.status == "running") when the pause
// signal fires at 100 ms. After the pause cleanup, a resume signal at 200 ms
// unblocks the workflow, which then waits for the activity to complete
// normally at 1 s and exits cleanly — keeping the test focused solely on the
// pause path without conflating it with the cancel/Step-5 cleanup path.
func (s *DAGExecutorTestSuite) TestPauseDeletesRunningJobs() {
	nodeA := uuid.New()
	execA := uuid.New()
	runID := uuid.New()

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
	s.env.OnActivity("FetchPodLogs", mock.Anything, mock.Anything).Return("", nil).Maybe()

	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == nodeA
	})).Return(execA, nil).Once()

	s.env.OnActivity("LoadPredecessorInputs", mock.Anything, mock.Anything).Return(map[string]string{}, nil).Maybe()
	s.env.OnActivity("LoadRequiredInputArtifacts", mock.Anything, mock.Anything).Return(activity.LoadRequiredInputArtifactsResult{}, nil).Maybe()
	s.env.OnActivity("LoadReviewHistoryJSON", mock.Anything, mock.Anything).Return("[]", nil).Maybe()

	// AI node A runs — delayed 1 s so the pause signal fires while the activity is
	// still in-flight (tracker.status == "running"). Without .After(), the activity
	// returns synchronously and the node is "completed" before the pause signal is
	// delivered, making the pause cleanup loop a no-op.
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.TemplateNodeID == nodeA
	})).After(time.Second).Return(activity.CallbackResult{}, nil)

	// Send pause signal after node starts but before activity completes
	s.env.RegisterDelayedCallback(func() {
		s.env.SignalWorkflow("pause", nil)
	}, time.Millisecond*100)

	// Resume after pause: unblocks the workflow so it can wait for the activity
	// to finish normally at 1 s and reach IsWorkflowCompleted().
	s.env.RegisterDelayedCallback(func() {
		s.env.SignalWorkflow("resume", nil)
	}, time.Millisecond*200)

	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.Anything).Return(nil).Maybe()

	// Decision for node A after it completes (required to evaluate outgoing edges).
	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execA
	})).Return("no_decision", nil).Once()

	// DeleteAgentJob MUST be called exactly once: from the pause cleanup loop
	// (dag_executor.go lines 182–196). .Once() (not .Maybe()) enforces this so
	// the test fails if the call is never made.
	s.env.OnActivity("DeleteAgentJob", mock.Anything, mock.MatchedBy(func(p activity.DeleteAgentJobParams) bool {
		return p.RunID == runID && p.NodeExecutionID == execA
	})).Return(nil).Once()

	s.env.ExecuteWorkflow(DAGExecutorWorkflow, DAGExecutorParams{
		RunID: runID, GraphVersion: 1,
	})

	s.True(s.env.IsWorkflowCompleted())
	s.NoError(s.env.GetWorkflowError())
}

// TestPauseStampsNodeAsPaused verifies that when a pause signal arrives while a
// non-human AI node is running, the orchestrator:
//
//	(a) calls UpdateNodeExecutionStatus("paused", execA) before deleting the K8s job,
//	(b) records execA in the pauseInterrupted map,
//	(c) on the subsequent heartbeat-timeout completion, invalidates execA and creates a
//	    fresh "pending" execution (iteration=2) — so the node restarts after resume
//	    with no manual Retry click required, and
//	(d) does NOT call UpdateNodeExecutionStatus("failed", execA) — the normal failure
//	    path is bypassed entirely when the re-queue succeeds (enforced by the absence
//	    of a matching mock: any unexpected call to that activity will error the test).
func (s *DAGExecutorTestSuite) TestPauseStampsNodeAsPaused() {
	nodeA := uuid.New()
	execA := uuid.New()
	execA2 := uuid.New()
	runID := uuid.New()

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
	s.env.OnActivity("FetchPodLogs", mock.Anything, mock.Anything).Return("", nil).Maybe()
	s.env.OnActivity("LoadPredecessorInputs", mock.Anything, mock.Anything).Return(map[string]string{}, nil).Maybe()
	s.env.OnActivity("LoadRequiredInputArtifacts", mock.Anything, mock.Anything).Return(activity.LoadRequiredInputArtifactsResult{}, nil).Maybe()
	s.env.OnActivity("LoadReviewHistoryJSON", mock.Anything, mock.Anything).Return("[]", nil).Maybe()

	// Entry node A created (iteration 1)
	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == nodeA && p.Iteration == 1
	})).Return(execA, nil).Once()

	// Node A re-queued as iteration 2 after pause-resume
	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == nodeA && p.Iteration == 2
	})).Return(execA2, nil).Once()

	// First execution: delayed so the pause signal fires while the activity is
	// still in-flight, then returns an error to simulate the heartbeat timeout.
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.NodeExecutionID == execA
	})).After(time.Second).Return(activity.CallbackResult{}, fmt.Errorf("heartbeat timeout"))

	// Second execution (iteration 2): succeeds
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.NodeExecutionID == execA2
	})).Return(activity.CallbackResult{}, nil)

	// UpdateNodeExecutionStatus: "paused" must be called exactly once for execA
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.MatchedBy(func(p activity.UpdateNodeExecStatusParams) bool {
		return p.NodeExecutionID == execA && p.Status == "paused"
	})).Return(nil).Once()

	// UpdateNodeExecutionStatus: "invalidated" for old execA (re-queue path)
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.MatchedBy(func(p activity.UpdateNodeExecStatusParams) bool {
		return p.NodeExecutionID == execA && p.Status == "invalidated"
	})).Return(nil).Once()

	// UpdateNodeExecutionStatus: "completed" for execA2 (second execution succeeds)
	// No catchall is registered: any additional UpdateNodeExecutionStatus call (e.g.
	// "failed" for execA) would produce an activity error, causing the test to fail.
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.MatchedBy(func(p activity.UpdateNodeExecStatusParams) bool {
		return p.NodeExecutionID == execA2 && p.Status == "completed"
	})).Return(nil).Once()

	// DeleteAgentJob must be called for execA from the pause cleanup loop
	s.env.OnActivity("DeleteAgentJob", mock.Anything, mock.MatchedBy(func(p activity.DeleteAgentJobParams) bool {
		return p.RunID == runID && p.NodeExecutionID == execA
	})).Return(nil).Once()

	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execA2
	})).Return("no_decision", nil).Once()

	// Pause while node A is in-flight (100 ms), then resume (200 ms)
	s.env.RegisterDelayedCallback(func() {
		s.env.SignalWorkflow(SignalPause, nil)
	}, time.Millisecond*100)
	s.env.RegisterDelayedCallback(func() {
		s.env.SignalWorkflow(SignalResume, nil)
	}, time.Millisecond*200)

	s.env.ExecuteWorkflow(DAGExecutorWorkflow, DAGExecutorParams{
		RunID: runID, GraphVersion: 1,
	})

	s.True(s.env.IsWorkflowCompleted())
	s.NoError(s.env.GetWorkflowError())
}

// TestTwoNodeGraphOnlyBPaused verifies that in a two-node graph (A → B) where A has
// already completed and only B is running when the pause signal arrives:
//
//	(a) only B is stamped "paused" — A's execID must NOT receive an "invalidated"
//	    UpdateNodeExecutionStatus call or be added to pauseInterrupted,
//	(b) B is re-queued as iteration=2 after the heartbeat-timeout completion, and
//	(c) the workflow eventually completes cleanly.
func (s *DAGExecutorTestSuite) TestTwoNodeGraphOnlyBPaused() {
	nodeA := uuid.New()
	nodeB := uuid.New()
	execA := uuid.New()
	execB1 := uuid.New()
	execB2 := uuid.New()
	runID := uuid.New()

	snapshot := `{
		"nodes": [
			{"templateNodeId": "` + nodeA.String() + `", "label": "A", "executorType": "ai", "timeoutSeconds": 1800, "isEntrypoint": true},
			{"templateNodeId": "` + nodeB.String() + `", "label": "B", "executorType": "ai", "timeoutSeconds": 1800}
		],
		"edges": [
			{"sourceNodeId": "` + nodeA.String() + `", "targetNodeId": "` + nodeB.String() + `"}
		]
	}`

	s.env.OnActivity("UpdateWorkflowRunStatus", mock.Anything, mock.Anything).Return(nil)
	s.env.OnActivity("GetGraphRuntime", mock.Anything, runID).Return(snapshot, nil)
	s.env.OnActivity("WriteExecutionLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("InitRunLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("AppendRunLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("FetchPodLogs", mock.Anything, mock.Anything).Return("", nil).Maybe()
	s.env.OnActivity("LoadPredecessorInputs", mock.Anything, mock.Anything).Return(map[string]string{}, nil).Maybe()
	s.env.OnActivity("LoadRequiredInputArtifacts", mock.Anything, mock.Anything).Return(activity.LoadRequiredInputArtifactsResult{}, nil).Maybe()
	s.env.OnActivity("LoadReviewHistoryJSON", mock.Anything, mock.Anything).Return("[]", nil).Maybe()
	// SetTraversedEdges must be mocked so it returns immediately when A completes.
	// Without this mock the test framework calls the real activity which panics
	// (nil *Activities receiver), causing Temporal to schedule a 1 s retry timer.
	// That retry timer fires after the 100 ms pause/resume window, pushing B's
	// creation past the pause signal — making B "pending" (not "running") when the
	// stamp loop runs, so the test expectations are never satisfied.
	s.env.OnActivity("SetTraversedEdges", mock.Anything, mock.Anything).Return(nil).Maybe()

	// Node A created (entry node)
	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == nodeA
	})).Return(execA, nil).Once()

	// Node B created (iteration 1, triggered by A completing)
	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == nodeB && p.Iteration == 1
	})).Return(execB1, nil).Once()

	// Node B re-queued as iteration 2 after pause-resume
	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == nodeB && p.Iteration == 2
	})).Return(execB2, nil).Once()

	// Node A: completes immediately so that B can start before the pause signal fires
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.TemplateNodeID == nodeA
	})).Return(activity.CallbackResult{}, nil)

	// Node B (iteration 1): delayed so it is still in-flight when the pause signal fires
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.NodeExecutionID == execB1
	})).After(time.Second).Return(activity.CallbackResult{}, fmt.Errorf("heartbeat timeout"))

	// Node B (iteration 2): succeeds
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.NodeExecutionID == execB2
	})).Return(activity.CallbackResult{}, nil)

	// A completes successfully before the pause — "completed" is called by the workflow
	// for all nodes (AI nodes also get it, though the callback set it first via HTTP).
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.MatchedBy(func(p activity.UpdateNodeExecStatusParams) bool {
		return p.NodeExecutionID == execA && p.Status == "completed"
	})).Return(nil).Once()

	// Only B must be stamped "paused" — not A (which was already "completed")
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.MatchedBy(func(p activity.UpdateNodeExecStatusParams) bool {
		return p.NodeExecutionID == execB1 && p.Status == "paused"
	})).Return(nil).Once()

	// execB1 must be invalidated (re-queue path), not failed
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.MatchedBy(func(p activity.UpdateNodeExecStatusParams) bool {
		return p.NodeExecutionID == execB1 && p.Status == "invalidated"
	})).Return(nil).Once()

	// execB2 succeeds after re-queue — "completed" is called
	// No catchall: any unexpected UpdateNodeExecutionStatus call (e.g. "failed" for execB1)
	// would produce an activity error, causing the test to fail — enforcing the re-queue invariant.
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.MatchedBy(func(p activity.UpdateNodeExecStatusParams) bool {
		return p.NodeExecutionID == execB2 && p.Status == "completed"
	})).Return(nil).Once()

	s.env.OnActivity("DeleteAgentJob", mock.Anything, mock.MatchedBy(func(p activity.DeleteAgentJobParams) bool {
		return p.RunID == runID && p.NodeExecutionID == execB1
	})).Return(nil).Once()

	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execA
	})).Return("no_decision", nil).Once()
	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execB2
	})).Return("no_decision", nil).Once()

	// Pause after B starts (A has already completed) and resume shortly after
	s.env.RegisterDelayedCallback(func() {
		s.env.SignalWorkflow(SignalPause, nil)
	}, time.Millisecond*100)
	s.env.RegisterDelayedCallback(func() {
		s.env.SignalWorkflow(SignalResume, nil)
	}, time.Millisecond*200)

	s.env.ExecuteWorkflow(DAGExecutorWorkflow, DAGExecutorParams{
		RunID: runID, GraphVersion: 1,
	})

	s.True(s.env.IsWorkflowCompleted())
	s.NoError(s.env.GetWorkflowError())
}

// TestResumeBeforeHeartbeatTimeout verifies that the pauseInterrupted map persists
// through a resume-before-timeout scenario: if the user resumes the workflow before
// the heartbeat timeout fires, the timeout completion (which arrives after resume) is
// still handled by the re-queue path — not the normal failure path.
func (s *DAGExecutorTestSuite) TestResumeBeforeHeartbeatTimeout() {
	nodeA := uuid.New()
	execA := uuid.New()
	execA2 := uuid.New()
	runID := uuid.New()

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
	s.env.OnActivity("FetchPodLogs", mock.Anything, mock.Anything).Return("", nil).Maybe()
	s.env.OnActivity("LoadPredecessorInputs", mock.Anything, mock.Anything).Return(map[string]string{}, nil).Maybe()
	s.env.OnActivity("LoadRequiredInputArtifacts", mock.Anything, mock.Anything).Return(activity.LoadRequiredInputArtifactsResult{}, nil).Maybe()
	s.env.OnActivity("LoadReviewHistoryJSON", mock.Anything, mock.Anything).Return("[]", nil).Maybe()
	s.env.OnActivity("DeleteAgentJob", mock.Anything, mock.Anything).Return(nil).Maybe()

	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == nodeA && p.Iteration == 1
	})).Return(execA, nil).Once()

	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == nodeA && p.Iteration == 2
	})).Return(execA2, nil).Once()

	// First execution: delayed well past the resume point (500 ms) then returns an
	// error. This simulates a heartbeat timeout that fires after the user has already
	// resumed the workflow. The pauseInterrupted entry must survive the resume and be
	// consumed here — confirming that resume does NOT clear the map.
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.NodeExecutionID == execA
	})).After(500*time.Millisecond).Return(activity.CallbackResult{}, fmt.Errorf("heartbeat timeout"))

	// Second execution succeeds
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.NodeExecutionID == execA2
	})).Return(activity.CallbackResult{}, nil)

	// "paused" — execA must be stamped before the K8s job is deleted
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.MatchedBy(func(p activity.UpdateNodeExecStatusParams) bool {
		return p.NodeExecutionID == execA && p.Status == "paused"
	})).Return(nil).Once()

	// "invalidated" — re-queue path was taken (not "failed")
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.MatchedBy(func(p activity.UpdateNodeExecStatusParams) bool {
		return p.NodeExecutionID == execA && p.Status == "invalidated"
	})).Return(nil).Once()

	// "completed" for execA2 — the re-queued execution succeeds
	// No catchall: any unexpected UpdateNodeExecutionStatus call (e.g. "failed" for execA)
	// will produce an activity error, causing the test to fail — ensuring the re-queue path
	// is taken and the normal failure path is bypassed.
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.MatchedBy(func(p activity.UpdateNodeExecStatusParams) bool {
		return p.NodeExecutionID == execA2 && p.Status == "completed"
	})).Return(nil).Once()

	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execA2
	})).Return("no_decision", nil).Once()

	// Pause at 50 ms, resume at 100 ms (well before the 500 ms timeout)
	s.env.RegisterDelayedCallback(func() {
		s.env.SignalWorkflow(SignalPause, nil)
	}, time.Millisecond*50)
	s.env.RegisterDelayedCallback(func() {
		s.env.SignalWorkflow(SignalResume, nil)
	}, time.Millisecond*100)

	s.env.ExecuteWorkflow(DAGExecutorWorkflow, DAGExecutorParams{
		RunID: runID, GraphVersion: 1,
	})

	s.True(s.env.IsWorkflowCompleted())
	s.NoError(s.env.GetWorkflowError())
}

// TestRateLimitedNodeSleepsThenRequeues verifies that a rate-limited outcome
// invalidates the execution, waits for the reset, and re-queues iteration 2
// carrying the session reference — without the node ever entering the failure path.
func (s *DAGExecutorTestSuite) TestRateLimitedNodeSleepsThenRequeues() {
	nodeA := uuid.New()
	execA := uuid.New()
	execA2 := uuid.New()
	runID := uuid.New()

	// model_first_iteration/model_subsequent_iteration let this test prove reviewPass
	// carries forward across the park (see nodeTracker.reviewPass): a rate-limited
	// park-and-resume is an infra retry, not a review decision, so the resumed
	// iteration must still resolve reviewPass == 1 and pick the first-iteration
	// model — not silently downgrade to the subsequent-iteration one, which is what
	// a regression that reset reviewPass to its Go zero value (0) would cause,
	// since the gating check is `tracker.reviewPass == 1`, not `!= 0`.
	snapshot := `{
		"nodes": [
			{"templateNodeId": "` + nodeA.String() + `", "label": "A", "executorType": "ai", "timeoutSeconds": 1800, "isEntrypoint": true, "configOverrides": {"model_first_iteration": "model-first", "model_subsequent_iteration": "model-subsequent"}}
		],
		"edges": []
	}`

	s.env.OnActivity("UpdateWorkflowRunStatus", mock.Anything, mock.Anything).Return(nil)
	s.env.OnActivity("GetGraphRuntime", mock.Anything, runID).Return(snapshot, nil)
	s.env.OnActivity("WriteExecutionLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("InitRunLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("AppendRunLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("FetchPodLogs", mock.Anything, mock.Anything).Return("", nil).Maybe()
	s.env.OnActivity("LoadPredecessorInputs", mock.Anything, mock.Anything).Return(map[string]string{}, nil).Maybe()
	s.env.OnActivity("LoadRequiredInputArtifacts", mock.Anything, mock.Anything).Return(activity.LoadRequiredInputArtifactsResult{}, nil).Maybe()
	s.env.OnActivity("LoadReviewHistoryJSON", mock.Anything, mock.Anything).Return("[]", nil).Maybe()
	s.env.OnActivity("DeleteAgentJob", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("SetTraversedEdges", mock.Anything, mock.Anything).Return(nil).Maybe()

	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == nodeA && p.Iteration == 1
	})).Return(execA, nil).Once()

	// Iteration 2 must be created after the sleep.
	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == nodeA && p.Iteration == 2
	})).Return(execA2, nil).Once()

	// The execution that hit quota is invalidated, never failed.
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.MatchedBy(func(p activity.UpdateNodeExecStatusParams) bool {
		return p.NodeExecutionID == execA && p.Status == "invalidated"
	})).Return(nil).Once()
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.MatchedBy(func(p activity.UpdateNodeExecStatusParams) bool {
		return p.NodeExecutionID == execA && p.Status == "failed"
	})).Return(nil).Maybe().Run(func(args mock.Arguments) {
		s.Fail("a rate-limited node must never be marked failed")
	})

	// Iteration 1 returns the park outcome; iteration 2 must receive the session.
	resumeAt := s.env.Now().Add(30 * time.Minute)
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.NodeExecutionID == execA
	})).Return(activity.CallbackResult{
		Status:              "rate_limited",
		ResumeAt:            resumeAt,
		SessionID:           "sess-1",
		SessionArtifactPath: "runs/r/e/session/sess-1.jsonl",
	}, nil).Once()

	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.NodeExecutionID == execA2 &&
			p.SessionID == "sess-1" &&
			p.SessionArtifactPath == "runs/r/e/session/sess-1.jsonl" &&
			p.Model == "model-first"
	})).Return(activity.CallbackResult{Status: "completed", Result: "done"}, nil).Once()

	// "completed" for execA2 — the re-queued execution succeeds. Without this
	// mock the DB write for the resumed iteration's completion is an
	// unmocked call: it panics, retries, and the workflow silently falls
	// back to its week-long await-retry path — the resumed iteration never
	// truly finishes even though the top-level workflow assertions below
	// would still pass. This mock is what makes the happy path real.
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.MatchedBy(func(p activity.UpdateNodeExecStatusParams) bool {
		return p.NodeExecutionID == execA2 && p.Status == "completed"
	})).Return(nil).Once()

	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execA2
	})).Return("no_decision", nil).Once()

	s.env.ExecuteWorkflow(DAGExecutorWorkflow, DAGExecutorParams{RunID: runID, GraphVersion: 1})

	s.True(s.env.IsWorkflowCompleted())
	s.NoError(s.env.GetWorkflowError())
	s.env.AssertExpectations(s.T())
}

// TestRateLimitedNodeInvalidateFailureMarksNodeFailed proves the fix for Finding 1: if the
// park-and-resume coroutine's own bookkeeping write (invalidating the parked execution)
// itself fails, the node must end up explicitly "failed" — never silently "completed".
//
// Before this fix, completion.err is nil on the rate-limited path (see the future-await
// goroutine), so simply falling through past a failed invalidate write landed in the
// SUCCESS finalization a few lines below (completion.err != nil evaluates false), marking
// the node "completed" with an empty result. The fix sends a synthetic errored
// nodeCompletion through completionCh instead, routing through the existing, already-tested
// failure path.
func (s *DAGExecutorTestSuite) TestRateLimitedNodeInvalidateFailureMarksNodeFailed() {
	nodeA := uuid.New()
	execA := uuid.New()
	runID := uuid.New()

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
	s.env.OnActivity("FetchPodLogs", mock.Anything, mock.Anything).Return("", nil).Maybe()
	s.env.OnActivity("LoadPredecessorInputs", mock.Anything, mock.Anything).Return(map[string]string{}, nil).Maybe()
	s.env.OnActivity("LoadRequiredInputArtifacts", mock.Anything, mock.Anything).Return(activity.LoadRequiredInputArtifactsResult{}, nil).Maybe()
	s.env.OnActivity("LoadReviewHistoryJSON", mock.Anything, mock.Anything).Return("[]", nil).Maybe()
	s.env.OnActivity("DeleteAgentJob", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("SetTraversedEdges", mock.Anything, mock.Anything).Return(nil).Maybe()

	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == nodeA && p.Iteration == 1
	})).Return(execA, nil).Once()
	// Iteration 2 must never be created — the invalidate write (below) fails before the
	// coroutine ever gets there.
	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == nodeA && p.Iteration == 2
	})).Return(uuid.Nil, fmt.Errorf("must not be called")).Maybe().Run(func(args mock.Arguments) {
		s.Fail("iteration 2 must never be created when the invalidate write itself fails")
	})

	resumeAt := s.env.Now().Add(30 * time.Minute)
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.NodeExecutionID == execA
	})).Return(activity.CallbackResult{
		Status:   "rate_limited",
		ResumeAt: resumeAt,
	}, nil).Once()

	// The invalidate write itself fails — simulating a transient DB/activity error after
	// the quota reset. dbCtx carries RetryPolicy{MaximumAttempts: 3}, so this activity is
	// actually invoked up to 3 times before .Get returns the error to the coroutine;
	// Times(3) (not Once) keeps every attempt answered by the same deliberate error
	// instead of the 2nd/3rd attempts falling through as unmocked calls.
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.MatchedBy(func(p activity.UpdateNodeExecStatusParams) bool {
		return p.NodeExecutionID == execA && p.Status == "invalidated"
	})).Return(fmt.Errorf("db unavailable")).Times(3)

	// The node must end up explicitly failed...
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.MatchedBy(func(p activity.UpdateNodeExecStatusParams) bool {
		return p.NodeExecutionID == execA && p.Status == "failed"
	})).Return(nil).Once()

	// ...and never silently completed.
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.MatchedBy(func(p activity.UpdateNodeExecStatusParams) bool {
		return p.NodeExecutionID == execA && p.Status == "completed"
	})).Return(nil).Maybe().Run(func(args mock.Arguments) {
		s.Fail("a node whose park bookkeeping failed must never be marked completed")
	})

	s.env.ExecuteWorkflow(DAGExecutorWorkflow, DAGExecutorParams{RunID: runID, GraphVersion: 1})

	s.True(s.env.IsWorkflowCompleted())
	s.NoError(s.env.GetWorkflowError())
	s.env.AssertExpectations(s.T())
}

// TestRateLimitedNodeParkDoesNotBlockConcurrentSibling proves the fix for Finding 2: the
// sleep must not block the selector for the whole park, or a sibling node's own completion
// (its DB writes, job cleanup, and edge evaluation) would stall for the entire quota window.
//
// Model: TestFanOutGraph's A → B, A → C parallel shape. B parks on quota for 30 (virtual)
// minutes; C is an independent sibling that finishes on its own a (virtual) minute later.
// The assertion is timing-based: C's "completed" DB write must land well before B's
// post-park "invalidated" write — proof C was never stalled waiting for B's sleep to
// return. Against the pre-fix synchronous implementation this fails hard: the main loop's
// Select call cannot return (and therefore cannot rebuild the selector and pick up C's
// already-pending Send) until the synchronous sleep inside the callback itself returns, so
// C's completion would only be processed AFTER B's entire 30-minute park elapses — see the
// report for the empirical run against the pre-fix code proving this.
func (s *DAGExecutorTestSuite) TestRateLimitedNodeParkDoesNotBlockConcurrentSibling() {
	nodeA := uuid.New()
	nodeB := uuid.New()
	nodeC := uuid.New()
	execA := uuid.New()
	execB := uuid.New()
	execB2 := uuid.New()
	execC := uuid.New()
	runID := uuid.New()

	snapshot := `{
		"nodes": [
			{"templateNodeId": "` + nodeA.String() + `", "label": "A", "executorType": "ai", "timeoutSeconds": 1800, "isEntrypoint": true},
			{"templateNodeId": "` + nodeB.String() + `", "label": "B", "executorType": "ai", "timeoutSeconds": 1800},
			{"templateNodeId": "` + nodeC.String() + `", "label": "C", "executorType": "ai", "timeoutSeconds": 1800}
		],
		"edges": [
			{"sourceNodeId": "` + nodeA.String() + `", "targetNodeId": "` + nodeB.String() + `"},
			{"sourceNodeId": "` + nodeA.String() + `", "targetNodeId": "` + nodeC.String() + `"}
		]
	}`

	s.env.OnActivity("UpdateWorkflowRunStatus", mock.Anything, mock.Anything).Return(nil)
	s.env.OnActivity("GetGraphRuntime", mock.Anything, runID).Return(snapshot, nil)
	s.env.OnActivity("WriteExecutionLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("InitRunLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("AppendRunLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("FetchPodLogs", mock.Anything, mock.Anything).Return("", nil).Maybe()
	s.env.OnActivity("LoadPredecessorInputs", mock.Anything, mock.Anything).Return(map[string]string{}, nil).Maybe()
	s.env.OnActivity("LoadRequiredInputArtifacts", mock.Anything, mock.Anything).Return(activity.LoadRequiredInputArtifactsResult{}, nil).Maybe()
	s.env.OnActivity("LoadReviewHistoryJSON", mock.Anything, mock.Anything).Return("[]", nil).Maybe()
	s.env.OnActivity("DeleteAgentJob", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("SetTraversedEdges", mock.Anything, mock.Anything).Return(nil).Maybe()

	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == nodeA
	})).Return(execA, nil).Once()
	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == nodeB && p.Iteration == 1
	})).Return(execB, nil).Once()
	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == nodeB && p.Iteration == 2
	})).Return(execB2, nil).Once()
	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == nodeC
	})).Return(execC, nil).Once()

	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.NodeExecutionID == execA
	})).Return(activity.CallbackResult{}, nil).Once()

	resumeAt := s.env.Now().Add(30 * time.Minute)
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.NodeExecutionID == execB
	})).Return(activity.CallbackResult{
		Status:   "rate_limited",
		ResumeAt: resumeAt,
	}, nil).Once()
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.NodeExecutionID == execB2
	})).Return(activity.CallbackResult{}, nil).Once()

	// C is an independent sibling: a short (virtual) delay, well under B's 30-minute
	// park, so its completion timestamp is a distinct, meaningfully-earlier point than
	// B's post-park write rather than both happening to land at t=0.
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.NodeExecutionID == execC
	})).After(1*time.Minute).Return(activity.CallbackResult{}, nil).Once()

	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execA
	})).Return("no_decision", nil).Once()
	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execB2
	})).Return("no_decision", nil).Once()
	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execC
	})).Return("no_decision", nil).Once()

	var mu sync.Mutex
	var cCompletedAt, bInvalidatedAt time.Time

	// Specific, timestamp-capturing matchers must be registered before the broad
	// catch-all below so they win the match for these two exact calls.
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.MatchedBy(func(p activity.UpdateNodeExecStatusParams) bool {
		return p.NodeExecutionID == execC && p.Status == "completed"
	})).Run(func(args mock.Arguments) {
		mu.Lock()
		cCompletedAt = s.env.Now()
		mu.Unlock()
	}).Return(nil).Once()

	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.MatchedBy(func(p activity.UpdateNodeExecStatusParams) bool {
		return p.NodeExecutionID == execB && p.Status == "invalidated"
	})).Run(func(args mock.Arguments) {
		mu.Lock()
		bInvalidatedAt = s.env.Now()
		mu.Unlock()
	}).Return(nil).Once()

	// Everything else (A's completion, B2's completion) — no timing assertion needed.
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.Anything).Return(nil).Maybe()

	s.env.ExecuteWorkflow(DAGExecutorWorkflow, DAGExecutorParams{RunID: runID, GraphVersion: 1})

	s.True(s.env.IsWorkflowCompleted())
	s.NoError(s.env.GetWorkflowError())
	s.env.AssertExpectations(s.T())

	mu.Lock()
	defer mu.Unlock()
	s.False(cCompletedAt.IsZero(), "C's completion must have been recorded")
	s.False(bInvalidatedAt.IsZero(), "B's post-park invalidate must have been recorded")
	s.True(cCompletedAt.Before(bInvalidatedAt),
		"C (an independent sibling) must complete before B's park ends — if the sleep "+
			"blocked the selector, C's own completion processing would be stalled until "+
			"after B's entire 30-minute park finishes")
	s.True(cCompletedAt.Before(resumeAt),
		"C must complete well within B's park window, not just barely before B's post-park write")
}

// TestRateLimitedNodeCarriesForceReadyThroughRequeue proves the Minor finding: forceReady
// (like reviewPass, proved in TestRateLimitedNodeSleepsThenRequeues) must survive the
// park-and-resume carry-forward at the rate-limited requeue site specifically. This is a
// THIRD reachable forceReady carry-forward site alongside the two already covered by
// TestSupervisorRoutesPastAnUnrunPredecessor (original activation) and
// TestForceReadyCarriesForwardThroughLateHumanDecisionRetry (late-human-decision retry) —
// neither of those exercises the rate-limited requeue path this task adds.
//
// Graph mirrors TestForceReadyCarriesForwardThroughLateHumanDecisionRetry: implement fans
// out to code_review (which escalates) and test (which fails outright and never completes,
// so final_approval's ordinary predecessor gate stays genuinely, permanently blocked — not
// just delayed). The Supervisor routes to final_approval (force-ready). Unlike that sibling
// test, final_approval is an AI node here, and its first execution rate-limits instead of
// timing out unanswered.
//
// The assertion is structural: final_approval's iteration-2 ExecuteAINodeFromSnapshot call
// can only ever happen if forceReady survived the coroutine's tracker rebuild — test never
// completes, so without forceReady, FindReadyNodes never re-admits final_approval and the
// .Once() mock below simply never fires, caught by AssertExpectations.
func (s *DAGExecutorTestSuite) TestRateLimitedNodeCarriesForceReadyThroughRequeue() {
	implement := uuid.New()
	codeReview := uuid.New()
	test := uuid.New()
	finalApproval := uuid.New()
	supervisor := uuid.New()

	execImplement := uuid.New()
	execCodeReview := uuid.New()
	execTest := uuid.New()
	execFinalApproval1 := uuid.New()
	execFinalApproval2 := uuid.New()
	execSupervisor := uuid.New()

	runID := uuid.New()

	snapshot := `{
		"nodes": [
			{"templateNodeId": "` + implement.String() + `", "label": "implement", "executorType": "ai", "timeoutSeconds": 1800, "isEntrypoint": true},
			{"templateNodeId": "` + codeReview.String() + `", "label": "code_review", "executorType": "ai", "timeoutSeconds": 1800},
			{"templateNodeId": "` + test.String() + `", "label": "test", "executorType": "ai", "timeoutSeconds": 1800},
			{"templateNodeId": "` + finalApproval.String() + `", "label": "final_approval", "executorType": "ai", "timeoutSeconds": 1800},
			{"templateNodeId": "` + supervisor.String() + `", "label": "supervisor", "executorType": "human", "timeoutSeconds": 3600, "configOverrides": {"routing_hub": true}}
		],
		"edges": [
			{"sourceNodeId": "` + implement.String() + `", "targetNodeId": "` + codeReview.String() + `"},
			{"sourceNodeId": "` + implement.String() + `", "targetNodeId": "` + test.String() + `"},
			{"sourceNodeId": "` + test.String() + `", "targetNodeId": "` + finalApproval.String() + `", "condition": "approved"}
		]
	}`

	s.env.OnActivity("UpdateWorkflowRunStatus", mock.Anything, mock.Anything).Return(nil)
	s.env.OnActivity("GetGraphRuntime", mock.Anything, runID).Return(snapshot, nil)
	s.env.OnActivity("WriteExecutionLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("InitRunLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("AppendRunLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("SetTraversedEdges", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("LoadPredecessorInputs", mock.Anything, mock.Anything).Return(map[string]string{}, nil).Maybe()
	s.env.OnActivity("LoadRequiredInputArtifacts", mock.Anything, mock.Anything).Return(activity.LoadRequiredInputArtifactsResult{}, nil).Maybe()
	s.env.OnActivity("LoadReviewHistoryJSON", mock.Anything, mock.Anything).Return("[]", nil).Maybe()
	s.env.OnActivity("FetchPodLogs", mock.Anything, mock.Anything).Return("", nil).Maybe()
	s.env.OnActivity("DeleteAgentJob", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.Anything).Return(nil).Maybe()

	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == implement
	})).Return(execImplement, nil).Once()
	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == codeReview
	})).Return(execCodeReview, nil).Once()
	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == test
	})).Return(execTest, nil).Once()
	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == supervisor
	})).Return(execSupervisor, nil).Once()
	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == finalApproval && p.Iteration == 1
	})).Return(execFinalApproval1, nil).Once()
	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == finalApproval && p.Iteration == 2
	})).Return(execFinalApproval2, nil).Once()

	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.TemplateNodeID == implement
	})).Return(activity.CallbackResult{}, nil).Once()
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.TemplateNodeID == codeReview
	})).Return(activity.CallbackResult{}, nil).Once()
	// test fails outright — permanently, never retried in this test — so the predecessor
	// gate final_approval depends on stays blocked for the rest of the run.
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.TemplateNodeID == test
	})).Return(activity.CallbackResult{}, fmt.Errorf("test environment down")).Once()

	resumeAt := s.env.Now().Add(30 * time.Minute)
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.NodeExecutionID == execFinalApproval1
	})).Return(activity.CallbackResult{
		Status:   "rate_limited",
		ResumeAt: resumeAt,
	}, nil).Once()
	// This call can only happen if forceReady survived the coroutine's tracker rebuild —
	// test (final_approval's ordinary predecessor) never completes in this graph.
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.NodeExecutionID == execFinalApproval2
	})).Return(activity.CallbackResult{}, nil).Once()

	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execImplement
	})).Return("no_decision", nil).Once()
	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execCodeReview
	})).Return("escalate", nil).Once()
	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execSupervisor
	})).Return("route:final_approval", nil).Once()
	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execFinalApproval2
	})).Return("no_decision", nil).Once()
	// execTest and execFinalApproval1 never reach the decision-read step: test fails
	// outright and execFinalApproval1's rate-limited outcome routes through the park
	// branch, not the normal decision-evaluation path.

	s.env.OnActivity("SetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.SetNodeDecisionParams) bool {
		return p.NodeExecutionID == execSupervisor && p.Decision == "route:final_approval"
	})).Return(nil).Once()

	// The Supervisor routes to final_approval as soon as it is paged.
	s.env.RegisterDelayedCallback(func() {
		s.env.SignalWorkflow(SignalHumanDecisionPrefix+execSupervisor.String(), HumanDecisionSignal{
			NodeExecutionID: execSupervisor.String(),
			Decision:        "route:final_approval",
			Feedback:        "Routing straight to final approval",
		})
	}, 0)

	s.env.ExecuteWorkflow(DAGExecutorWorkflow, DAGExecutorParams{
		RunID: runID, GraphVersion: 1,
	})

	s.True(s.env.IsWorkflowCompleted())
	s.NoError(s.env.GetWorkflowError())
	s.env.AssertExpectations(s.T())
}

// TestRateLimitedNodeParkedDuringPauseStillFailsExplicitly covers the fix-round-2 finding:
// a manual pause landing during a park, followed by a bookkeeping failure, used to silently
// misroute into the pre-existing wasPaused recovery — which has no sessionID/
// sessionArtifactPath fields at all — discarding the parked session and re-queuing without
// error instead of failing the node as TestRateLimitedNodeInvalidateFailureMarksNodeFailed
// established.
//
// Sequence: A parks (rate_limited). While it sleeps, a pause signal arrives — the parked
// node's tracker still reads "running" (that's what keeps runningCount correct during the
// park), so the pre-existing pause-stamping loop matches it and adds its execID to
// pauseInterrupted; this window did not exist before the fix-round-1 change moved the sleep
// off the synchronous callback. A resume signal follows so the workflow can proceed
// normally once the park ends. The invalidate write is mocked to fail 3 times (exhausting
// dbCtx's RetryPolicy) — a transient failure — and then to SUCCEED on any further call: this
// shape specifically distinguishes "the fix applied" (no further call ever happens; the node
// simply fails) from "the fix missing" (the wasPaused branch's own independent invalidate
// retry succeeds on that later call, and it silently re-queues without a session — an
// always-failing invalidate would reach "failed" either way and not tell the two cases
// apart).
//
// Assertions: the node ends explicitly "failed", and CreateNodeExecution for iteration 2 is
// never attempted at all — proving no fresh execution (with or without a session reference)
// is silently created.
func (s *DAGExecutorTestSuite) TestRateLimitedNodeParkedDuringPauseStillFailsExplicitly() {
	nodeA := uuid.New()
	execA := uuid.New()
	runID := uuid.New()

	snapshot := `{
		"nodes": [
			{"templateNodeId": "` + nodeA.String() + `", "label": "A", "executorType": "ai", "timeoutSeconds": 1800, "isEntrypoint": true}
		],
		"edges": []
	}`

	s.env.OnActivity("GetGraphRuntime", mock.Anything, runID).Return(snapshot, nil)
	s.env.OnActivity("WriteExecutionLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("InitRunLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("AppendRunLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("FetchPodLogs", mock.Anything, mock.Anything).Return("", nil).Maybe()
	s.env.OnActivity("LoadPredecessorInputs", mock.Anything, mock.Anything).Return(map[string]string{}, nil).Maybe()
	s.env.OnActivity("LoadRequiredInputArtifacts", mock.Anything, mock.Anything).Return(activity.LoadRequiredInputArtifactsResult{}, nil).Maybe()
	s.env.OnActivity("LoadReviewHistoryJSON", mock.Anything, mock.Anything).Return("[]", nil).Maybe()
	s.env.OnActivity("DeleteAgentJob", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("SetTraversedEdges", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("UpdateWorkflowRunStatus", mock.Anything, mock.Anything).Return(nil)

	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == nodeA && p.Iteration == 1
	})).Return(execA, nil).Once()

	// No fresh execution — with or without a session — may ever be created for this node:
	// the bookkeeping failure must end in an explicit failure, never a silent re-queue.
	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == nodeA && p.Iteration == 2
	})).Return(uuid.Nil, fmt.Errorf("must not be called")).Maybe().Run(func(args mock.Arguments) {
		s.Fail("a node whose bookkeeping failed while paused must never be silently re-queued")
	})

	resumeAt := s.env.Now().Add(30 * time.Minute)
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.NodeExecutionID == execA
	})).Return(activity.CallbackResult{
		Status:              "rate_limited",
		ResumeAt:            resumeAt,
		SessionID:           "sess-1",
		SessionArtifactPath: "runs/r/e/session/sess-1.jsonl",
	}, nil).Once()

	// The parked node's own invalidate write fails 3 times (dbCtx's RetryPolicy is
	// MaximumAttempts: 3) — a transient failure. Registered as a SEPARATE, later
	// expectation: any call beyond those 3 succeeds. Under the fix, no such further call
	// ever happens (pauseInterrupted is cleared before the synthetic failure completion
	// is sent, so the wasPaused recovery branch — the only other code that would retry
	// this write — is never reached). This is deliberately not "always fails": an
	// always-failing invalidate reaches "failed" via either code path and would not tell
	// the fixed and unfixed behavior apart.
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.MatchedBy(func(p activity.UpdateNodeExecStatusParams) bool {
		return p.NodeExecutionID == execA && p.Status == "invalidated"
	})).Return(fmt.Errorf("db unavailable")).Times(3)
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.MatchedBy(func(p activity.UpdateNodeExecStatusParams) bool {
		return p.NodeExecutionID == execA && p.Status == "invalidated"
	})).Return(nil).Maybe()

	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.MatchedBy(func(p activity.UpdateNodeExecStatusParams) bool {
		return p.NodeExecutionID == execA && p.Status == "failed"
	})).Return(nil).Once()

	// Everything else (the pause stamp, etc.) — no assertion needed on these.
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.Anything).Return(nil).Maybe()

	// A pause lands while A is parked: its tracker still reads "running" (that is what
	// keeps runningCount correct through the whole park), so the top-of-loop pause-stamp
	// matches it and adds its execID to pauseInterrupted. Resume follows so the run
	// proceeds normally once the park itself ends ~30 (virtual) minutes later.
	s.env.RegisterDelayedCallback(func() {
		s.env.SignalWorkflow(SignalPause, nil)
	}, 50*time.Millisecond)
	s.env.RegisterDelayedCallback(func() {
		s.env.SignalWorkflow(SignalResume, nil)
	}, 100*time.Millisecond)

	s.env.ExecuteWorkflow(DAGExecutorWorkflow, DAGExecutorParams{RunID: runID, GraphVersion: 1})

	s.True(s.env.IsWorkflowCompleted())
	s.NoError(s.env.GetWorkflowError())
	s.env.AssertExpectations(s.T())
}

// TestGraphWithLoopBackEdge_Rejected verifies the loop-back path through back-edges.
// Same graph as TestGraphWithLoopBackEdge.
// Signals A (approved), C (rejected) → B loops back, then C (approved) → D completes.
// B runs twice (iteration 1 and 2), C runs twice (iteration 1 and 2).
func (s *DAGExecutorTestSuite) TestGraphWithLoopBackEdge_Rejected() {
	nodeA := uuid.New()
	nodeB := uuid.New()
	nodeC := uuid.New()
	nodeD := uuid.New()
	execA := uuid.New()
	execB1 := uuid.New()
	execB2 := uuid.New()
	execC1 := uuid.New()
	execC2 := uuid.New()
	execD := uuid.New()
	runID := uuid.New()

	snapshot := `{
		"nodes": [
			{"templateNodeId": "` + nodeA.String() + `", "label": "Gate A", "executorType": "human", "timeoutSeconds": 3600, "isEntrypoint": true},
			{"templateNodeId": "` + nodeB.String() + `", "label": "AI Draft", "executorType": "ai", "timeoutSeconds": 1800},
			{"templateNodeId": "` + nodeC.String() + `", "label": "Review", "executorType": "human", "timeoutSeconds": 3600},
			{"templateNodeId": "` + nodeD.String() + `", "label": "Final", "executorType": "ai", "timeoutSeconds": 1800}
		],
		"edges": [
			{"sourceNodeId": "` + nodeA.String() + `", "targetNodeId": "` + nodeB.String() + `"},
			{"sourceNodeId": "` + nodeB.String() + `", "targetNodeId": "` + nodeC.String() + `"},
			{"sourceNodeId": "` + nodeC.String() + `", "targetNodeId": "` + nodeD.String() + `", "condition": "approved"},
			{"sourceNodeId": "` + nodeC.String() + `", "targetNodeId": "` + nodeB.String() + `", "condition": "rejected"}
		]
	}`

	s.env.OnActivity("UpdateWorkflowRunStatus", mock.Anything, mock.Anything).Return(nil)
	s.env.OnActivity("GetGraphRuntime", mock.Anything, runID).Return(snapshot, nil)
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("WriteExecutionLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("LoadPredecessorInputs", mock.Anything, mock.Anything).Return(map[string]string{}, nil).Maybe()
	s.env.OnActivity("LoadRequiredInputArtifacts", mock.Anything, mock.Anything).Return(activity.LoadRequiredInputArtifactsResult{}, nil).Maybe()
	s.env.OnActivity("LoadReviewHistoryJSON", mock.Anything, mock.Anything).Return("[]", nil).Maybe()
	s.env.OnActivity("InitRunLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("AppendRunLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("SetNodeDecision", mock.Anything, mock.Anything).Return(nil).Maybe()

	// CreateNodeExecution — B and C each created twice (once per iteration)
	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == nodeA
	})).Return(execA, nil).Once()
	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == nodeB && p.Iteration == 1
	})).Return(execB1, nil).Once()
	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == nodeB && p.Iteration == 2
	})).Return(execB2, nil).Once()
	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == nodeC && p.Iteration == 1
	})).Return(execC1, nil).Once()
	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == nodeC && p.Iteration == 2
	})).Return(execC2, nil).Once()
	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == nodeD
	})).Return(execD, nil).Once()

	// AI nodes — B runs twice (iterations 1 and 2), D runs once
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.TemplateNodeID == nodeB
	})).Return(activity.CallbackResult{}, nil).Times(2)
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.TemplateNodeID == nodeD
	})).Return(activity.CallbackResult{}, nil).Once()

	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execA
	})).Return("no_decision", nil).Once()
	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execB1
	})).Return("no_decision", nil).Once()
	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execB2
	})).Return("no_decision", nil).Once()
	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execC1
	})).Return("rejected", nil).Once()
	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execC2
	})).Return("approved", nil).Once()
	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execD
	})).Return("no_decision", nil).Once()

	// Signal A: approved → activates B (iteration 1)
	s.env.RegisterDelayedCallback(func() {
		s.env.SignalWorkflow(SignalHumanDecisionPrefix+execA.String(), HumanDecisionSignal{
			NodeExecutionID: execA.String(),
			Decision:        "approved",
			Feedback:        "Go ahead",
		})
	}, 0)

	// Signal C (iteration 1): rejected → back-edge fires, B loops to iteration 2
	s.env.RegisterDelayedCallback(func() {
		s.env.SignalWorkflow(SignalHumanDecisionPrefix+execC1.String(), HumanDecisionSignal{
			NodeExecutionID: execC1.String(),
			Decision:        "rejected",
			Feedback:        "Needs more work",
		})
	}, 0)

	// Signal C (iteration 2): approved → forward edge fires, D runs
	s.env.RegisterDelayedCallback(func() {
		s.env.SignalWorkflow(SignalHumanDecisionPrefix+execC2.String(), HumanDecisionSignal{
			NodeExecutionID: execC2.String(),
			Decision:        "approved",
			Feedback:        "Good now",
		})
	}, 0)

	s.env.ExecuteWorkflow(DAGExecutorWorkflow, DAGExecutorParams{
		RunID: runID, GraphVersion: 1,
	})

	s.True(s.env.IsWorkflowCompleted())
	s.NoError(s.env.GetWorkflowError())
}

// TestSelfLoopingAIReviewerIteratesThenAdvances pins down the v23 regression scenario:
// an AI reviewer node with a `revised` self-loop must (a) actually re-execute as a new
// iteration when it decides revised, and (b) advance forward when it decides approved.
// Without the self-edge fix in FindReadyNodes the workflow exits after the first
// iteration because the reviewer node ends up as its own pending predecessor.
func (s *DAGExecutorTestSuite) TestSelfLoopingAIReviewerIteratesThenAdvances() {
	draft := uuid.New()
	review := uuid.New()
	gate := uuid.New()
	execDraft := uuid.New()
	execReview1 := uuid.New()
	execReview2 := uuid.New()
	execGate := uuid.New()
	runID := uuid.New()

	snapshot := `{
		"nodes": [
			{"templateNodeId": "` + draft.String() + `", "label": "draft", "executorType": "ai", "timeoutSeconds": 1800, "isEntrypoint": true},
			{"templateNodeId": "` + review.String() + `", "label": "review", "executorType": "ai", "timeoutSeconds": 1800},
			{"templateNodeId": "` + gate.String() + `", "label": "gate", "executorType": "ai", "timeoutSeconds": 1800}
		],
		"edges": [
			{"sourceNodeId": "` + draft.String() + `", "targetNodeId": "` + review.String() + `"},
			{"sourceNodeId": "` + review.String() + `", "targetNodeId": "` + review.String() + `", "condition": "revised"},
			{"sourceNodeId": "` + review.String() + `", "targetNodeId": "` + gate.String() + `", "condition": "approved"}
		]
	}`

	s.env.OnActivity("UpdateWorkflowRunStatus", mock.Anything, mock.Anything).Return(nil)
	s.env.OnActivity("GetGraphRuntime", mock.Anything, runID).Return(snapshot, nil)
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("WriteExecutionLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("LoadPredecessorInputs", mock.Anything, mock.Anything).Return(map[string]string{}, nil).Maybe()
	s.env.OnActivity("LoadRequiredInputArtifacts", mock.Anything, mock.Anything).Return(activity.LoadRequiredInputArtifactsResult{}, nil).Maybe()
	s.env.OnActivity("LoadReviewHistoryJSON", mock.Anything, mock.Anything).Return("[]", nil).Maybe()
	s.env.OnActivity("InitRunLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("AppendRunLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("SetTraversedEdges", mock.Anything, mock.Anything).Return(nil).Maybe()

	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == draft
	})).Return(execDraft, nil).Once()
	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == review && p.Iteration == 1
	})).Return(execReview1, nil).Once()
	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == review && p.Iteration == 2
	})).Return(execReview2, nil).Once()
	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == gate
	})).Return(execGate, nil).Once()

	// review must execute twice (iter 1, iter 2). Times(2) is the load-bearing assertion:
	// without the FindReadyNodes self-edge skip, only iter 1 fires and the suite fails.
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.TemplateNodeID == draft
	})).Return(activity.CallbackResult{}, nil).Once()
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.TemplateNodeID == review
	})).Return(activity.CallbackResult{}, nil).Times(2)
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.TemplateNodeID == gate
	})).Return(activity.CallbackResult{}, nil).Once()

	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execDraft
	})).Return("no_decision", nil).Once()
	// iter 1: revised → self-loop fires, review iter 2 created
	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execReview1
	})).Return("revised", nil).Once()
	// iter 2: approved → forward edge to gate
	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execReview2
	})).Return("approved", nil).Once()
	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execGate
	})).Return("no_decision", nil).Once()

	s.env.ExecuteWorkflow(DAGExecutorWorkflow, DAGExecutorParams{
		RunID: runID, GraphVersion: 1,
	})

	s.True(s.env.IsWorkflowCompleted())
	s.NoError(s.env.GetWorkflowError())
}

// TestModelEffortResolution_SelfLoopingReviewNode_FirstThenSubsequentIteration
// covers Part 2 step 7(a)+(b) of the accompanying spec: a review
// node's iteration-aware config_overrides keys resolve to the first-iteration
// model/effort on its first pass (tracker.reviewPass == 1) and to the
// subsequent-iteration model/effort once the back-edge self-loop advances
// reviewPass to 2 — keyed on reviewPass, not the raw iteration counter.
func (s *DAGExecutorTestSuite) TestModelEffortResolution_SelfLoopingReviewNode_FirstThenSubsequentIteration() {
	draft := uuid.New()
	review := uuid.New()
	gate := uuid.New()
	execDraft := uuid.New()
	execReview1 := uuid.New()
	execReview2 := uuid.New()
	execGate := uuid.New()
	runID := uuid.New()

	snapshot := `{
		"nodes": [
			{"templateNodeId": "` + draft.String() + `", "label": "draft", "executorType": "ai", "timeoutSeconds": 1800, "isEntrypoint": true},
			{"templateNodeId": "` + review.String() + `", "label": "review", "executorType": "ai", "timeoutSeconds": 1800, "configOverrides": {"loop_group": "review", "model_first_iteration": "opus-x", "effort_first_iteration": "xhigh", "model_subsequent_iteration": "sonnet-y", "effort_subsequent_iteration": "high"}},
			{"templateNodeId": "` + gate.String() + `", "label": "gate", "executorType": "ai", "timeoutSeconds": 1800}
		],
		"edges": [
			{"sourceNodeId": "` + draft.String() + `", "targetNodeId": "` + review.String() + `"},
			{"sourceNodeId": "` + review.String() + `", "targetNodeId": "` + review.String() + `", "condition": "revised"},
			{"sourceNodeId": "` + review.String() + `", "targetNodeId": "` + gate.String() + `", "condition": "approved"}
		]
	}`

	s.env.OnActivity("UpdateWorkflowRunStatus", mock.Anything, mock.Anything).Return(nil)
	s.env.OnActivity("GetGraphRuntime", mock.Anything, runID).Return(snapshot, nil)
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("WriteExecutionLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("LoadPredecessorInputs", mock.Anything, mock.Anything).Return(map[string]string{}, nil).Maybe()
	s.env.OnActivity("LoadRequiredInputArtifacts", mock.Anything, mock.Anything).Return(activity.LoadRequiredInputArtifactsResult{}, nil).Maybe()
	s.env.OnActivity("LoadReviewHistoryJSON", mock.Anything, mock.Anything).Return("[]", nil).Maybe()
	s.env.OnActivity("InitRunLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("AppendRunLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("SetTraversedEdges", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("WriteReviewHistory", mock.Anything, mock.Anything).Return(nil).Maybe()

	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == draft
	})).Return(execDraft, nil).Once()
	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == review && p.Iteration == 1
	})).Return(execReview1, nil).Once()
	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == review && p.Iteration == 2
	})).Return(execReview2, nil).Once()
	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == gate
	})).Return(execGate, nil).Once()

	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.TemplateNodeID == draft
	})).Return(activity.CallbackResult{}, nil).Once()
	// First review pass (reviewPass == 1): resolves model_first_iteration/effort_first_iteration.
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.TemplateNodeID == review && p.Iteration == 1 && p.Model == "opus-x" && p.Effort == "xhigh"
	})).Return(activity.CallbackResult{}, nil).Once()
	// Second review pass, reached via the back-edge self-loop (reviewPass == 2):
	// resolves model_subsequent_iteration/effort_subsequent_iteration.
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.TemplateNodeID == review && p.Iteration == 2 && p.Model == "sonnet-y" && p.Effort == "high"
	})).Return(activity.CallbackResult{}, nil).Once()
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.TemplateNodeID == gate
	})).Return(activity.CallbackResult{}, nil).Once()

	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execDraft
	})).Return("no_decision", nil).Once()
	// iter 1: revised → self-loop fires, review iter 2 / reviewPass 2 created
	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execReview1
	})).Return("revised", nil).Once()
	// iter 2: approved → forward edge to gate
	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execReview2
	})).Return("approved", nil).Once()
	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execGate
	})).Return("no_decision", nil).Once()

	s.env.ExecuteWorkflow(DAGExecutorWorkflow, DAGExecutorParams{
		RunID: runID, GraphVersion: 1,
	})

	s.True(s.env.IsWorkflowCompleted())
	s.NoError(s.env.GetWorkflowError())
}

// TestModelEffortResolution_NodeWithoutIterationAwareKeys_FallsBackToStatic
// covers Part 2 step 7(c): a node whose config_overrides carry no
// iteration-aware keys at all (e.g. Implement, Draft Spec & Plan) is unaffected
// by the new resolution branch and still resolves via the pre-existing static
// model / flat effort path.
func (s *DAGExecutorTestSuite) TestModelEffortResolution_NodeWithoutIterationAwareKeys_FallsBackToStatic() {
	nodeA := uuid.New()
	execA := uuid.New()
	runID := uuid.New()

	snapshot := `{
		"nodes": [
			{"templateNodeId": "` + nodeA.String() + `", "label": "A", "executorType": "ai", "timeoutSeconds": 1800, "isEntrypoint": true, "model": "static-model", "configOverrides": {"effort": "static-effort"}}
		],
		"edges": []
	}`

	s.env.OnActivity("UpdateWorkflowRunStatus", mock.Anything, mock.Anything).Return(nil)
	s.env.OnActivity("GetGraphRuntime", mock.Anything, runID).Return(snapshot, nil)
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("WriteExecutionLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("InitRunLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("AppendRunLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("LoadPredecessorInputs", mock.Anything, mock.Anything).Return(map[string]string{}, nil).Maybe()
	s.env.OnActivity("LoadRequiredInputArtifacts", mock.Anything, mock.Anything).Return(activity.LoadRequiredInputArtifactsResult{}, nil).Maybe()
	s.env.OnActivity("LoadReviewHistoryJSON", mock.Anything, mock.Anything).Return("[]", nil).Maybe()

	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.Anything).Return(execA, nil).Once()

	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.TemplateNodeID == nodeA && p.Model == "static-model" && p.Effort == "static-effort"
	})).Return(activity.CallbackResult{}, nil).Once()

	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.Anything).Return("no_decision", nil).Once()

	s.env.ExecuteWorkflow(DAGExecutorWorkflow, DAGExecutorParams{
		RunID: runID, GraphVersion: 1,
	})

	s.True(s.env.IsWorkflowCompleted())
	s.NoError(s.env.GetWorkflowError())
}

// TestModelEffortResolution_PartialIterationKeys_FallsBackToStaticNotEmpty
// covers Part 2 step 7(d): a review node whose config_overrides
// set only model_first_iteration/effort_first_iteration — omitting the
// _subsequent_iteration counterparts — must fall back to the static
// model/flat effort value on reviewPass > 1, not silently resolve to an empty
// string. extractConfigField cannot distinguish an absent key from an
// explicit empty string, so a combined "either key present" guard would get
// this wrong; the per-branch-key-then-fallback resolution must not.
func (s *DAGExecutorTestSuite) TestModelEffortResolution_PartialIterationKeys_FallsBackToStaticNotEmpty() {
	review := uuid.New()
	execReview1 := uuid.New()
	execReview2 := uuid.New()
	runID := uuid.New()

	snapshot := `{
		"nodes": [
			{"templateNodeId": "` + review.String() + `", "label": "review", "executorType": "ai", "timeoutSeconds": 1800, "isEntrypoint": true, "model": "static-fallback-model", "configOverrides": {"loop_group": "review", "effort": "static-fallback-effort", "model_first_iteration": "opus-x", "effort_first_iteration": "xhigh"}}
		],
		"edges": [
			{"sourceNodeId": "` + review.String() + `", "targetNodeId": "` + review.String() + `", "condition": "revised"}
		]
	}`

	s.env.OnActivity("UpdateWorkflowRunStatus", mock.Anything, mock.Anything).Return(nil)
	s.env.OnActivity("GetGraphRuntime", mock.Anything, runID).Return(snapshot, nil)
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("WriteExecutionLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("InitRunLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("AppendRunLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("LoadPredecessorInputs", mock.Anything, mock.Anything).Return(map[string]string{}, nil).Maybe()
	s.env.OnActivity("LoadRequiredInputArtifacts", mock.Anything, mock.Anything).Return(activity.LoadRequiredInputArtifactsResult{}, nil).Maybe()
	s.env.OnActivity("LoadReviewHistoryJSON", mock.Anything, mock.Anything).Return("[]", nil).Maybe()
	s.env.OnActivity("SetTraversedEdges", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("WriteReviewHistory", mock.Anything, mock.Anything).Return(nil).Maybe()

	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.Iteration == 1
	})).Return(execReview1, nil).Once()
	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.Iteration == 2
	})).Return(execReview2, nil).Once()

	// reviewPass == 1: model_first_iteration/effort_first_iteration are set — use them.
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.Iteration == 1 && p.Model == "opus-x" && p.Effort == "xhigh"
	})).Return(activity.CallbackResult{}, nil).Once()
	// reviewPass == 2: model_subsequent_iteration/effort_subsequent_iteration are absent —
	// must fall back to the static model/flat effort, NOT resolve to "".
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.Iteration == 2 && p.Model == "static-fallback-model" && p.Effort == "static-fallback-effort"
	})).Return(activity.CallbackResult{}, nil).Once()

	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execReview1
	})).Return("revised", nil).Once()
	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execReview2
	})).Return("no_decision", nil).Once()

	s.env.ExecuteWorkflow(DAGExecutorWorkflow, DAGExecutorParams{
		RunID: runID, GraphVersion: 1,
	})

	s.True(s.env.IsWorkflowCompleted())
	s.NoError(s.env.GetWorkflowError())
}

// TestTaskContextFields_NilTaskContext confirms the nil-safety documented on
// taskContextFields/openBlockerFields (dag_executor.go) is exercised at THIS layer —
// the DAG-executor's own flattening step — not only via ExecuteAINodeFromSnapshot's
// activity-layer coverage in activities_test.go. A snapshot with TaskContext == nil is
// the ad-hoc-run case ("Preserve current behavior for Tasks with no
// dependency context"): a run that was never started from a roadmap Task.
func (s *DAGExecutorTestSuite) TestTaskContextFields_NilTaskContext() {
	snap := &state.GraphRuntimeSnapshot{TaskContext: nil}

	taskID, taskTitle, storyID, storyTitle, epicID, epicTitle := taskContextFields(snap)
	s.Empty(taskID, "TaskID")
	s.Empty(taskTitle, "TaskTitle")
	s.Empty(storyID, "StoryID")
	s.Empty(storyTitle, "StoryTitle")
	s.Empty(epicID, "EpicID")
	s.Empty(epicTitle, "EpicTitle")

	s.Empty(openBlockerFields(snap), "OpenBlockers")
}

// TestOpenBlockerFields_TaskContextWithNoOpenBlockers covers the sibling nil-safety
// case: a Task-linked run whose TaskContext resolves but has zero open blockers.
// openBlockerFields must still return an empty/nil slice (not a slice of length 1 with
// a zero-valued entry, and not a panic on the nil OpenBlockers field) so
// ExecuteAINodeFromSnapshot omits the open_blockers key rather than emitting a
// misleading "you have 0 blockers" narration downstream in entrypoint.sh.
func (s *DAGExecutorTestSuite) TestOpenBlockerFields_TaskContextWithNoOpenBlockers() {
	taskID := uuid.New()
	snap := &state.GraphRuntimeSnapshot{
		TaskContext: &state.SnapshotTaskContext{
			TaskID:    taskID,
			TaskTitle: "Task with no open blockers",
		},
	}

	gotTaskID, gotTaskTitle, _, _, _, _ := taskContextFields(snap)
	s.Equal(taskID.String(), gotTaskID)
	s.Equal("Task with no open blockers", gotTaskTitle)
	s.Empty(openBlockerFields(snap), "OpenBlockers")
}

// TestSupervisorRoutesPastAnUnrunPredecessor verifies the force-ready bypass end to
// end through the workflow (graph.go's FindReadyNodes/FindRoutingHub are covered at
// the unit level in graph_test.go; this exercises the dag_executor.go wiring that
// makes those functions meaningful).
//
// Graph: implement fans out to code_review and test in parallel. code_review
// escalates instead of taking a normal edge, so the Supervisor (an edgeless
// human "routing_hub" node) receives control and routes straight to
// final_approval — final_approval's ordinary predecessor is test, but test is
// deliberately routed around, mirroring a wedged test environment that a
// reviewer chooses to skip.
//
// test's activity is held in-flight for 1 (virtual) second via .After(), so at
// the moment the Supervisor's decision is processed, test is still "running" —
// not absent from tracking, not completed. Without the force-ready flag,
// FindReadyNodes would keep final_approval pending until test's predecessor
// edge is satisfied, i.e. final_approval would only start once test finishes.
// The test proves the opposite happened: final_approval's activity starts
// while test is still incomplete. testCompleted/startedWhileTestIncomplete are
// set from the mock's Run() callbacks, which the SDK invokes immediately
// before an activity call returns (so test's callback fires only after its
// 1-second .After() elapses) — see runBeforeMockCallReturns in the Temporal Go
// SDK's test suite.
func (s *DAGExecutorTestSuite) TestSupervisorRoutesPastAnUnrunPredecessor() {
	implement := uuid.New()
	codeReview := uuid.New()
	test := uuid.New()
	finalApproval := uuid.New()
	supervisor := uuid.New()

	execImplement := uuid.New()
	execCodeReview := uuid.New()
	execTest := uuid.New()
	execFinalApproval := uuid.New()
	execSupervisor := uuid.New()

	runID := uuid.New()

	snapshot := `{
		"nodes": [
			{"templateNodeId": "` + implement.String() + `", "label": "implement", "executorType": "ai", "timeoutSeconds": 1800, "isEntrypoint": true},
			{"templateNodeId": "` + codeReview.String() + `", "label": "code_review", "executorType": "ai", "timeoutSeconds": 1800},
			{"templateNodeId": "` + test.String() + `", "label": "test", "executorType": "ai", "timeoutSeconds": 1800, "configOverrides": {"terminal_decisions": ["no_decision"]}},
			{"templateNodeId": "` + finalApproval.String() + `", "label": "final_approval", "executorType": "ai", "timeoutSeconds": 1800},
			{"templateNodeId": "` + supervisor.String() + `", "label": "supervisor", "executorType": "human", "timeoutSeconds": 3600, "configOverrides": {"routing_hub": true}}
		],
		"edges": [
			{"sourceNodeId": "` + implement.String() + `", "targetNodeId": "` + codeReview.String() + `"},
			{"sourceNodeId": "` + implement.String() + `", "targetNodeId": "` + test.String() + `"},
			{"sourceNodeId": "` + test.String() + `", "targetNodeId": "` + finalApproval.String() + `", "condition": "approved"}
		]
	}`

	s.env.OnActivity("UpdateWorkflowRunStatus", mock.Anything, mock.Anything).Return(nil)
	s.env.OnActivity("GetGraphRuntime", mock.Anything, runID).Return(snapshot, nil)
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("WriteExecutionLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("InitRunLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("AppendRunLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("SetTraversedEdges", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("LoadPredecessorInputs", mock.Anything, mock.Anything).Return(map[string]string{}, nil).Maybe()
	s.env.OnActivity("LoadRequiredInputArtifacts", mock.Anything, mock.Anything).Return(activity.LoadRequiredInputArtifactsResult{}, nil).Maybe()
	s.env.OnActivity("LoadReviewHistoryJSON", mock.Anything, mock.Anything).Return("[]", nil).Maybe()

	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == implement
	})).Return(execImplement, nil).Once()
	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == codeReview
	})).Return(execCodeReview, nil).Once()
	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == test
	})).Return(execTest, nil).Once()
	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == supervisor
	})).Return(execSupervisor, nil).Once()
	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == finalApproval
	})).Return(execFinalApproval, nil).Once()

	var mu sync.Mutex
	testCompleted := false
	finalApprovalStartedWhileTestIncomplete := false

	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.TemplateNodeID == implement
	})).Return(activity.CallbackResult{}, nil).Once()
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.TemplateNodeID == codeReview
	})).Return(activity.CallbackResult{}, nil).Once()
	// test is held in-flight for 1 virtual second so it is still "running" — tracked,
	// not absent — at the moment the Supervisor routes around it.
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.TemplateNodeID == test
	})).After(time.Second).Run(func(args mock.Arguments) {
		mu.Lock()
		testCompleted = true
		mu.Unlock()
	}).Return(activity.CallbackResult{}, nil).Once()
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.TemplateNodeID == finalApproval
	})).Run(func(args mock.Arguments) {
		mu.Lock()
		if !testCompleted {
			finalApprovalStartedWhileTestIncomplete = true
		}
		mu.Unlock()
	}).Return(activity.CallbackResult{}, nil).Once()

	s.env.OnActivity("SetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.SetNodeDecisionParams) bool {
		return p.NodeExecutionID == execSupervisor && p.Decision == "route:final_approval"
	})).Return(nil).Once()

	// code_review escalates instead of following a normal edge.
	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execImplement
	})).Return("no_decision", nil).Once()
	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execCodeReview
	})).Return("escalate", nil).Once()
	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execTest
	})).Return("no_decision", nil).Once()
	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execSupervisor
	})).Return("route:final_approval", nil).Once()
	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execFinalApproval
	})).Return("no_decision", nil).Once()

	// The Supervisor routes to final_approval as soon as it is paged.
	s.env.RegisterDelayedCallback(func() {
		s.env.SignalWorkflow(SignalHumanDecisionPrefix+execSupervisor.String(), HumanDecisionSignal{
			NodeExecutionID: execSupervisor.String(),
			Decision:        "route:final_approval",
			Feedback:        "Test environment is wedged — skipping straight to final approval",
		})
	}, 0)

	s.env.ExecuteWorkflow(DAGExecutorWorkflow, DAGExecutorParams{
		RunID: runID, GraphVersion: 1,
	})

	s.True(s.env.IsWorkflowCompleted())
	s.NoError(s.env.GetWorkflowError())

	mu.Lock()
	defer mu.Unlock()
	s.True(finalApprovalStartedWhileTestIncomplete,
		"final_approval must start while test (its ordinary predecessor) is still incomplete — "+
			"the force-ready bypass. Without it, final_approval would stay pending until test "+
			"completes on its own, which this graph is built to never let happen in time.")
}

// TestForceReadyCarriesForwardThroughLateHumanDecisionRetry covers the OTHER reachable
// forceReady carry-forward site (dag_executor.go's late-human-decision-retry handler,
// gated on a failed node's ExecutorType == "human"): a Supervisor route:<label> decision
// can name a human node — e.g. a downstream approval gate — and that gate can time out
// before anyone acts on it. A late decision arriving afterward must still see the node
// as force-ready, or it falls back into ordinary predecessor gating on the very upstream
// node the reviewer routed past, and hangs forever.
//
// Graph: implement fans out to code_review and test, in parallel. test fails outright
// (not "eventually completes" — this matters: it must never become "completed" for the
// rest of the test, so the predecessor gate stays genuinely blocked, not just delayed).
// code_review escalates; the Supervisor routes to final_approval, a HUMAN node whose
// ordinary predecessor is test. final_approval's first execution times out unanswered
// (nobody signals it), so it lands in failedNodeIDs alongside test — entering
// awaiting_retry — at which point a late decision signal arrives on its original
// execution's channel. That rebuilds final_approval's tracker at the line-445 site.
//
// The assertion is structural, not timing-based (unlike the sibling test above): since
// test never completes, final_approval can only ever become ready again via the
// force-ready flag surviving the rebuild. If it doesn't, final_approval's retried
// execution (iteration 2) never reaches "awaiting_human"/"completed" — the .Once()
// mocks below simply never fire, and testify's AssertExpectations (in AfterTest) fails
// the test.
func (s *DAGExecutorTestSuite) TestForceReadyCarriesForwardThroughLateHumanDecisionRetry() {
	implement := uuid.New()
	codeReview := uuid.New()
	test := uuid.New()
	finalApproval := uuid.New()
	supervisor := uuid.New()

	execImplement := uuid.New()
	execCodeReview := uuid.New()
	execTest := uuid.New()
	execFinalApproval1 := uuid.New()
	execFinalApproval2 := uuid.New()
	execSupervisor := uuid.New()

	runID := uuid.New()

	snapshot := `{
		"nodes": [
			{"templateNodeId": "` + implement.String() + `", "label": "implement", "executorType": "ai", "timeoutSeconds": 1800, "isEntrypoint": true},
			{"templateNodeId": "` + codeReview.String() + `", "label": "code_review", "executorType": "ai", "timeoutSeconds": 1800},
			{"templateNodeId": "` + test.String() + `", "label": "test", "executorType": "ai", "timeoutSeconds": 1800},
			{"templateNodeId": "` + finalApproval.String() + `", "label": "final_approval", "executorType": "human", "timeoutSeconds": 60},
			{"templateNodeId": "` + supervisor.String() + `", "label": "supervisor", "executorType": "human", "timeoutSeconds": 3600, "configOverrides": {"routing_hub": true}}
		],
		"edges": [
			{"sourceNodeId": "` + implement.String() + `", "targetNodeId": "` + codeReview.String() + `"},
			{"sourceNodeId": "` + implement.String() + `", "targetNodeId": "` + test.String() + `"},
			{"sourceNodeId": "` + test.String() + `", "targetNodeId": "` + finalApproval.String() + `", "condition": "approved"}
		]
	}`

	s.env.OnActivity("UpdateWorkflowRunStatus", mock.Anything, mock.Anything).Return(nil)
	s.env.OnActivity("GetGraphRuntime", mock.Anything, runID).Return(snapshot, nil)
	s.env.OnActivity("WriteExecutionLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("InitRunLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("AppendRunLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("SetTraversedEdges", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("LoadPredecessorInputs", mock.Anything, mock.Anything).Return(map[string]string{}, nil).Maybe()
	s.env.OnActivity("LoadRequiredInputArtifacts", mock.Anything, mock.Anything).Return(activity.LoadRequiredInputArtifactsResult{}, nil).Maybe()
	s.env.OnActivity("LoadReviewHistoryJSON", mock.Anything, mock.Anything).Return("[]", nil).Maybe()
	s.env.OnActivity("FetchPodLogs", mock.Anything, mock.Anything).Return("", nil).Maybe()
	s.env.OnActivity("DeleteAgentJob", mock.Anything, mock.Anything).Return(nil).Maybe()

	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == implement
	})).Return(execImplement, nil).Once()
	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == codeReview
	})).Return(execCodeReview, nil).Once()
	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == test
	})).Return(execTest, nil).Once()
	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == supervisor
	})).Return(execSupervisor, nil).Once()
	// final_approval is created twice: iteration 1 (routed to by the Supervisor, times
	// out unanswered) and iteration 2 (the late-decision retry this test is about).
	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == finalApproval && p.Iteration == 1
	})).Return(execFinalApproval1, nil).Once()
	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == finalApproval && p.Iteration == 2
	})).Return(execFinalApproval2, nil).Once()

	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.TemplateNodeID == implement
	})).Return(activity.CallbackResult{}, nil).Once()
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.TemplateNodeID == codeReview
	})).Return(activity.CallbackResult{}, nil).Once()
	// test fails outright — permanently, never retried in this test — so the predecessor
	// gate final_approval depends on stays blocked for the rest of the run. If forceReady
	// is lost anywhere, final_approval has no other way to ever become ready again.
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.TemplateNodeID == test
	})).Return(activity.CallbackResult{}, fmt.Errorf("test environment down")).Once()

	// code_review escalates instead of following a normal edge.
	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execImplement
	})).Return("no_decision", nil).Once()
	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execCodeReview
	})).Return("escalate", nil).Once()
	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execSupervisor
	})).Return("route:final_approval", nil).Once()
	// The retried (iteration 2) execution's decision — read back after SetNodeDecision
	// persists it in the late-decision handler.
	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execFinalApproval2
	})).Return("approved", nil).Once()
	// execTest and execFinalApproval1 never reach the decision-read step — both fail/time
	// out, and the failure path returns before GetNodeDecision is ever called for them.

	s.env.OnActivity("SetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.SetNodeDecisionParams) bool {
		return p.NodeExecutionID == execSupervisor && p.Decision == "route:final_approval"
	})).Return(nil).Once()
	s.env.OnActivity("SetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.SetNodeDecisionParams) bool {
		return p.NodeExecutionID == execFinalApproval2 && p.Decision == "approved"
	})).Return(nil).Once()

	// The Supervisor routes to final_approval as soon as it is paged.
	s.env.RegisterDelayedCallback(func() {
		s.env.SignalWorkflow(SignalHumanDecisionPrefix+execSupervisor.String(), HumanDecisionSignal{
			NodeExecutionID: execSupervisor.String(),
			Decision:        "route:final_approval",
			Feedback:        "Routing straight to final approval",
		})
	}, 0)

	// final_approval's FIRST execution must be left unsignaled until it genuinely times
	// out (timeoutSeconds: 60) — waitForHumanDecision is actively blocked on Receive from
	// this same channel name (SignalHumanDecisionPrefix+execFinalApproval1) from the
	// moment final_approval is first dispatched, so a signal sent any earlier is
	// delivered to THAT live listener and completes iteration 1 directly, short-circuiting
	// the very failure/retry path this test exists to exercise. Only once that listener
	// gives up (60s) and the workflow re-registers the same channel name inside
	// awaiting_retry mode does a signal sent here reach the late-decision-retry handler
	// this test is actually about.
	s.env.RegisterDelayedCallback(func() {
		s.env.SignalWorkflow(SignalHumanDecisionPrefix+execFinalApproval1.String(), HumanDecisionSignal{
			NodeExecutionID: execFinalApproval1.String(),
			Decision:        "approved",
			Feedback:        "Late approval after the gate timed out",
		})
	}, 65*time.Second)

	// The retried (iteration 2) execution must reach "awaiting_human" and then
	// "completed" — proof that FindReadyNodes admitted it despite test (its ordinary,
	// permanently-failed predecessor) never completing.
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.MatchedBy(func(p activity.UpdateNodeExecStatusParams) bool {
		return p.NodeExecutionID == execFinalApproval2 && p.Status == "awaiting_human"
	})).Return(nil).Once()
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.MatchedBy(func(p activity.UpdateNodeExecStatusParams) bool {
		return p.NodeExecutionID == execFinalApproval2 && p.Status == "completed"
	})).Return(nil).Once()
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.Anything).Return(nil).Maybe()

	s.env.ExecuteWorkflow(DAGExecutorWorkflow, DAGExecutorParams{
		RunID: runID, GraphVersion: 1,
	})

	s.True(s.env.IsWorkflowCompleted())
	s.NoError(s.env.GetWorkflowError())
}

// TestCompletedStatusRejected_PersistsFailedStatus verifies that when the api-server
// rejects a node's "completed" status update, the resulting failure is written to the
// DB and not only to the workflow's in-memory tracker.
//
// The tracker and the node_execution row are two copies of one fact, and every
// consumer outside the workflow — the web UI's Retry button, RunService.retryNode —
// reads the row. A branch that marks the tracker failed without persisting it parks
// the run in awaiting_retry while the row still reads "running", which no operator
// can then act on: the button is gated on status=failed and the retry endpoint
// rejects anything else.
func (s *DAGExecutorTestSuite) TestCompletedStatusRejected_PersistsFailedStatus() {
	nodeA := uuid.New()
	execA := uuid.New()
	runID := uuid.New()

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
	s.env.OnActivity("LoadPredecessorInputs", mock.Anything, mock.Anything).Return(map[string]string{}, nil).Maybe()
	s.env.OnActivity("LoadRequiredInputArtifacts", mock.Anything, mock.Anything).
		Return(activity.LoadRequiredInputArtifactsResult{}, nil).Maybe()
	s.env.OnActivity("LoadReviewHistoryJSON", mock.Anything, mock.Anything).Return("[]", nil).Maybe()
	s.env.OnActivity("FetchPodLogs", mock.Anything, mock.Anything).Return("", nil).Maybe()
	s.env.OnActivity("DeleteAgentJob", mock.Anything, mock.Anything).Return(nil).Maybe()

	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.Anything).Return(execA, nil).Once()
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.Anything).
		Return(activity.CallbackResult{}, nil)

	// The api-server rejects the completion — e.g. a stale `escalate` decision with no
	// escalation.md, which enforceOutputSpec answers with a 400 before it saves the row.
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything,
		mock.MatchedBy(func(p activity.UpdateNodeExecStatusParams) bool {
			return p.Status == "completed"
		})).Return(fmt.Errorf("api error 400: decision 'escalate' without producing escalation.md"))

	// The failure this produces must reach the row the UI and the retry API read.
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything,
		mock.MatchedBy(func(p activity.UpdateNodeExecStatusParams) bool {
			return p.NodeExecutionID == execA && p.Status == "failed" && p.ErrorMessage != nil
		})).Return(nil).Once()

	// Any other status write (running, awaiting_human, …) is incidental to this test.
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.Anything).Return(nil).Maybe()

	s.env.ExecuteWorkflow(DAGExecutorWorkflow, DAGExecutorParams{RunID: runID, GraphVersion: 1})

	s.True(s.env.IsWorkflowCompleted())
	s.NoError(s.env.GetWorkflowError())
}

// --- Stale run-branch cleanup (Part 2): scheduled only when the run reaches "completed" ---

// TestCleanupActivityScheduledOnCompletion verifies Step 6 schedules DeleteStaleBranches for
// the run once the workflow reaches finalStatus "completed".
func (s *DAGExecutorTestSuite) TestCleanupActivityScheduledOnCompletion() {
	nodeA := uuid.New()
	execA := uuid.New()
	runID := uuid.New()

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

	// .Once() (not .Maybe()): the test fails if Step 6 never schedules the cleanup activity
	// for this run once it reaches "completed".
	s.env.OnActivity("DeleteStaleBranches", mock.Anything, mock.MatchedBy(func(p activity.DeleteStaleBranchesParams) bool {
		return p.RunID == runID
	})).Return(nil).Once()

	s.env.ExecuteWorkflow(DAGExecutorWorkflow, DAGExecutorParams{
		RunID: runID, GraphVersion: 1,
	})

	s.True(s.env.IsWorkflowCompleted())
	s.NoError(s.env.GetWorkflowError())
}

// TestCleanupActivityErrorDoesNotFailWorkflow verifies a DeleteStaleBranches failure is
// tolerated: Step 6's UpdateWorkflowRunStatus call still records "completed", and the
// workflow itself still completes without error — the cleanup is best-effort, never a
// condition of the run's own success.
func (s *DAGExecutorTestSuite) TestCleanupActivityErrorDoesNotFailWorkflow() {
	nodeA := uuid.New()
	execA := uuid.New()
	runID := uuid.New()

	snapshot := `{
		"nodes": [
			{"templateNodeId": "` + nodeA.String() + `", "label": "A", "executorType": "ai", "timeoutSeconds": 1800, "isEntrypoint": true}
		],
		"edges": []
	}`

	s.env.OnActivity("UpdateWorkflowRunStatus", mock.Anything,
		mock.MatchedBy(func(p activity.UpdateRunStatusParams) bool {
			return p.RunID == runID && p.Status == "completed"
		})).Return(nil).Once()
	s.env.OnActivity("UpdateWorkflowRunStatus", mock.Anything, mock.Anything).Return(nil).Maybe()
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

	// No .Once(): dbCtx's RetryPolicy (MaximumAttempts: 3) retries an erroring activity, so this
	// stub must stay eligible across every attempt rather than being spent after the first.
	s.env.OnActivity("DeleteStaleBranches", mock.Anything, mock.Anything).
		Return(fmt.Errorf("api error 500: internal error"))

	s.env.ExecuteWorkflow(DAGExecutorWorkflow, DAGExecutorParams{
		RunID: runID, GraphVersion: 1,
	})

	s.True(s.env.IsWorkflowCompleted())
	s.NoError(s.env.GetWorkflowError())
}

// TestCleanupActivityNotScheduledOnFailure verifies Step 6 never schedules DeleteStaleBranches
// when the run ends "failed" — same graph shape as TestTimeoutCallsDeleteAgentJob, with the
// added assertion that cleanup is not scheduled.
func (s *DAGExecutorTestSuite) TestCleanupActivityNotScheduledOnFailure() {
	nodeA := uuid.New()
	execA := uuid.New()
	runID := uuid.New()

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
	s.env.OnActivity("FetchPodLogs", mock.Anything, mock.Anything).Return("", nil).Maybe()
	s.env.OnActivity("DeleteAgentJob", mock.Anything, mock.Anything).Return(nil).Maybe()

	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == nodeA
	})).Return(execA, nil).Once()

	s.env.OnActivity("LoadPredecessorInputs", mock.Anything, mock.Anything).Return(map[string]string{}, nil).Maybe()
	s.env.OnActivity("LoadRequiredInputArtifacts", mock.Anything, mock.Anything).
		Return(activity.LoadRequiredInputArtifactsResult{}, nil).Maybe()
	s.env.OnActivity("LoadReviewHistoryJSON", mock.Anything, mock.Anything).Return("[]", nil).Maybe()

	// AI node A fails permanently (simulates a timeout/error), driving finalStatus to "failed".
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.TemplateNodeID == nodeA
	})).Return(activity.CallbackResult{}, fmt.Errorf("activity timeout"))

	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.MatchedBy(func(p activity.UpdateNodeExecStatusParams) bool {
		return p.NodeExecutionID == execA && p.Status == "failed"
	})).Return(nil).Once()

	var cleanupCalled bool
	s.env.OnActivity("DeleteStaleBranches", mock.Anything, mock.Anything).
		Run(func(mock.Arguments) { cleanupCalled = true }).
		Return(nil).
		Maybe()

	s.env.ExecuteWorkflow(DAGExecutorWorkflow, DAGExecutorParams{
		RunID: runID, GraphVersion: 1,
	})

	s.True(s.env.IsWorkflowCompleted())
	s.False(cleanupCalled, "DeleteStaleBranches must not be scheduled when the run ends failed")
}

// TestCleanupActivityNotScheduledOnCancel verifies Step 6 never schedules DeleteStaleBranches
// when the run ends "cancelled" — same graph/cancel shape as
// TestCancelStep5ToleratesDeleteAgentJob404, with the added assertion that cleanup is not
// scheduled.
func (s *DAGExecutorTestSuite) TestCleanupActivityNotScheduledOnCancel() {
	nodeA := uuid.New()
	execA := uuid.New()
	runID := uuid.New()

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

	// Fire the cancel from the node activity's own invocation (see
	// TestCancelStep5ToleratesDeleteAgentJob404's comment for why: triggering the cancel from
	// dispatch removes a race against a fixed virtual-time delay).
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.Anything).
		Run(func(mock.Arguments) {
			s.env.SignalWorkflow("cancel", nil)
		}).
		After(time.Second).Return(activity.CallbackResult{}, nil)

	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("DeleteAgentJob", mock.Anything, mock.Anything).Return(nil).Maybe()

	var cleanupCalled bool
	s.env.OnActivity("DeleteStaleBranches", mock.Anything, mock.Anything).
		Run(func(mock.Arguments) { cleanupCalled = true }).
		Return(nil).
		Maybe()

	s.env.ExecuteWorkflow(DAGExecutorWorkflow, DAGExecutorParams{
		RunID: runID, GraphVersion: 1,
	})

	s.True(s.env.IsWorkflowCompleted())
	s.NoError(s.env.GetWorkflowError())
	s.False(cleanupCalled, "DeleteStaleBranches must not be scheduled when the run ends cancelled")
}
