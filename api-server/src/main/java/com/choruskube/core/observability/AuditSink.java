package com.choruskube.core.observability;

import java.util.UUID;

public interface AuditSink {

    String REPO_CREATED = "repo_created";
    String REPO_UPDATED = "repo_updated";
    String REPO_DELETED = "repo_deleted";
    String RUN_CANCELLED = "run_cancelled";
    String RUN_PAUSED = "run_paused";
    String RUN_RESUMED = "run_resumed";
    String PROPOSAL_CREATED = "proposal_created";
    String PROPOSAL_UPDATED = "proposal_updated";
    String PROPOSAL_DELETED = "proposal_deleted";
    String NODE_DEF_CREATED = "node_def_created";
    String NODE_DEF_UPDATED = "node_def_updated";
    String NODE_DEF_DELETED = "node_def_deleted";

    /** Record an audit event. Org/actor/impersonation are supplied by the implementation. */
    void record(String action, String resourceType, UUID resourceId, String detail);
}
