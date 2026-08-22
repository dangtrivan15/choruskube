package workflow

import (
	"fmt"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/mock"

	"github.com/dangtrivan15/choruskube/orchestrator/internal/activity"
)

// TestHumanGateTimeout_RetryViaApproval verifies: when a human gate times out
// and enters awaiting_retry, a subsequent human-decision signal on the original
// exec ID triggers a retry with the decision pre-applied.
// Graph: A (human, 1s timeout) — single entry node, no edges.
func (s *DAGExecutorTestSuite) TestHumanGateTimeout_RetryViaApproval() {
	nodeA := uuid.New()
	execA := uuid.New()  // first execution (will time out)
	execA2 := uuid.New() // retry execution
	runID := uuid.New()

	snapshot := `{
		"nodes": [
			{"templateNodeId": "` + nodeA.String() + `", "label": "Review", "executorType": "human", "timeoutSeconds": 1, "isEntrypoint": true}
		],
		"edges": []
	}`

	s.env.OnActivity("UpdateWorkflowRunStatus", mock.Anything, mock.Anything).Return(nil)
	s.env.OnActivity("GetGraphRuntime", mock.Anything, runID).Return(snapshot, nil)
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("WriteExecutionLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("InitRunLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("AppendRunLog", mock.Anything, mock.Anything).Return(nil).Maybe()

	// First execution created
	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == nodeA && p.Iteration == 1
	})).Return(execA, nil).Once()

	// Retry execution created (from human-decision signal in retry loop)
	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == nodeA && p.Iteration == 2
	})).Return(execA2, nil).Once()

	// SetNodeDecision called for the retry execution
	s.env.OnActivity("SetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.SetNodeDecisionParams) bool {
		return p.NodeExecutionID == execA2 && p.Decision == "approved"
	})).Return(nil).Once()

	// GetNodeDecision called during completion for retry execution —
	// returns "approved" because SetNodeDecision persisted it earlier.
	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execA2
	})).Return("approved", nil).Once()

	// After the 1s timeout, send human-decision signal on the ORIGINAL exec ID.
	// This simulates the user clicking approve after the gate timed out.
	s.env.RegisterDelayedCallback(func() {
		s.env.SignalWorkflow(SignalHumanDecisionPrefix+execA.String(), HumanDecisionSignal{
			NodeExecutionID: execA.String(),
			Decision:        "approved",
			Feedback:        "Looks good (late approval)",
		})
	}, 2*time.Second) // 2s > 1s timeout, so gate times out first

	s.env.ExecuteWorkflow(DAGExecutorWorkflow, DAGExecutorParams{
		RunID: runID, GraphVersion: 1,
	})

	s.True(s.env.IsWorkflowCompleted())
	s.NoError(s.env.GetWorkflowError())
}

// TestCancelStep5ToleratesDeleteAgentJob404 verifies that when Step 5 (cancel cleanup)
// calls DeleteAgentJob and it returns a 404-like error (already deleted by api-server),
// the workflow still completes successfully.
func (s *DAGExecutorTestSuite) TestCancelStep5ToleratesDeleteAgentJob404() {
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
	s.env.OnActivity("LoadRequiredInputArtifacts", mock.Anything, mock.Anything).Return(activity.LoadRequiredInputArtifactsResult{}, nil).Maybe()
	s.env.OnActivity("LoadReviewHistoryJSON", mock.Anything, mock.Anything).Return("[]", nil).Maybe()

	// Fire the cancel from the node activity's own invocation. .Run executes when
	// ExecuteAINodeFromSnapshot is dispatched — which only happens after the loop's
	// top-of-iteration cancel drain — so the cancel can never precede dispatch. .After
	// then holds the activity in-flight (tracker.status == "running") while the cancel
	// is delivered, so Step 5's cleanup dispatches DeleteAgentJob.
	//
	// The previous form scheduled the cancel on a fixed virtual-time delay racing a
	// real-clock activity delay; on a loaded runner the cancel fired before dispatch and
	// ExecuteAINodeFromSnapshot was never called. Triggering the cancel from dispatch
	// removes the race.
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.Anything).
		Run(func(mock.Arguments) {
			s.env.SignalWorkflow("cancel", nil)
		}).
		After(time.Second).Return(activity.CallbackResult{}, nil)

	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.Anything).Return(nil).Maybe()

	// DeleteAgentJob returns an error (simulates api-server already deleted it — 404).
	// .Once() (not .Maybe()) enforces the call is actually made by Step 5's cancel
	// cleanup path — the test fails if DeleteAgentJob is never dispatched.
	// The workflow must still complete successfully despite the 404 error.
	s.env.OnActivity("DeleteAgentJob", mock.Anything, mock.Anything).
		Return(fmt.Errorf("resource not found: 404")).Once()

	s.env.ExecuteWorkflow(DAGExecutorWorkflow, DAGExecutorParams{
		RunID: runID, GraphVersion: 1,
	})

	s.True(s.env.IsWorkflowCompleted())
	// Workflow must complete without error despite DeleteAgentJob 404
	s.NoError(s.env.GetWorkflowError())
}

// TestScriptNodeRetry_RefreshesSnapshot verifies: when a script node fails and
// is retried, the workflow re-fetches the graph snapshot so that GitRepo changes
// (e.g. updated test_command) take effect on the retried execution.
// Graph: A (script, entrypoint) — single node, no edges.
func (s *DAGExecutorTestSuite) TestScriptNodeRetry_RefreshesSnapshot() {
	nodeA := uuid.New()
	execA := uuid.New()  // first execution (will fail)
	execA2 := uuid.New() // retry execution
	runID := uuid.New()

	oldCommand := "npm run test"
	newCommand := "npm install && npm run test"

	snapshotWithCommand := func(cmd string) string {
		return fmt.Sprintf(`{
			"nodes": [
				{
					"templateNodeId": "%s",
					"label": "test",
					"executorType": "script",
					"timeoutSeconds": 300,
					"configOverrides": {"command": "{run.test_command}", "needs_branch": "true"},
					"isEntrypoint": true
				}
			],
			"edges": [],
			"inputs": {"test_command": "%s", "repo_url": "https://github.com/example/repo"}
		}`, nodeA.String(), cmd)
	}

	oldSnapshot := snapshotWithCommand(oldCommand)
	newSnapshot := snapshotWithCommand(newCommand)

	s.env.OnActivity("UpdateWorkflowRunStatus", mock.Anything, mock.Anything).Return(nil)
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("WriteExecutionLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("InitRunLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("AppendRunLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("LoadPredecessorInputs", mock.Anything, mock.Anything).Return(map[string]string{}, nil).Maybe()
	s.env.OnActivity("LoadRequiredInputArtifacts", mock.Anything, mock.Anything).Return(activity.LoadRequiredInputArtifactsResult{}, nil).Maybe()
	s.env.OnActivity("FetchPodLogs", mock.Anything, mock.Anything).Return("", nil).Maybe()
	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execA2
	})).Return("passed", nil).Once()
	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.Anything).Return("", nil).Maybe()

	// First GetGraphRuntime → old snapshot (workflow start)
	// Second GetGraphRuntime → new snapshot (retry refresh)
	s.env.OnActivity("GetGraphRuntime", mock.Anything, runID).Return(oldSnapshot, nil).Once()
	s.env.OnActivity("GetGraphRuntime", mock.Anything, runID).Return(newSnapshot, nil).Once()

	// First execution created
	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == nodeA && p.Iteration == 1
	})).Return(execA, nil).Once()

	// First execution fails — activity returns a zero CallbackResult and an error
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.TemplateNodeID == nodeA && p.Command == oldCommand
	})).Return(activity.CallbackResult{}, fmt.Errorf("script failed (exit 127)")).Once()

	// Retry execution created
	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == nodeA && p.Iteration == 2
	})).Return(execA2, nil).Once()

	// Retry execution succeeds with the NEW command
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.TemplateNodeID == nodeA && p.Command == newCommand
	})).Return(activity.CallbackResult{}, nil).Once()

	// Send retry signal after the first execution fails
	s.env.RegisterDelayedCallback(func() {
		s.env.SignalWorkflow(SignalRetryNode, RetryNodeSignal{
			TemplateNodeID: nodeA.String(),
		})
	}, 1*time.Second)

	s.env.ExecuteWorkflow(DAGExecutorWorkflow, DAGExecutorParams{
		RunID: runID, GraphVersion: 1,
	})

	s.True(s.env.IsWorkflowCompleted())
	s.NoError(s.env.GetWorkflowError())
}

// TestModelEffortResolution_RetryNodeSignal_PreservesReviewPassAcrossRetry
// covers Decision 2 / Part 2 step 7(e) (first half) of the accompanying spec:
// an operator-triggered retry of a failed node's first review pass (via
// SignalRetryNode) advances the DB-facing `iteration` counter (so the new
// node_execution row gets a fresh, unique value) but must NOT advance
// `reviewPass` — the retried execution still resolves the first-iteration
// model/effort, not the subsequent-iteration configuration.
func (s *DAGExecutorTestSuite) TestModelEffortResolution_RetryNodeSignal_PreservesReviewPassAcrossRetry() {
	nodeA := uuid.New()
	execA1 := uuid.New() // first execution (will fail)
	execA2 := uuid.New() // retry execution

	runID := uuid.New()

	snapshot := `{
		"nodes": [
			{"templateNodeId": "` + nodeA.String() + `", "label": "A", "executorType": "ai", "timeoutSeconds": 1800, "isEntrypoint": true, "model": "static-model", "configOverrides": {"effort": "static-effort", "model_first_iteration": "opus-x", "effort_first_iteration": "xhigh", "model_subsequent_iteration": "sonnet-y", "effort_subsequent_iteration": "high"}}
		],
		"edges": []
	}`

	s.env.OnActivity("UpdateWorkflowRunStatus", mock.Anything, mock.Anything).Return(nil)
	s.env.OnActivity("GetGraphRuntime", mock.Anything, runID).Return(snapshot, nil).Maybe()
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("WriteExecutionLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("InitRunLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("AppendRunLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("LoadPredecessorInputs", mock.Anything, mock.Anything).Return(map[string]string{}, nil).Maybe()
	s.env.OnActivity("LoadRequiredInputArtifacts", mock.Anything, mock.Anything).Return(activity.LoadRequiredInputArtifactsResult{}, nil).Maybe()
	s.env.OnActivity("LoadReviewHistoryJSON", mock.Anything, mock.Anything).Return("[]", nil).Maybe()
	s.env.OnActivity("FetchPodLogs", mock.Anything, mock.Anything).Return("", nil).Maybe()
	s.env.OnActivity("DeleteAgentJob", mock.Anything, mock.Anything).Return(nil).Maybe()

	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == nodeA && p.Iteration == 1
	})).Return(execA1, nil).Once()
	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == nodeA && p.Iteration == 2
	})).Return(execA2, nil).Once()

	// First attempt (reviewPass == 1, iteration 1): resolves the first-iteration
	// config, then fails, sending the node into the awaiting_retry state.
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.NodeExecutionID == execA1 && p.Iteration == 1 && p.Model == "opus-x" && p.Effort == "xhigh"
	})).Return(activity.CallbackResult{}, fmt.Errorf("boom")).Once()

	// Retried attempt (iteration 2, but reviewPass STILL == 1 — this is an infra
	// retry, not a review decision): must still resolve the first-iteration
	// config, not the subsequent-iteration one.
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.NodeExecutionID == execA2 && p.Iteration == 2 && p.Model == "opus-x" && p.Effort == "xhigh"
	})).Return(activity.CallbackResult{}, nil).Once()

	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execA2
	})).Return("no_decision", nil).Once()

	s.env.RegisterDelayedCallback(func() {
		s.env.SignalWorkflow(SignalRetryNode, RetryNodeSignal{
			TemplateNodeID: nodeA.String(),
		})
	}, time.Millisecond*10)

	s.env.ExecuteWorkflow(DAGExecutorWorkflow, DAGExecutorParams{
		RunID: runID, GraphVersion: 1,
	})

	s.True(s.env.IsWorkflowCompleted())
	s.NoError(s.env.GetWorkflowError())
}

// TestModelEffortResolution_PauseHeartbeatTimeoutRecovery_PreservesReviewPass
// covers Decision 2 / Part 2 step 7(e) (second half): a heartbeat timeout that
// fires after a deliberate pause is treated as an infra-retry re-queue (see
// dag_executor.go's pauseInterrupted handling), not a failure. The re-queued
// execution's `iteration` advances (fresh DB row, same as any retry) but
// `reviewPass` must not — the re-queued attempt still resolves the
// first-iteration model/effort.
func (s *DAGExecutorTestSuite) TestModelEffortResolution_PauseHeartbeatTimeoutRecovery_PreservesReviewPass() {
	nodeA := uuid.New()
	execA := uuid.New()
	execA2 := uuid.New()
	runID := uuid.New()

	snapshot := `{
		"nodes": [
			{"templateNodeId": "` + nodeA.String() + `", "label": "A", "executorType": "ai", "timeoutSeconds": 1800, "isEntrypoint": true, "model": "static-model", "configOverrides": {"effort": "static-effort", "model_first_iteration": "opus-x", "effort_first_iteration": "xhigh", "model_subsequent_iteration": "sonnet-y", "effort_subsequent_iteration": "high"}}
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

	// First execution: resolves the first-iteration config, then "times out"
	// (simulated heartbeat timeout) after the pause/resume cycle below.
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.NodeExecutionID == execA
	})).After(500*time.Millisecond).Return(activity.CallbackResult{}, fmt.Errorf("heartbeat timeout"))

	// Re-queued execution (iteration 2, reviewPass STILL 1): must still resolve
	// the first-iteration config, not the subsequent-iteration one.
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.MatchedBy(func(p activity.ExecuteAINodeFromSnapshotParams) bool {
		return p.NodeExecutionID == execA2 && p.Model == "opus-x" && p.Effort == "xhigh"
	})).Return(activity.CallbackResult{}, nil)

	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.MatchedBy(func(p activity.UpdateNodeExecStatusParams) bool {
		return p.NodeExecutionID == execA && p.Status == "paused"
	})).Return(nil).Once()
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.MatchedBy(func(p activity.UpdateNodeExecStatusParams) bool {
		return p.NodeExecutionID == execA && p.Status == "invalidated"
	})).Return(nil).Once()
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.MatchedBy(func(p activity.UpdateNodeExecStatusParams) bool {
		return p.NodeExecutionID == execA2 && p.Status == "completed"
	})).Return(nil).Once()

	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == execA2
	})).Return("no_decision", nil).Once()

	// Pause at 50 ms, resume at 100 ms (well before the 500 ms timeout) — same
	// timing as TestResumeBeforeHeartbeatTimeout.
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
