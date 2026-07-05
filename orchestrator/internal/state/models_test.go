package state

import (
	"encoding/json"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestGraphRuntimeSnapshot_Deserialize(t *testing.T) {
	raw := `{
		"nodes": [{
			"templateNodeId": "11111111-1111-1111-1111-111111111111",
			"label": "test",
			"executorType": "ai",
			"timeoutSeconds": 300,
			"configOverrides": {"loop_group": "review"},
			"isEntrypoint": true
		}],
		"edges": [{
			"templateEdgeId": "22222222-2222-2222-2222-222222222222",
			"sourceNodeId": "11111111-1111-1111-1111-111111111111",
			"targetNodeId": "33333333-3333-3333-3333-333333333333",
			"condition": "approved"
		}],
		"inputs": {"repo_url": "https://github.com/test/repo"}
	}`

	var snap GraphRuntimeSnapshot
	err := json.Unmarshal([]byte(raw), &snap)
	require.NoError(t, err)
	assert.Len(t, snap.Nodes, 1)
	assert.Equal(t, "ai", snap.Nodes[0].ExecutorType)
	assert.True(t, snap.Nodes[0].IsEntrypoint)
	assert.Equal(t, "review", snap.Nodes[0].ConfigOverrides["loop_group"])
	assert.Len(t, snap.Edges, 1)
	assert.Equal(t, "approved", *snap.Edges[0].Condition)
}

func TestSnapshotNode_OutputSpec_RoundTrip(t *testing.T) {
	outputSpec := `{"files":[{"name":"report.pdf","required":true},{"name":"summary.txt","required":false}]}`
	// The API server serializes outputSpec as a JSON string (Jackson-encoded String field),
	// so the orchestrator receives it as a quoted, escaped JSON string value.
	// Use json.Marshal to produce a properly escaped JSON string literal.
	outputSpecEncoded, err := json.Marshal(outputSpec)
	require.NoError(t, err)
	raw := `{
		"nodes": [{
			"templateNodeId": "11111111-1111-1111-1111-111111111111",
			"label": "generate-report",
			"executorType": "ai",
			"timeoutSeconds": 600,
			"configOverrides": {},
			"isEntrypoint": true,
			"outputSpec": ` + string(outputSpecEncoded) + `
		}],
		"edges": [],
		"inputs": {}
	}`

	var snap GraphRuntimeSnapshot
	err = json.Unmarshal([]byte(raw), &snap)
	require.NoError(t, err)
	require.Len(t, snap.Nodes, 1)
	assert.Equal(t, outputSpec, snap.Nodes[0].OutputSpec, "OutputSpec should round-trip through JSON deserialization")
}

func TestSnapshotNode_OutputSpec_AbsentIsEmpty(t *testing.T) {
	raw := `{
		"nodes": [{
			"templateNodeId": "11111111-1111-1111-1111-111111111111",
			"label": "simple-node",
			"executorType": "ai",
			"timeoutSeconds": 300,
			"configOverrides": {},
			"isEntrypoint": true
		}],
		"edges": [],
		"inputs": {}
	}`

	var snap GraphRuntimeSnapshot
	err := json.Unmarshal([]byte(raw), &snap)
	require.NoError(t, err)
	require.Len(t, snap.Nodes, 1)
	assert.Equal(t, "", snap.Nodes[0].OutputSpec, "OutputSpec should be empty string when absent from JSON")
}
