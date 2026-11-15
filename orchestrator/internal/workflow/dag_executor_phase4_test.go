package workflow

import (
	"sync"
	"testing"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"

	"github.com/dangtrivan15/choruskube/orchestrator/internal/activity"
	"github.com/dangtrivan15/choruskube/orchestrator/internal/state"
)

func TestBuildPromptVariables_IncludesRunInputs(t *testing.T) {
	snap := &state.GraphRuntimeSnapshot{
		Nodes: []state.SnapshotNode{
			{TemplateNodeID: uuid.New(), Label: "test_node"},
		},
		Inputs: map[string]interface{}{
			"repo_url":     "https://github.com/foo/bar",
			"test_command": "npm test",
			"agent_image":  "my-image:latest",
		},
	}
	nodeID := snap.Nodes[0].TemplateNodeID
	vars := buildPromptVariables(snap, nil, uuid.New(), nodeID, 1)

	assert.Equal(t, "https://github.com/foo/bar", vars["run.repo_url"])
	assert.Equal(t, "npm test", vars["run.test_command"])
	assert.Equal(t, "my-image:latest", vars["run.agent_image"])
	assert.Equal(t, "test_node", vars["node.label"])
}

func TestBuildPromptVariables_NoInputs(t *testing.T) {
	snap := &state.GraphRuntimeSnapshot{
		Nodes: []state.SnapshotNode{
			{TemplateNodeID: uuid.New(), Label: "node"},
		},
	}
	nodeID := snap.Nodes[0].TemplateNodeID
	vars := buildPromptVariables(snap, nil, uuid.New(), nodeID, 1)

	assert.NotContains(t, vars, "run.repo_url")
	assert.Contains(t, vars, "run.id")
}

// dispatchedRepos runs a one-AI-node run whose snapshot carries snapshotTail (further top-level
// members, comma-led) and returns the Repos the node was dispatched with, as the Worker decodes
// them off the Temporal payload.
func (s *DAGExecutorTestSuite) dispatchedRepos(snapshotTail string) activity.Repos {
	nodeA := uuid.New()
	execA := uuid.New()
	runID := s.dagRunID()

	snapshot := `{
		"nodes": [
			{"templateNodeId": "` + nodeA.String() + `", "label": "test", "executorType": "ai", "timeoutSeconds": 1800, "isEntrypoint": true}
		],
		"edges": []` + snapshotTail + `
	}`

	s.env.OnActivity("UpdateWorkflowRunStatus", mock.Anything, mock.Anything).Return(nil)
	s.env.OnActivity("GetGraphRuntime", mock.Anything, runID).Return(snapshot, nil)
	s.env.OnActivity("WriteExecutionLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("InitRunLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("AppendRunLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.Anything).Return(execA, nil).Once()
	s.env.OnActivity("LoadPredecessorInputs", mock.Anything, mock.Anything).Return(map[string]string{}, nil).Maybe()
	s.env.OnActivity("LoadRequiredInputArtifacts", mock.Anything, mock.Anything).
		Return(activity.LoadRequiredInputArtifactsResult{}, nil).Maybe()
	s.env.OnActivity("LoadReviewHistoryJSON", mock.Anything, mock.Anything).Return("[]", nil).Maybe()
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.Anything).Return("no_decision", nil).Maybe()
	s.env.OnActivity("SetTraversedEdges", mock.Anything, mock.Anything).Return(nil).Maybe()

	var mu sync.Mutex
	var got activity.Repos
	s.env.OnActivity("ExecuteAINodeFromSnapshot", mock.Anything, mock.Anything).
		Run(func(args mock.Arguments) {
			mu.Lock()
			defer mu.Unlock()
			got = args.Get(1).(activity.ExecuteAINodeFromSnapshotParams).Repos
		}).
		Return(activity.CallbackResult{}, nil).Once()

	s.env.ExecuteWorkflow(DAGExecutorWorkflow, DAGExecutorParams{
		RunID: runID, GraphVersion: 1, WorkerTaskQueue: testWorkerQueue,
	})
	s.True(s.env.IsWorkflowCompleted())
	s.NoError(s.env.GetWorkflowError())

	mu.Lock()
	defer mu.Unlock()
	return got
}

// TestRepoMapsCarryDependsOn: the snapshot's per-repo dependsOn reaches the repo's map as
// depends_on, and a repo with none gets no key at all.
func (s *DAGExecutorTestSuite) TestRepoMapsCarryDependsOn() {
	repos := s.dispatchedRepos(`,
		"repos": [
			{"id": "11111111-1111-1111-1111-111111111111", "url": "https://example.invalid/acme/x.git", "name": "x", "dependsOn": ["y"]},
			{"id": "22222222-2222-2222-2222-222222222222", "url": "https://example.invalid/acme/y.git", "name": "y", "dependsOn": []}
		]`)

	s.Require().Len(repos.List, 2)
	s.Equal("x", repos.List[0]["name"])
	s.Equal([]any{"y"}, repos.List[0]["depends_on"])
	s.Equal("y", repos.List[1]["name"])
	s.NotContains(repos.List[1], "depends_on")
	s.False(repos.TestAffectedOnly, "a snapshot without testAffectedOnly dispatches the switch off")
}

// TestReposCarryTestAffectedOnly: the snapshot's top-level testAffectedOnly reaches the node's
// activity parameters.
func (s *DAGExecutorTestSuite) TestReposCarryTestAffectedOnly() {
	repos := s.dispatchedRepos(`,
		"repos": [
			{"id": "11111111-1111-1111-1111-111111111111", "url": "https://example.invalid/acme/x.git", "name": "x"}
		],
		"testAffectedOnly": true`)

	s.Require().Len(repos.List, 1)
	s.True(repos.TestAffectedOnly)
}
