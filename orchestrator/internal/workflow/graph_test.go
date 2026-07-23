package workflow

import (
	"testing"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/dangtrivan15/choruskube/orchestrator/internal/state"
)

func makeNodeID() uuid.UUID   { return uuid.New() }
func strPtr(s string) *string { return &s }

func TestParseSnapshot(t *testing.T) {
	nodeID := uuid.New()
	json := `{"nodes":[{"templateNodeId":"` + nodeID.String() + `","label":"Test","executorType":"ai","timeoutSeconds":1800}],"edges":[]}`

	snap, err := ParseSnapshot(json)
	require.NoError(t, err)
	assert.Len(t, snap.Nodes, 1)
	assert.Equal(t, "Test", snap.Nodes[0].Label)
	assert.Len(t, snap.Edges, 0)
}

func TestFindEntryNodes(t *testing.T) {
	nodeA := makeNodeID()
	nodeB := makeNodeID()
	nodeC := makeNodeID()

	snap := &state.GraphRuntimeSnapshot{
		Nodes: []state.SnapshotNode{
			{TemplateNodeID: nodeA, Label: "A", IsEntrypoint: true},
			{TemplateNodeID: nodeB, Label: "B"},
			{TemplateNodeID: nodeC, Label: "C"},
		},
		Edges: []state.SnapshotEdge{
			{SourceNodeID: nodeA, TargetNodeID: nodeB},
			{SourceNodeID: nodeA, TargetNodeID: nodeC},
		},
	}

	entries := FindEntryNodes(snap)
	assert.Len(t, entries, 1)
	assert.Equal(t, nodeA, entries[0].TemplateNodeID)
}

func TestFindEntryNodes_NoEntrypoints(t *testing.T) {
	nodeA := makeNodeID()
	nodeB := makeNodeID()

	snap := &state.GraphRuntimeSnapshot{
		Nodes: []state.SnapshotNode{
			{TemplateNodeID: nodeA, Label: "A"},
			{TemplateNodeID: nodeB, Label: "B"},
		},
	}

	entries := FindEntryNodes(snap)
	assert.Len(t, entries, 0)
}

// --- GetPredecessorNodeIDs and FindReadyNodes ---

func TestGetPredecessorNodeIDs(t *testing.T) {
	nodeA := makeNodeID()
	nodeB := makeNodeID()
	nodeC := makeNodeID()

	snap := &state.GraphRuntimeSnapshot{
		Edges: []state.SnapshotEdge{
			{SourceNodeID: nodeA, TargetNodeID: nodeC},
			{SourceNodeID: nodeB, TargetNodeID: nodeC},
		},
	}

	preds := GetPredecessorNodeIDs(snap, nodeC)
	assert.Len(t, preds, 2)
	assert.Contains(t, preds, nodeA)
	assert.Contains(t, preds, nodeB)
}

func TestFindReadyNodes_AllPredecessorsCompleted(t *testing.T) {
	nodeA := makeNodeID()
	nodeB := makeNodeID()
	nodeC := makeNodeID()

	snap := &state.GraphRuntimeSnapshot{
		Nodes: []state.SnapshotNode{
			{TemplateNodeID: nodeA, Label: "A"},
			{TemplateNodeID: nodeB, Label: "B"},
			{TemplateNodeID: nodeC, Label: "C"},
		},
		Edges: []state.SnapshotEdge{
			{SourceNodeID: nodeA, TargetNodeID: nodeC},
			{SourceNodeID: nodeB, TargetNodeID: nodeC},
		},
	}

	nodeStates := map[uuid.UUID]string{
		nodeA: "completed",
		nodeB: "completed",
		nodeC: "pending",
	}

	ready := FindReadyNodes(snap, nodeStates)
	assert.Len(t, ready, 1)
	assert.Equal(t, nodeC, ready[0].TemplateNodeID)
}

func TestFindReadyNodes_NotAllPredecessorsCompleted(t *testing.T) {
	nodeA := makeNodeID()
	nodeB := makeNodeID()
	nodeC := makeNodeID()

	snap := &state.GraphRuntimeSnapshot{
		Nodes: []state.SnapshotNode{
			{TemplateNodeID: nodeA, Label: "A"},
			{TemplateNodeID: nodeB, Label: "B"},
			{TemplateNodeID: nodeC, Label: "C"},
		},
		Edges: []state.SnapshotEdge{
			{SourceNodeID: nodeA, TargetNodeID: nodeC},
			{SourceNodeID: nodeB, TargetNodeID: nodeC},
		},
	}

	nodeStates := map[uuid.UUID]string{
		nodeA: "completed",
		nodeB: "running",
		nodeC: "pending",
	}

	ready := FindReadyNodes(snap, nodeStates)
	assert.Len(t, ready, 0)
}

func TestFindReadyNodes_OnlyPendingNodesReturned(t *testing.T) {
	nodeA := makeNodeID()
	nodeB := makeNodeID()

	snap := &state.GraphRuntimeSnapshot{
		Nodes: []state.SnapshotNode{
			{TemplateNodeID: nodeA, Label: "A"},
			{TemplateNodeID: nodeB, Label: "B"},
		},
		Edges: []state.SnapshotEdge{
			{SourceNodeID: nodeA, TargetNodeID: nodeB},
		},
	}

	nodeStates := map[uuid.UUID]string{
		nodeA: "running",
		nodeB: "pending",
	}

	ready := FindReadyNodes(snap, nodeStates)
	assert.Len(t, ready, 0)
}

func TestFindReadyNodes_SelfLoopDoesNotBlock(t *testing.T) {
	// Regression: v23 self-iterating reviews (spec_review / code_review) have a
	// `revised` self-loop. When the node is freshly activated as pending, it must
	// not gate on its own pending status.
	pred := makeNodeID()
	node := makeNodeID()

	snap := &state.GraphRuntimeSnapshot{
		Nodes: []state.SnapshotNode{
			{TemplateNodeID: pred, Label: "pred"},
			{TemplateNodeID: node, Label: "loopy"},
		},
		Edges: []state.SnapshotEdge{
			{SourceNodeID: pred, TargetNodeID: node},
			{SourceNodeID: node, TargetNodeID: node, Condition: strPtr("revised")},
		},
	}

	nodeStates := map[uuid.UUID]string{
		pred: "completed",
		node: "pending",
	}

	ready := FindReadyNodes(snap, nodeStates)
	assert.Len(t, ready, 1)
	assert.Equal(t, node, ready[0].TemplateNodeID)
}

func TestFindReadyNodes_SelfLoopStillGatedByOtherPredecessor(t *testing.T) {
	// Self-edges are ignored but real predecessors still gate readiness.
	predA := makeNodeID()
	predB := makeNodeID()
	node := makeNodeID()

	snap := &state.GraphRuntimeSnapshot{
		Nodes: []state.SnapshotNode{
			{TemplateNodeID: predA, Label: "A"},
			{TemplateNodeID: predB, Label: "B"},
			{TemplateNodeID: node, Label: "loopy"},
		},
		Edges: []state.SnapshotEdge{
			{SourceNodeID: predA, TargetNodeID: node},
			{SourceNodeID: predB, TargetNodeID: node},
			{SourceNodeID: node, TargetNodeID: node, Condition: strPtr("revised")},
		},
	}

	nodeStates := map[uuid.UUID]string{
		predA: "completed",
		predB: "running",
		node:  "pending",
	}

	ready := FindReadyNodes(snap, nodeStates)
	assert.Len(t, ready, 0)
}

// --- EvaluateEdges ---

func TestEvaluateEdges_UnconditionalAlwaysFires(t *testing.T) {
	nodeA := makeNodeID()
	nodeB := makeNodeID()

	snap := &state.GraphRuntimeSnapshot{
		Edges: []state.SnapshotEdge{
			{SourceNodeID: nodeA, TargetNodeID: nodeB, Condition: nil},
		},
	}

	targets, firedEdgeIDs, err := EvaluateEdges(snap, nodeA, "anything")
	require.NoError(t, err)
	assert.Equal(t, []uuid.UUID{nodeB}, targets)
	assert.Equal(t, []uuid.UUID{snap.Edges[0].TemplateEdgeID}, firedEdgeIDs)
}

func TestEvaluateEdges_ConditionalMatchesCaseInsensitive(t *testing.T) {
	nodeA := makeNodeID()
	nodeB := makeNodeID()
	nodeC := makeNodeID()

	edgeAB := uuid.New()
	snap := &state.GraphRuntimeSnapshot{
		Edges: []state.SnapshotEdge{
			{TemplateEdgeID: edgeAB, SourceNodeID: nodeA, TargetNodeID: nodeB, Condition: strPtr("approved")},
			{TemplateEdgeID: uuid.New(), SourceNodeID: nodeA, TargetNodeID: nodeC, Condition: strPtr("rejected")},
		},
	}

	targets, firedEdgeIDs, err := EvaluateEdges(snap, nodeA, "Approved")
	require.NoError(t, err)
	assert.Equal(t, []uuid.UUID{nodeB}, targets)
	assert.Equal(t, []uuid.UUID{edgeAB}, firedEdgeIDs)
}

func TestEvaluateEdges_MixedConditionalAndUnconditional(t *testing.T) {
	nodeA := makeNodeID()
	nodeB := makeNodeID()
	nodeC := makeNodeID()
	nodeLog := makeNodeID()

	edgeAB := uuid.New()
	edgeALog := uuid.New()
	snap := &state.GraphRuntimeSnapshot{
		Edges: []state.SnapshotEdge{
			{TemplateEdgeID: edgeAB, SourceNodeID: nodeA, TargetNodeID: nodeB, Condition: strPtr("approved")},
			{TemplateEdgeID: uuid.New(), SourceNodeID: nodeA, TargetNodeID: nodeC, Condition: strPtr("rejected")},
			{TemplateEdgeID: edgeALog, SourceNodeID: nodeA, TargetNodeID: nodeLog, Condition: nil},
		},
	}

	targets, firedEdgeIDs, err := EvaluateEdges(snap, nodeA, "approved")
	require.NoError(t, err)
	assert.Len(t, targets, 2)
	assert.Contains(t, targets, nodeB)
	assert.Contains(t, targets, nodeLog)
	assert.Len(t, firedEdgeIDs, 2)
	assert.Contains(t, firedEdgeIDs, edgeAB)
	assert.Contains(t, firedEdgeIDs, edgeALog)
}

func TestEvaluateEdges_NoMatchError(t *testing.T) {
	nodeA := makeNodeID()
	nodeB := makeNodeID()

	snap := &state.GraphRuntimeSnapshot{
		Edges: []state.SnapshotEdge{
			{SourceNodeID: nodeA, TargetNodeID: nodeB, Condition: strPtr("approved")},
		},
	}

	_, _, err := EvaluateEdges(snap, nodeA, "needs_revision")
	require.Error(t, err)
	assert.Contains(t, err.Error(), "no matching edge for result: needs_revision")
}

func TestEvaluateEdges_NoEdgesIsTerminal(t *testing.T) {
	nodeA := makeNodeID()

	snap := &state.GraphRuntimeSnapshot{
		Edges: []state.SnapshotEdge{},
	}

	targets, firedEdgeIDs, err := EvaluateEdges(snap, nodeA, "completed")
	require.NoError(t, err)
	assert.Empty(t, targets)
	assert.Empty(t, firedEdgeIDs)
}

func TestEvaluateEdges_TerminalDecisionMatches(t *testing.T) {
	humanGate := makeNodeID()
	rejectedTarget := makeNodeID()

	snap := &state.GraphRuntimeSnapshot{
		Nodes: []state.SnapshotNode{
			{
				TemplateNodeID: humanGate,
				Label:          "roadmap_human_gate",
				ConfigOverrides: map[string]interface{}{
					"terminal_decisions": []interface{}{"approved"},
				},
			},
		},
		Edges: []state.SnapshotEdge{
			{SourceNodeID: humanGate, TargetNodeID: rejectedTarget, Condition: strPtr("rejected")},
		},
	}

	targets, firedEdgeIDs, err := EvaluateEdges(snap, humanGate, "approved")
	require.NoError(t, err)
	assert.Empty(t, targets)
	assert.Empty(t, firedEdgeIDs)
}

func TestEvaluateEdges_TerminalDecisionMatchesCaseInsensitive(t *testing.T) {
	humanGate := makeNodeID()
	rejectedTarget := makeNodeID()

	snap := &state.GraphRuntimeSnapshot{
		Nodes: []state.SnapshotNode{
			{
				TemplateNodeID: humanGate,
				Label:          "roadmap_human_gate",
				ConfigOverrides: map[string]interface{}{
					"terminal_decisions": []interface{}{"Approved"},
				},
			},
		},
		Edges: []state.SnapshotEdge{
			{SourceNodeID: humanGate, TargetNodeID: rejectedTarget, Condition: strPtr("rejected")},
		},
	}

	targets, firedEdgeIDs, err := EvaluateEdges(snap, humanGate, "approved")
	require.NoError(t, err)
	assert.Empty(t, targets)
	assert.Empty(t, firedEdgeIDs)
}

func TestEvaluateEdges_TerminalDecisionNoMatchStillErrors(t *testing.T) {
	humanGate := makeNodeID()
	rejectedTarget := makeNodeID()

	snap := &state.GraphRuntimeSnapshot{
		Nodes: []state.SnapshotNode{
			{
				TemplateNodeID: humanGate,
				Label:          "roadmap_human_gate",
				ConfigOverrides: map[string]interface{}{
					"terminal_decisions": []interface{}{"approved"},
				},
			},
		},
		Edges: []state.SnapshotEdge{
			{SourceNodeID: humanGate, TargetNodeID: rejectedTarget, Condition: strPtr("rejected")},
		},
	}

	_, _, err := EvaluateEdges(snap, humanGate, "gibberish")
	require.Error(t, err)
	assert.Contains(t, err.Error(), "no matching edge for result: gibberish")
}

func TestEvaluateEdges_NoTerminalDecisionsKeyUnchanged(t *testing.T) {
	humanGate := makeNodeID()
	rejectedTarget := makeNodeID()

	snap := &state.GraphRuntimeSnapshot{
		Nodes: []state.SnapshotNode{
			{TemplateNodeID: humanGate, Label: "roadmap_human_gate"},
		},
		Edges: []state.SnapshotEdge{
			{SourceNodeID: humanGate, TargetNodeID: rejectedTarget, Condition: strPtr("rejected")},
		},
	}

	_, _, err := EvaluateEdges(snap, humanGate, "approved")
	require.Error(t, err)
	assert.Contains(t, err.Error(), "no matching edge for result: approved")
}

// --- GetNodeByID ---

func TestGetNodeByID(t *testing.T) {
	nodeA := makeNodeID()
	nodeB := makeNodeID()

	snap := &state.GraphRuntimeSnapshot{
		Nodes: []state.SnapshotNode{
			{TemplateNodeID: nodeA, Label: "A"},
			{TemplateNodeID: nodeB, Label: "B"},
		},
	}

	node, ok := GetNodeByID(snap, nodeA)
	assert.True(t, ok)
	assert.Equal(t, "A", node.Label)

	_, ok = GetNodeByID(snap, uuid.New())
	assert.False(t, ok)
}

// --- HasConditionalEdges ---

func TestHasConditionalEdges_WithConditional(t *testing.T) {
	nodeA := makeNodeID()
	nodeB := makeNodeID()
	nodeC := makeNodeID()

	snap := &state.GraphRuntimeSnapshot{
		Edges: []state.SnapshotEdge{
			{SourceNodeID: nodeA, TargetNodeID: nodeB, Condition: strPtr("approved")},
			{SourceNodeID: nodeA, TargetNodeID: nodeC, Condition: strPtr("rejected")},
		},
	}

	assert.True(t, HasConditionalEdges(snap, nodeA))
}

func TestHasConditionalEdges_OnlyUnconditional(t *testing.T) {
	nodeA := makeNodeID()
	nodeB := makeNodeID()

	snap := &state.GraphRuntimeSnapshot{
		Edges: []state.SnapshotEdge{
			{SourceNodeID: nodeA, TargetNodeID: nodeB, Condition: nil},
		},
	}

	assert.False(t, HasConditionalEdges(snap, nodeA))
}

func TestHasConditionalEdges_NoEdges(t *testing.T) {
	nodeA := makeNodeID()

	snap := &state.GraphRuntimeSnapshot{
		Edges: []state.SnapshotEdge{},
	}

	assert.False(t, HasConditionalEdges(snap, nodeA))
}

func TestHasConditionalEdges_MixedEdges(t *testing.T) {
	nodeA := makeNodeID()
	nodeB := makeNodeID()
	nodeC := makeNodeID()

	snap := &state.GraphRuntimeSnapshot{
		Edges: []state.SnapshotEdge{
			{SourceNodeID: nodeA, TargetNodeID: nodeB, Condition: strPtr("approved")},
			{SourceNodeID: nodeA, TargetNodeID: nodeC, Condition: nil},
		},
	}

	assert.True(t, HasConditionalEdges(snap, nodeA))
}

// --- Implement Node Test Bypass Routing ---

func TestEvaluateEdges_ImplementBypassRouting(t *testing.T) {
	implement := makeNodeID()
	test := makeNodeID()
	humanBypass := makeNodeID()
	codeReview := makeNodeID()

	snap := &state.GraphRuntimeSnapshot{
		Edges: []state.SnapshotEdge{
			{SourceNodeID: implement, TargetNodeID: test, Condition: strPtr("test")},
			{SourceNodeID: implement, TargetNodeID: humanBypass, Condition: strPtr("request_test_bypass")},
			{SourceNodeID: humanBypass, TargetNodeID: codeReview, Condition: strPtr("approved")},
			{SourceNodeID: humanBypass, TargetNodeID: test, Condition: strPtr("rejected")},
		},
	}

	// Normal path: "test" routes to Test node
	targets, _, err := EvaluateEdges(snap, implement, "test")
	require.NoError(t, err)
	assert.Equal(t, []uuid.UUID{test}, targets)

	// Bypass path: "request_test_bypass" routes to Test Bypass
	targets, _, err = EvaluateEdges(snap, implement, "request_test_bypass")
	require.NoError(t, err)
	assert.Equal(t, []uuid.UUID{humanBypass}, targets)

	// Human approves bypass: routes to Code Review
	targets, _, err = EvaluateEdges(snap, humanBypass, "approved")
	require.NoError(t, err)
	assert.Equal(t, []uuid.UUID{codeReview}, targets)

	// Human rejects bypass: routes to Test
	targets, _, err = EvaluateEdges(snap, humanBypass, "rejected")
	require.NoError(t, err)
	assert.Equal(t, []uuid.UUID{test}, targets)

	// Invalid decision from Implement: error
	_, _, err = EvaluateEdges(snap, implement, "skip")
	require.Error(t, err)
	assert.Contains(t, err.Error(), "no matching edge")
}

func TestHasConditionalEdges_ImplementWithBypass(t *testing.T) {
	implement := makeNodeID()
	test := makeNodeID()
	humanBypass := makeNodeID()

	snap := &state.GraphRuntimeSnapshot{
		Edges: []state.SnapshotEdge{
			{SourceNodeID: implement, TargetNodeID: test, Condition: strPtr("test")},
			{SourceNodeID: implement, TargetNodeID: humanBypass, Condition: strPtr("request_test_bypass")},
		},
	}

	assert.True(t, HasConditionalEdges(snap, implement))
}
