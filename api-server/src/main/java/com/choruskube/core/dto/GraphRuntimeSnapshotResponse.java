package com.choruskube.core.dto;

import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Projected graph snapshot containing only workflow-execution fields.
 * Infrastructure fields (image, secrets, namespace, docker config) are
 * excluded — the API server resolves those internally during workload creation.
 */
public record GraphRuntimeSnapshotResponse(
        List<RuntimeNode> nodes,
        List<RuntimeEdge> edges,
        Map<String, Object> inputs,
        List<RuntimeRepo> repos,
        @Nullable TaskContext taskContext) {

    /** Backwards-compatible constructor for callers that don't pass repos or taskContext. */
    public GraphRuntimeSnapshotResponse(List<RuntimeNode> nodes, List<RuntimeEdge> edges, Map<String, Object> inputs) {
        this(nodes, edges, inputs, List.of(), null);
    }

    /** Backwards-compatible constructor for callers that don't pass taskContext. */
    public GraphRuntimeSnapshotResponse(
            List<RuntimeNode> nodes, List<RuntimeEdge> edges, Map<String, Object> inputs, List<RuntimeRepo> repos) {
        this(nodes, edges, inputs, repos, null);
    }

    public record RuntimeRepo(String id, String url, String name, String testCommand, String agentImage) {}

    /**
     * The triggering Task's identity, resolved live off {@code workflow_run.task_id} and the
     * {@code task.story_id -> story.epic_id} FK chain (Decision 1). Absent when the run wasn't
     * started from a Task. Story/Epic fields are independently nullable since either level may
     * no longer resolve (renamed/deleted mid-run) without failing the whole snapshot.
     */
    public record TaskContext(
            UUID taskId,
            String taskTitle,
            @Nullable UUID storyId,
            @Nullable String storyTitle,
            @Nullable UUID epicId,
            @Nullable String epicTitle) {}

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
