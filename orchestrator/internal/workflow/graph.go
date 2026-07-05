package workflow

import (
	"encoding/json"
	"fmt"
	"strings"

	"github.com/google/uuid"

	"github.com/dangtrivan15/choruskube/orchestrator/internal/state"
)

// ParseSnapshot deserializes a JSON graph runtime snapshot
func ParseSnapshot(snapshotJSON string) (*state.GraphRuntimeSnapshot, error) {
	var snap state.GraphRuntimeSnapshot
	if err := json.Unmarshal([]byte(snapshotJSON), &snap); err != nil {
		return nil, fmt.Errorf("parse graph runtime snapshot: %w", err)
	}
	return &snap, nil
}

// FindEntryNodes returns nodes explicitly marked as entrypoints
func FindEntryNodes(snap *state.GraphRuntimeSnapshot) []state.SnapshotNode {
	var entries []state.SnapshotNode
	for _, node := range snap.Nodes {
		if node.IsEntrypoint {
			entries = append(entries, node)
		}
	}
	return entries
}

// GetPredecessorNodeIDs returns source node IDs of all edges pointing to the given node
func GetPredecessorNodeIDs(snap *state.GraphRuntimeSnapshot, nodeID uuid.UUID) []uuid.UUID {
	var preds []uuid.UUID
	seen := make(map[uuid.UUID]bool)
	for _, edge := range snap.Edges {
		if edge.TargetNodeID == nodeID && !seen[edge.SourceNodeID] {
			preds = append(preds, edge.SourceNodeID)
			seen[edge.SourceNodeID] = true
		}
	}
	return preds
}

// FindReadyNodes returns nodes that are pending and have all predecessors completed.
// nodeStates maps template_node_id -> status for all activated nodes in this run.
func FindReadyNodes(snap *state.GraphRuntimeSnapshot, nodeStates map[uuid.UUID]string) []state.SnapshotNode {
	var ready []state.SnapshotNode
	for _, node := range snap.Nodes {
		status, exists := nodeStates[node.TemplateNodeID]
		if !exists || status != "pending" {
			continue
		}

		// Entrypoint nodes are always ready when pending (no predecessor check)
		if node.IsEntrypoint {
			ready = append(ready, node)
			continue
		}

		preds := GetPredecessorNodeIDs(snap, node.TemplateNodeID)
		allCompleted := true
		for _, predID := range preds {
			// Self-edges are re-entry back-edges (e.g. spec_review --revised--> spec_review),
			// not gating dependencies. A node's own pending status must not block its own readiness.
			if predID == node.TemplateNodeID {
				continue
			}
			predStatus, predExists := nodeStates[predID]
			if predExists && predStatus != "completed" {
				allCompleted = false
				break
			}
		}

		if allCompleted {
			ready = append(ready, node)
		}
	}
	return ready
}

// EvaluateEdges evaluates outgoing edges for a completed node.
// Returns target node IDs to activate AND the template_edge IDs that fired —
// the latter is the source of truth the web UI uses to highlight traversed
// edges, so the rule lives here instead of being re-derived client-side.
// Rules:
//   - Unconditional edges (condition == nil) always fire
//   - Conditional edges fire if condition matches result (case-insensitive)
//   - If node has ONLY conditional edges and none match → error
//   - If node has no outgoing edges (terminal) → empty lists, no error
func EvaluateEdges(
	snap *state.GraphRuntimeSnapshot,
	completedNodeID uuid.UUID,
	result string,
) (targets []uuid.UUID, firedEdgeIDs []uuid.UUID, err error) {
	var outgoing []state.SnapshotEdge
	for _, edge := range snap.Edges {
		if edge.SourceNodeID == completedNodeID {
			outgoing = append(outgoing, edge)
		}
	}

	if len(outgoing) == 0 {
		return nil, nil, nil
	}

	hasConditional := false
	resultLower := strings.ToLower(result)

	for _, edge := range outgoing {
		if edge.Condition == nil {
			targets = append(targets, edge.TargetNodeID)
			firedEdgeIDs = append(firedEdgeIDs, edge.TemplateEdgeID)
		} else {
			hasConditional = true
			if strings.ToLower(*edge.Condition) == resultLower {
				targets = append(targets, edge.TargetNodeID)
				firedEdgeIDs = append(firedEdgeIDs, edge.TemplateEdgeID)
			}
		}
	}

	if hasConditional && len(targets) == 0 {
		return nil, nil, fmt.Errorf("no matching edge for result: %s", result)
	}

	return targets, firedEdgeIDs, nil
}

// GetNodeByID looks up a node in the snapshot by template_node_id
func GetNodeByID(snap *state.GraphRuntimeSnapshot, nodeID uuid.UUID) (state.SnapshotNode, bool) {
	for _, node := range snap.Nodes {
		if node.TemplateNodeID == nodeID {
			return node, true
		}
	}
	return state.SnapshotNode{}, false
}

// HasConditionalEdges returns true if the node has any outgoing edge with a non-nil condition.
func HasConditionalEdges(snap *state.GraphRuntimeSnapshot, nodeID uuid.UUID) bool {
	for _, edge := range snap.Edges {
		if edge.SourceNodeID == nodeID && edge.Condition != nil {
			return true
		}
	}
	return false
}
