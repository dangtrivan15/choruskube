package com.choruskube.core.observability;

import java.util.UUID;

public interface UsageSink {

    String RUN_STARTED = "run_started";
    String RUN_COMPLETED = "run_completed";
    String RUN_FAILED = "run_failed";
    String EXECUTION_STARTED = "execution_started";
    String REPO_CREATED = "repo_created";
    String REPO_DELETED = "repo_deleted";

    /** Record a usage event. The org is resolved from {@code (resourceType, resourceId)}. */
    void record(String eventType, String resourceType, UUID resourceId, String metadata);
}
