package com.choruskube.core.service;

/**
 * Where a run executes: the Temporal namespace its workflow lives in, and the task queue its
 * agent-step activities are dispatched to.
 */
public record RunPlacement(String namespace, String taskQueue) {

    public RunPlacement {
        // A blank value reaches Temporal as the default namespace or the workflow's own queue,
        // and both present as a run that hangs to its activity timeout with nothing logged on
        // any side. Callers with no policy of their own do not construct this at all -- they
        // leave the resolver bean absent.
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("RunPlacement namespace must not be blank");
        }
        if (taskQueue == null || taskQueue.isBlank()) {
            throw new IllegalArgumentException("RunPlacement taskQueue must not be blank");
        }
    }
}
