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

        // Rule 1: Exactly one entrypoint
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

        // Rule 2: All nodes reachable from entrypoint (backward reachability)
        if (entrypoints.size() == 1) {
            Set<UUID> reachable = findReachableFrom(entrypoints.get(0).getId(), outgoing);
            for (UUID nodeId : nodeIds) {
                if (!reachable.contains(nodeId)) {
                    errors.add("Node '" + nodeLabels.get(nodeId) + "' is not reachable from entrypoint");
                }
            }
        }

        // Rule 3: Terminal node check
        Set<UUID> terminalNodes = new HashSet<>();
        for (UUID id : nodeIds) {
            if (outgoing.get(id).isEmpty()) {
                terminalNodes.add(id);
            }
        }

        if (terminalNodes.isEmpty() && !nodeIds.isEmpty()) {
            errors.add("No terminal node found (all nodes have outgoing edges)");
        } else {
            for (UUID nodeId : nodeIds) {
                if (!canReachTerminal(nodeId, outgoing, terminalNodes)) {
                    errors.add("Node '" + nodeLabels.get(nodeId) + "' cannot reach any terminal node");
                }
            }
        }

        // Rule 4: Validate config_overrides for each node
        for (TemplateNode node : nodes) {
            validateConfigOverrides(node, errors);
        }

        return new ValidationResponse(errors.isEmpty(), errors);
    }

    /**
     * Validates config_overrides JSON on a template node.
     * Currently checks: timeout_seconds must be 0 or between 60 and 86400.
     */
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
