package com.choruskube.core.util;

import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.NodeExecution;
import java.util.UUID;

/**
 * Guards for run-scoped {@code node_execution} access.
 *
 * <p>Endpoints addressed as {@code /runs/{runId}/nodes/{nodeExecId}/...} authorize the org on
 * {@code runId} only. Without this check, a caller may pair a {@code runId} they are entitled to
 * with any {@code nodeExecId} at all and act on an execution belonging to a different run — node
 * executions carry no authorization of their own, inheriting it entirely from their parent run.
 *
 * <p>A mismatch is reported as {@link NotFoundException} rather than a forbidden error so the
 * response does not confirm that the supplied execution id exists.
 */
public final class NodeExecutionUtil {

    private NodeExecutionUtil() {}

    public static void requireInRun(NodeExecution exec, UUID runId) {
        if (!runId.equals(exec.getWorkflowRunId())) {
            throw new NotFoundException("Node execution " + exec.getId() + " does not belong to run " + runId);
        }
    }
}
