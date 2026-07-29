package workflow

import (
	"fmt"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/mock"
	"github.com/stretchr/testify/suite"
	"go.temporal.io/sdk/testsuite"

	"github.com/dangtrivan15/choruskube/orchestrator/internal/activity"
)

type DAGExecutorTestSuite struct {
	suite.Suite
	testsuite.WorkflowTestSuite
	env *testsuite.TestWorkflowEnvironment
}

func (s *DAGExecutorTestSuite) SetupTest() {
	s.env = s.NewTestWorkflowEnvironment()
	var a *activity.Activities
	s.env.RegisterActivity(a)
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
	s.env.OnActivity("LoadReviewHistoryJSON", mock.Anything, mock.Anything).Return("[]", nil).Maybe()

	// AI node A executes (async completion — returns nil in test, result is zero-valued)
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.TemplateNodeID == nodeA
	})).Return(nil)

	// Node B created after A completes (unconditional edge fires with empty result)
	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == nodeB
	})).Return(execB, nil).Once()

	// AI node B executes
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.TemplateNodeID == nodeB
	})).Return(nil)

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

// TestLinearTwoNodeGraph_TaskContextPropagatesToAllNodes verifies Decision 3: a
// snapshot's taskContext (Task -> Story -> Epic identity, and its openBlockers,
// Decision 1/4) must reach EVERY node execution's config.json, not just the
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
	})).Return(nil)

	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == nodeB
	})).Return(execB, nil).Once()

	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.TemplateNodeID == nodeB && taskContextMatches(p)
	})).Return(nil)

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
	s.env.OnActivity("LoadReviewHistoryJSON", mock.Anything, mock.Anything).Return("[]", nil).Maybe()
	s.env.OnActivity("InitRunLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("AppendRunLog", mock.Anything, mock.Anything).Return(nil).Maybe()

	// Entry node A
	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == nodeA
	})).Return(execA, nil).Once()

	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.TemplateNodeID == nodeA
	})).Return(nil)

	// Both B and C get created (fan-out)
	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == nodeB
	})).Return(execB, nil).Once()
	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == nodeC
	})).Return(execC, nil).Once()

	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.TemplateNodeID == nodeB
	})).Return(nil)
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.TemplateNodeID == nodeC
	})).Return(nil)

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
	})).Return(nil).Once()
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.TemplateNodeID == nodeD
	})).Return(nil).Once()

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
	})).Return(nil).Once()

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
	})).Return(nil).Once()

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
	s.env.OnActivity("FetchPodLogs", mock.Anything, mock.Anything).Return("", nil).Maybe()

	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == nodeA
	})).Return(execA, nil).Once()

	s.env.OnActivity("LoadPredecessorInputs", mock.Anything, mock.Anything).Return(map[string]string{}, nil).Maybe()
	s.env.OnActivity("LoadReviewHistoryJSON", mock.Anything, mock.Anything).Return("[]", nil).Maybe()

	// AI node A fails (simulates timeout/error)
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.TemplateNodeID == nodeA
	})).Return(fmt.Errorf("activity timeout"))

	// UpdateNodeExecutionStatus must be called with failed
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.MatchedBy(func(p activity.UpdateNodeExecStatusParams) bool {
		return p.NodeExecutionID == execA && p.Status == "failed"
	})).Return(nil).Once()

	// DeleteAgentJob must be called after node timeout
	s.env.OnActivity("DeleteAgentJob", mock.Anything, mock.MatchedBy(func(p activity.DeleteAgentJobParams) bool {
		return p.NodeExecutionID == execA
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
	s.env.OnActivity("LoadReviewHistoryJSON", mock.Anything, mock.Anything).Return("[]", nil).Maybe()

	// AI node A runs — delayed 1 s so the pause signal fires while the activity is
	// still in-flight (tracker.status == "running"). Without .After(), the activity
	// returns synchronously and the node is "completed" before the pause signal is
	// delivered, making the pause cleanup loop a no-op.
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.TemplateNodeID == nodeA
	})).After(time.Second).Return(nil)

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
		return p.NodeExecutionID == execA
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
	})).After(time.Second).Return(fmt.Errorf("heartbeat timeout"))

	// Second execution (iteration 2): succeeds
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.NodeExecutionID == execA2
	})).Return(nil)

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
		return p.NodeExecutionID == execA
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
	})).Return(nil)

	// Node B (iteration 1): delayed so it is still in-flight when the pause signal fires
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.NodeExecutionID == execB1
	})).After(time.Second).Return(fmt.Errorf("heartbeat timeout"))

	// Node B (iteration 2): succeeds
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.NodeExecutionID == execB2
	})).Return(nil)

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
		return p.NodeExecutionID == execB1
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
	})).After(500 * time.Millisecond).Return(fmt.Errorf("heartbeat timeout"))

	// Second execution succeeds
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.NodeExecutionID == execA2
	})).Return(nil)

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
	})).Return(nil).Times(2)
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.TemplateNodeID == nodeD
	})).Return(nil).Once()

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
	})).Return(nil).Once()
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.TemplateNodeID == review
	})).Return(nil).Times(2)
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.TemplateNodeID == gate
	})).Return(nil).Once()

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
