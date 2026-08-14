package com.choruskube.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Shared resolver for "what decisions are legal for this node" — the union of a node's outgoing
 * edge conditions, its configured {@code terminal_decisions} (Decision 2), and the implicit
 * Supervisor vocabulary (Decision 1: dynamic routing, not edge topology): every AI node gains
 * {@link #ESCALATE_DECISION} when the template declares a Supervisor (a human node with {@code
 * config_overrides.routing_hub: true} and no edges of its own), and the Supervisor itself gains a
 * {@code route:<label>} decision for every other node in the template. Every call site that
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
    static final String ROUTING_HUB_KEY = "routing_hub";

    /** Implicit decision offered to every AI node in a template that declares a Supervisor. */
    public static final String ESCALATE_DECISION = "escalate";

    /** Prefix of the Supervisor's implicit routing decisions: {@code route:<target label>}. */
    public static final String ROUTE_PREFIX = "route:";

    /**
     * The ordered, de-duplicated set of decisions this node may submit: its outgoing edge
     * conditions, then its {@code terminal_decisions}, then the implicit Supervisor decisions.
     *
     * @param snapshot a full graph runtime snapshot as produced by {@code GraphSnapshotBuilder}
     *     (snake_case field names), or {@code null}
     * @param sourceNodeId the template node whose decisions to resolve
     */
    public List<String> resolve(JsonNode snapshot, UUID sourceNodeId) {
        JsonNode nodesArr = snapshot == null ? null : snapshot.get("nodes");
        JsonNode edgesArr = snapshot == null ? null : snapshot.get("edges");

        List<String> options = new ArrayList<>();
        if (edgesArr != null) {
            for (JsonNode edge : edgesArr) {
                if (edge.get("source_node_id").asText().equals(sourceNodeId.toString())
                        && edge.has("condition")
                        && !edge.get("condition").isNull()) {
                    addIfAbsent(options, edge.get("condition").asText());
                }
            }
        }

        JsonNode sourceNode = findNode(nodesArr, sourceNodeId);
        JsonNode overrides = configOverridesOf(sourceNode);
        if (overrides != null && overrides.has(TERMINAL_DECISIONS_KEY)) {
            JsonNode terminalDecisions = overrides.get(TERMINAL_DECISIONS_KEY);
            if (terminalDecisions.isArray()) {
                for (JsonNode decision : terminalDecisions) {
                    addIfAbsent(options, decision.asText());
                }
            }
        }

        JsonNode hub = findRoutingHub(snapshot);
        if (hub == null || sourceNode == null) {
            return options;
        }
        if (isRoutingHub(sourceNode)) {
            // The Supervisor routes to any node but itself. Order follows the nodes array so the
            // picker reads in template order.
            for (JsonNode node : nodesArr) {
                if (isRoutingHub(node)) {
                    continue;
                }
                addIfAbsent(options, ROUTE_PREFIX + node.path("label").asText());
            }
        } else if ("ai".equalsIgnoreCase(sourceNode.path("executor_type").asText())) {
            // Script nodes have no agent session and cannot author escalation.md; human nodes
            // already have a human present.
            addIfAbsent(options, ESCALATE_DECISION);
        }
        return options;
    }

    /** The template's Supervisor node, or {@code null} if it declares none. */
    public JsonNode findRoutingHub(JsonNode snapshot) {
        JsonNode nodesArr = snapshot == null ? null : snapshot.get("nodes");
        if (nodesArr == null) {
            return null;
        }
        for (JsonNode node : nodesArr) {
            if (isRoutingHub(node)) {
                return node;
            }
        }
        return null;
    }

    /**
     * Finds a node's {@code config_overrides} JsonNode within a snapshot's {@code nodes} array, by
     * {@code template_node_id}. Returns {@code null} if the node or its config overrides can't be
     * found. Kept public because callers outside decision resolution read other config keys
     * through it — {@code RunService.isMaterializeNode} reads {@code materialize}.
     */
    public JsonNode findNodeConfigOverrides(JsonNode nodesArr, UUID nodeId) {
        return configOverridesOf(findNode(nodesArr, nodeId));
    }

    /** {@code "route:implement"} → {@code "implement"}; {@code null} for any other decision. */
    public static String routeTargetLabel(String decision) {
        if (decision == null || decision.length() <= ROUTE_PREFIX.length()) {
            return null;
        }
        if (!decision.regionMatches(true, 0, ROUTE_PREFIX, 0, ROUTE_PREFIX.length())) {
            return null;
        }
        return decision.substring(ROUTE_PREFIX.length());
    }

    /**
     * Whether a {@code config_overrides} JSON string declares {@code routing_hub: true} — the
     * Supervisor. Shared with the entity-side callers (graph validation, artifact resolution),
     * which hold a TemplateNode's raw JSON rather than a snapshot node, so the rule is written
     * once. Degrades to {@code false} on missing or malformed JSON.
     *
     * <p>Requires the strict JSON boolean shape ({@code isBoolean() && asBoolean()}), not
     * Jackson's {@code asBoolean(false)} coercion — that coercion also accepts the string
     * {@code "true"} and non-zero numbers, which {@code orchestrator/internal/workflow/graph.go}
     * (a real {@code .(bool)} type assertion) and {@code RunDag.tsx} (a strict {@code === true})
     * both reject. A truthy-but-non-boolean value must not be a hub to one component and not to
     * the other two.
     */
    public static boolean isRoutingHub(String configOverridesJson, ObjectMapper mapper) {
        if (configOverridesJson == null || configOverridesJson.isBlank()) {
            return false;
        }
        try {
            return isStrictlyTrue(mapper.readTree(configOverridesJson).path(ROUTING_HUB_KEY));
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isRoutingHub(JsonNode node) {
        JsonNode overrides = configOverridesOf(node);
        return overrides != null && isStrictlyTrue(overrides.path(ROUTING_HUB_KEY));
    }

    private static boolean isStrictlyTrue(JsonNode value) {
        return value.isBoolean() && value.asBoolean();
    }

    private JsonNode findNode(JsonNode nodesArr, UUID nodeId) {
        if (nodesArr == null) {
            return null;
        }
        for (JsonNode node : nodesArr) {
            if (node.has("template_node_id")
                    && node.get("template_node_id").asText().equals(nodeId.toString())) {
                return node;
            }
        }
        return null;
    }

    private JsonNode configOverridesOf(JsonNode node) {
        if (node == null) {
            return null;
        }
        JsonNode overrides = node.get("config_overrides");
        return (overrides == null || overrides.isNull()) ? null : overrides;
    }

    private void addIfAbsent(List<String> options, String value) {
        if (options.stream().noneMatch(o -> o.equalsIgnoreCase(value))) {
            options.add(value);
        }
    }
}
