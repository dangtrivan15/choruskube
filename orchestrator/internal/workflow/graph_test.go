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

// TestParseSnapshot_TaskContext exercises the Java -> Go JSON contract for
// GraphRuntimeSnapshotResponse.TaskContext itself: a field-name typo on either
// side of the language boundary would silently zero-value SnapshotTaskContext
// instead of failing loudly, since there's no compile-time check across it.
func TestParseSnapshot_TaskContext(t *testing.T) {
	taskID := uuid.New()
	storyID := uuid.New()
	epicID := uuid.New()
	json := `{"nodes":[],"edges":[],"taskContext":{` +
		`"taskId":"` + taskID.String() + `",` +
		`"taskTitle":"Wire up task_context",` +
		`"storyId":"` + storyID.String() + `",` +
		`"storyTitle":"Agent identity threading",` +
		`"epicId":"` + epicID.String() + `",` +
		`"epicTitle":"Roadmap-aware agents"` +
		`}}`

	snap, err := ParseSnapshot(json)
	require.NoError(t, err)
	require.NotNil(t, snap.TaskContext)
	assert.Equal(t, taskID, snap.TaskContext.TaskID)
	assert.Equal(t, "Wire up task_context", snap.TaskContext.TaskTitle)
	require.NotNil(t, snap.TaskContext.StoryID)
	assert.Equal(t, storyID, *snap.TaskContext.StoryID)
	require.NotNil(t, snap.TaskContext.StoryTitle)
	assert.Equal(t, "Agent identity threading", *snap.TaskContext.StoryTitle)
	require.NotNil(t, snap.TaskContext.EpicID)
	assert.Equal(t, epicID, *snap.TaskContext.EpicID)
	require.NotNil(t, snap.TaskContext.EpicTitle)
	assert.Equal(t, "Roadmap-aware agents", *snap.TaskContext.EpicTitle)
}

// TestParseSnapshot_NoTaskContext confirms a manually-started run's snapshot
// (no taskContext key at all) leaves TaskContext nil rather than a zero-valued
// struct — the signal ExecuteAINodeFromSnapshot uses to omit config.json's
// task_context entirely.
func TestParseSnapshot_NoTaskContext(t *testing.T) {
	json := `{"nodes":[],"edges":[]}`

	snap, err := ParseSnapshot(json)
	require.NoError(t, err)
	assert.Nil(t, snap.TaskContext)
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

	ready := FindReadyNodes(snap, nodeStates, nil)
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

	ready := FindReadyNodes(snap, nodeStates, nil)
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

	ready := FindReadyNodes(snap, nodeStates, nil)
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

	ready := FindReadyNodes(snap, nodeStates, nil)
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

	ready := FindReadyNodes(snap, nodeStates, nil)
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

func TestEvaluateEdges_RealEdgeWinsOverTerminalDecisionForSameDecisionString(t *testing.T) {
	// A decision string that appears in both a real outgoing edge condition and the
	// node's terminal_decisions config must route via the edge — the terminal check
	// only ever runs when nothing matched a real edge (see EvaluateEdges: `hasConditional
	// && len(targets) == 0`). This locks that precedence in as intentional.
	humanGate := makeNodeID()
	approvedTarget := makeNodeID()

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
			{SourceNodeID: humanGate, TargetNodeID: approvedTarget, Condition: strPtr("approved")},
		},
	}

	targets, firedEdgeIDs, err := EvaluateEdges(snap, humanGate, "approved")
	require.NoError(t, err)
	assert.Equal(t, []uuid.UUID{approvedTarget}, targets)
	assert.NotEmpty(t, firedEdgeIDs)
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

// --- Supervisor routing hub: escalate / route:<label> ---

func hubSnapshot(t *testing.T) (*state.GraphRuntimeSnapshot, uuid.UUID, uuid.UUID, uuid.UUID) {
	t.Helper()
	ai := uuid.New()
	target := uuid.New()
	hub := uuid.New()
	snap := &state.GraphRuntimeSnapshot{
		Nodes: []state.SnapshotNode{
			{TemplateNodeID: ai, Label: "code_review", ExecutorType: "ai"},
			{TemplateNodeID: target, Label: "final_approval", ExecutorType: "human"},
			{TemplateNodeID: hub, Label: "supervisor", ExecutorType: "human",
				ConfigOverrides: map[string]interface{}{"routing_hub": true}},
		},
		Edges: []state.SnapshotEdge{
			{TemplateEdgeID: uuid.New(), SourceNodeID: ai, TargetNodeID: target, Condition: strPtr("approved")},
		},
	}
	return snap, ai, target, hub
}

func TestEvaluateEdges_EscalateRoutesToHubWithoutFiringAnEdge(t *testing.T) {
	snap, ai, _, hub := hubSnapshot(t)

	targets, fired, err := EvaluateEdges(snap, ai, "escalate")

	require.NoError(t, err)
	assert.Equal(t, []uuid.UUID{hub}, targets)
	assert.Empty(t, fired, "escalation fires no edge — there is none")
}

func TestEvaluateEdges_HubRoutesToNamedLabel(t *testing.T) {
	snap, _, target, hub := hubSnapshot(t)

	targets, fired, err := EvaluateEdges(snap, hub, "route:final_approval")

	require.NoError(t, err)
	assert.Equal(t, []uuid.UUID{target}, targets)
	assert.Empty(t, fired)
}

// The hub has zero outgoing edges, so the len(outgoing)==0 early return would treat it as a
// run terminus and silently end the run. The route branch must be evaluated first.
func TestEvaluateEdges_HubIsNotMistakenForATerminalNode(t *testing.T) {
	snap, _, _, hub := hubSnapshot(t)

	targets, _, err := EvaluateEdges(snap, hub, "route:code_review")

	require.NoError(t, err)
	require.Len(t, targets, 1)
}

func TestEvaluateEdges_HubRejectsUnknownLabel(t *testing.T) {
	snap, _, _, hub := hubSnapshot(t)

	_, _, err := EvaluateEdges(snap, hub, "route:does_not_exist")

	require.Error(t, err)
	assert.Contains(t, err.Error(), "does_not_exist")
}

func TestEvaluateEdges_EscalateWithoutHubIsAnError(t *testing.T) {
	snap, ai, _, _ := hubSnapshot(t)
	snap.Nodes = snap.Nodes[:2] // drop the hub

	_, _, err := EvaluateEdges(snap, ai, "escalate")

	require.Error(t, err)
}

func TestFindReadyNodes_ForceReadySkipsPredecessorGating(t *testing.T) {
	snap, ai, target, _ := hubSnapshot(t)
	states := map[uuid.UUID]string{
		ai:     "running", // would normally block `target`
		target: "pending",
	}

	assert.Empty(t, FindReadyNodes(snap, states, nil))
	ready := FindReadyNodes(snap, states, map[uuid.UUID]bool{target: true})
	require.Len(t, ready, 1)
	assert.Equal(t, target, ready[0].TemplateNodeID)
}
