package com.choruskube.core.service;

import com.choruskube.core.dto.ValidationResponse;
import com.choruskube.core.model.TemplateEdge;
import com.choruskube.core.model.TemplateNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class GraphValidationService {

    private final ObjectMapper objectMapper;

    public GraphValidationService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ValidationResponse validate(List<TemplateNode> nodes, List<TemplateEdge> edges) {
        List<String> errors = new ArrayList<>();

        if (nodes.isEmpty()) {
            errors.add("Graph has no nodes");
            return new ValidationResponse(false, errors);
        }

        Set<UUID> nodeIds = new HashSet<>();
        Map<UUID, String> nodeLabels = new HashMap<>();
        Map<UUID, List<UUID>> outgoing = new HashMap<>();

        for (TemplateNode node : nodes) {
            nodeIds.add(node.getId());
            nodeLabels.put(node.getId(), node.getLabel());
            outgoing.put(node.getId(), new ArrayList<>());
        }
        for (TemplateEdge edge : edges) {
            outgoing.get(edge.getSourceNodeId()).add(edge.getTargetNodeId());
        }

        // A routing_hub (the Supervisor) is reachable via the implicit `escalate` decision from
        // every AI node rather than by an edge, so it is exempt from the edge-based reachability
        // and terminal rules below.
        Set<UUID> routingHubs = new HashSet<>();
        for (TemplateNode node : nodes) {
            if (DecisionOptionsResolver.isRoutingHub(node.getConfigOverrides(), objectMapper)) {
                routingHubs.add(node.getId());
            }
        }
        if (routingHubs.size() > 1) {
            String names = routingHubs.stream()
                    .map(nodeLabels::get)
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");
            errors.add("Multiple routing_hub nodes defined (" + names + "); at most one is allowed");
        }
        for (TemplateEdge edge : edges) {
            if (routingHubs.contains(edge.getSourceNodeId()) || routingHubs.contains(edge.getTargetNodeId())) {
                UUID hubId =
                        routingHubs.contains(edge.getSourceNodeId()) ? edge.getSourceNodeId() : edge.getTargetNodeId();
                errors.add("Node '" + nodeLabels.get(hubId) + "' is a routing_hub and must have no edges");
            }
        }

        List<TemplateNode> entrypoints =
                nodes.stream().filter(TemplateNode::isEntrypoint).toList();
        if (entrypoints.isEmpty()) {
            errors.add("No entrypoint node defined (exactly one node must have entrypoint=true)");
        } else if (entrypoints.size() > 1) {
            String names = entrypoints.stream()
                    .map(TemplateNode::getLabel)
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");
            errors.add("Multiple entrypoint nodes defined (" + names + "); exactly one is allowed");
        }

        if (entrypoints.size() == 1) {
            Set<UUID> reachable = findReachableFrom(entrypoints.get(0).getId(), outgoing);
            for (UUID nodeId : nodeIds) {
                if (routingHubs.contains(nodeId)) continue;
                if (!reachable.contains(nodeId)) {
                    errors.add("Node '" + nodeLabels.get(nodeId) + "' is not reachable from entrypoint");
                }
            }
        }

        // Rule 3: Terminal node check. A node counts as terminal if it has no outgoing
        // edges, OR if it declares a non-empty config_overrides.terminal_decisions:
        // that decision ends the run right there with no edge to follow,
        // so the node doesn't need a zero-outgoing-edge shape to legitimately terminate
        // a run — e.g. the Roadmap Provisioner's human gate, whose only other edge is a
        // "rejected" back-edge to the analyzer.
        Set<UUID> terminalNodes = new HashSet<>();
        for (TemplateNode node : nodes) {
            UUID id = node.getId();
            if (routingHubs.contains(id)) continue;
            if (outgoing.get(id).isEmpty() || hasTerminalDecisions(node)) {
                terminalNodes.add(id);
            }
        }

        if (terminalNodes.isEmpty() && !nodeIds.isEmpty()) {
            errors.add("No terminal node found (all nodes have outgoing edges)");
        } else {
            for (UUID nodeId : nodeIds) {
                if (routingHubs.contains(nodeId)) continue;
                if (!canReachTerminal(nodeId, outgoing, terminalNodes)) {
                    errors.add("Node '" + nodeLabels.get(nodeId) + "' cannot reach any terminal node");
                }
            }
        }

        for (TemplateNode node : nodes) {
            validateConfigOverrides(node, errors);
        }

        // Rule 5: Edge conditions must not collide with the Supervisor's reserved decision
        // vocabulary. orchestrator/internal/workflow/graph.go's EvaluateEdges resolves an
        // `escalate` result and, for the hub's own completions, a `route:<label>` result before
        // it ever inspects the completed node's outgoing edges. An edge condition written as
        // either literal would therefore never fire — it is silently shadowed by the
        // routing-hub interpretation rather than erroring, which makes the authoring mistake
        // invisible until the run takes the wrong branch. Rejecting it here at authoring time
        // surfaces the mistake immediately instead.
        for (TemplateEdge edge : edges) {
            String condition = edge.getCondition();
            if (condition == null) {
                continue;
            }
            boolean isEscalate = DecisionOptionsResolver.ESCALATE_DECISION.equalsIgnoreCase(condition);
            boolean isRoutePrefixed = condition.regionMatches(
                    true, 0, DecisionOptionsResolver.ROUTE_PREFIX, 0, DecisionOptionsResolver.ROUTE_PREFIX.length());
            if (isEscalate || isRoutePrefixed) {
                errors.add("Edge condition '" + condition + "' from node '" + nodeLabels.get(edge.getSourceNodeId())
                        + "' is reserved for the Supervisor ('" + DecisionOptionsResolver.ESCALATE_DECISION + "' or '"
                        + DecisionOptionsResolver.ROUTE_PREFIX + "*') and can never fire as an edge condition");
            }
        }

        return new ValidationResponse(errors.isEmpty(), errors);
    }

    /**
     * Whether a node's config_overrides declares a non-empty {@code terminal_decisions} array.
     * Degrades to {@code false} on missing/malformed config_overrides — Rule 4
     * ({@link #validateConfigOverrides}) is the one responsible for surfacing malformed JSON as
     * a validation error, not this check.
     */
    private boolean hasTerminalDecisions(TemplateNode node) {
        String overridesStr = node.getConfigOverrides();
        if (overridesStr == null || overridesStr.isBlank()) {
            return false;
        }
        try {
            JsonNode overrides = objectMapper.readTree(overridesStr);
            JsonNode terminalDecisions = overrides.get("terminal_decisions");
            return terminalDecisions != null && terminalDecisions.isArray() && !terminalDecisions.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private void validateConfigOverrides(TemplateNode node, List<String> errors) {
        String overridesStr = node.getConfigOverrides();
        if (overridesStr == null || overridesStr.isBlank() || "{}".equals(overridesStr.trim())) {
            return;
        }
        try {
            JsonNode overrides = objectMapper.readTree(overridesStr);
            if (overrides.has("timeout_seconds")) {
                JsonNode timeoutNode = overrides.get("timeout_seconds");
                if (!timeoutNode.isNumber()) {
                    errors.add("Node '" + node.getLabel() + "': config_overrides.timeout_seconds must be a number");
                } else {
                    int timeout = timeoutNode.asInt();
                    if (timeout != 0 && (timeout < 60 || timeout > 86400)) {
                        errors.add("Node '" + node.getLabel()
                                + "': config_overrides.timeout_seconds must be 0 or between 60 and 86400");
                    }
                }
            }
        } catch (Exception e) {
            errors.add("Node '" + node.getLabel() + "': invalid config_overrides JSON");
        }
    }

    private Set<UUID> findReachableFrom(UUID start, Map<UUID, List<UUID>> outgoing) {
        Set<UUID> visited = new HashSet<>();
        Queue<UUID> queue = new LinkedList<>();
        queue.add(start);
        visited.add(start);

        while (!queue.isEmpty()) {
            UUID current = queue.poll();
            for (UUID next : outgoing.getOrDefault(current, List.of())) {
                if (visited.add(next)) {
                    queue.add(next);
                }
            }
        }
        return visited;
    }

    private boolean canReachTerminal(UUID start, Map<UUID, List<UUID>> outgoing, Set<UUID> terminalNodes) {
        if (terminalNodes.contains(start)) {
            return true;
        }
        Set<UUID> visited = new HashSet<>();
        Queue<UUID> queue = new LinkedList<>();
        queue.add(start);
        visited.add(start);

        while (!queue.isEmpty()) {
            UUID current = queue.poll();
            for (UUID next : outgoing.getOrDefault(current, List.of())) {
                if (terminalNodes.contains(next)) {
                    return true;
                }
                if (visited.add(next)) {
                    queue.add(next);
                }
            }
        }
        return false;
    }
}
