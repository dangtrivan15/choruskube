package workflow

import (
	"testing"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"

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
