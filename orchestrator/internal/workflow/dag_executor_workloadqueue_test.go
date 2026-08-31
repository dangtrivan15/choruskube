package workflow

import (
	"context"
	"errors"

	"github.com/google/uuid"
	"github.com/stretchr/testify/mock"
	temporalactivity "go.temporal.io/sdk/activity"
	"go.temporal.io/sdk/converter"

	"github.com/dangtrivan15/choruskube/orchestrator/internal/activity"
)

// TestWorkloadActivitiesUseWorkerTaskQueue pins every workload activity to the run's queue,
// not just the launch. The three are one unit — they address the same agent pod through the
// same executor — so a Worker that serves the launch but not the teardown or the log fetch
// is a boundary only two thirds proven.
func (s *DAGExecutorTestSuite) TestWorkloadActivitiesUseWorkerTaskQueue() {
	nodeA := uuid.New()
	execA := uuid.New()
	runID := uuid.New()

	snapshot := `{
		"nodes": [
			{"templateNodeId": "` + nodeA.String() + `", "label": "AI", "executorType": "ai", "timeoutSeconds": 1800, "isEntrypoint": true}
		],
		"edges": []
	}`

	seenQueues := map[string]string{}
	s.env.SetOnActivityStartedListener(func(info *temporalactivity.Info, _ context.Context, _ converter.EncodedValues) {
		seenQueues[info.ActivityType.Name] = info.TaskQueue
	})

	s.env.OnActivity("UpdateWorkflowRunStatus", mock.Anything, mock.Anything).Return(nil).Maybe()
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

	// A failed agent step is what drives the log fetch and the teardown on the same path.
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.Anything).
		Return(activity.CallbackResult{}, errors.New("agent failed")).Once()
	s.env.OnActivity("FetchPodLogs", mock.Anything, mock.Anything).Return("boom", nil).Once()
	s.env.OnActivity("DeleteAgentJob", mock.Anything, mock.Anything).Return(nil).Once()

	s.env.ExecuteWorkflow(DAGExecutorWorkflow, DAGExecutorParams{
		RunID:           runID,
		GraphVersion:    1,
		WorkerTaskQueue: "fleet-acme",
	})

	s.True(s.env.IsWorkflowCompleted())
	s.Equal("fleet-acme", seenQueues["ExecuteAINodeFromSnapshot"])
	s.Equal("fleet-acme", seenQueues["FetchPodLogs"])
	s.Equal("fleet-acme", seenQueues["DeleteAgentJob"])
	// App-data activities must NOT follow: they read and write the API server's own tables
	// and stay wherever the workflow itself is served.
	s.Equal("default-test-taskqueue", seenQueues["UpdateNodeExecutionStatus"])
}

// TestWorkloadActivitiesFallBackWhenNoWorkerTaskQueue is the in-flight-run case: an empty
// queue is what a run started before WorkerTaskQueue existed recomputes on replay, and
// Temporal reads it as the workflow's own queue.
func (s *DAGExecutorTestSuite) TestWorkloadActivitiesFallBackWhenNoWorkerTaskQueue() {
	nodeA := uuid.New()
	execA := uuid.New()
	runID := uuid.New()

	snapshot := `{
		"nodes": [
			{"templateNodeId": "` + nodeA.String() + `", "label": "AI", "executorType": "ai", "timeoutSeconds": 1800, "isEntrypoint": true}
		],
		"edges": []
	}`

	seenQueues := map[string]string{}
	s.env.SetOnActivityStartedListener(func(info *temporalactivity.Info, _ context.Context, _ converter.EncodedValues) {
		seenQueues[info.ActivityType.Name] = info.TaskQueue
	})

	s.env.OnActivity("UpdateWorkflowRunStatus", mock.Anything, mock.Anything).Return(nil).Maybe()
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

	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.Anything).
		Return(activity.CallbackResult{}, errors.New("agent failed")).Once()
	s.env.OnActivity("FetchPodLogs", mock.Anything, mock.Anything).Return("boom", nil).Once()
	s.env.OnActivity("DeleteAgentJob", mock.Anything, mock.Anything).Return(nil).Once()

	s.env.ExecuteWorkflow(DAGExecutorWorkflow, DAGExecutorParams{
		RunID:        runID,
		GraphVersion: 1,
	})

	s.True(s.env.IsWorkflowCompleted())
	s.Equal("default-test-taskqueue", seenQueues["FetchPodLogs"])
	s.Equal("default-test-taskqueue", seenQueues["DeleteAgentJob"])
}
