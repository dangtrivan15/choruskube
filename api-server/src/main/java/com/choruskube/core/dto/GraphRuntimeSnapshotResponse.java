package com.choruskube.core.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Projected graph snapshot containing only workflow-execution fields.
 * Infrastructure fields (image, secrets, namespace, docker config) are
 * excluded — the API server resolves those internally during workload creation.
 */
public record GraphRuntimeSnapshotResponse(
        List<RuntimeNode> nodes, List<RuntimeEdge> edges, Map<String, Object> inputs, List<RuntimeRepo> repos) {

    /** Backwards-compatible constructor for callers that don't pass repos. */
    public GraphRuntimeSnapshotResponse(List<RuntimeNode> nodes, List<RuntimeEdge> edges, Map<String, Object> inputs) {
        this(nodes, edges, inputs, List.of());
    }

    public record RuntimeRepo(String id, String url, String name, String testCommand, String agentImage) {}

    public record RuntimeNode(
            UUID templateNodeId,
            String label,
            String executorType,
            String promptTemplate,
            String model,
            int timeoutSeconds,
            Map<String, Object> configOverrides,
            boolean isEntrypoint,
            String outputSpec) {}

    public record RuntimeEdge(UUID templateEdgeId, UUID sourceNodeId, UUID targetNodeId, String condition) {}
}
