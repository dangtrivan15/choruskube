package com.choruskube.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Shared resolver for "what decisions are legal for this node" — the union of a node's outgoing
 * edge conditions and its configured {@code terminal_decisions} (Decision 2). Every call site that
 * computes decision validity (gate presentation, decision submission, the agent-facing
 * valid-decisions endpoint) delegates here instead of re-deriving the rule inline, so the three
 * can't drift apart.
 *
 * <p>A node opts into a terminal decision by listing it (case-insensitively matched, same
 * convention as edge-condition matching) in its {@code config_overrides.terminal_decisions} JSON
 * array — see {@code orchestrator/internal/workflow/graph.go}'s {@code EvaluateEdges} for the
 * matching logic on the routing side.
 */
@Component
public class DecisionOptionsResolver {

    static final String TERMINAL_DECISIONS_KEY = "terminal_decisions";

    /**
     * @param edgesArr the snapshot's {@code edges} array (snake_case field names, as produced by
     *     {@code GraphSnapshotBuilder}), or {@code null}
     * @param sourceNodeId the template node whose outgoing edges/config to resolve
     * @param nodeConfigOverrides the source node's {@code config_overrides} JsonNode (the object
     *     under that key on the node — not the whole node), or {@code null} if absent
     * @return the ordered, de-duplicated union of edge-derived conditions and {@code
     *     terminal_decisions} entries
     */
    public List<String> resolve(JsonNode edgesArr, UUID sourceNodeId, JsonNode nodeConfigOverrides) {
        List<String> options = new ArrayList<>();
        if (edgesArr != null) {
            for (JsonNode edge : edgesArr) {
                if (edge.get("source_node_id").asText().equals(sourceNodeId.toString())
                        && edge.has("condition")
                        && !edge.get("condition").isNull()) {
                    options.add(edge.get("condition").asText());
                }
            }
        }

        if (nodeConfigOverrides != null && nodeConfigOverrides.has(TERMINAL_DECISIONS_KEY)) {
            JsonNode terminalDecisions = nodeConfigOverrides.get(TERMINAL_DECISIONS_KEY);
            if (terminalDecisions.isArray()) {
                for (JsonNode decision : terminalDecisions) {
                    String value = decision.asText();
                    boolean alreadyPresent = options.stream().anyMatch(o -> o.equalsIgnoreCase(value));
                    if (!alreadyPresent) {
                        options.add(value);
                    }
                }
            }
        }

        return options;
    }

    /**
     * Finds a node's {@code config_overrides} JsonNode within a snapshot's {@code nodes} array, by
     * {@code template_node_id}. Returns {@code null} if the node or its config overrides can't be
     * found — callers pass that straight into {@link #resolve} (which treats {@code null} as "no
     * terminal decisions configured").
     */
    public JsonNode findNodeConfigOverrides(JsonNode nodesArr, UUID nodeId) {
        if (nodesArr == null) {
            return null;
        }
        for (JsonNode node : nodesArr) {
            if (node.has("template_node_id")
                    && node.get("template_node_id").asText().equals(nodeId.toString())) {
                JsonNode overrides = node.get("config_overrides");
                return (overrides == null || overrides.isNull()) ? null : overrides;
            }
        }
        return null;
    }
}
