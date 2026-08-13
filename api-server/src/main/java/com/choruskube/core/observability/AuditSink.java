package com.choruskube.core.observability;

import java.util.UUID;

public interface AuditSink {

    String REPO_CREATED = "repo_created";
    String REPO_UPDATED = "repo_updated";
    String REPO_DELETED = "repo_deleted";
    String RUN_CANCELLED = "run_cancelled";
    String RUN_PAUSED = "run_paused";
    String RUN_RESUMED = "run_resumed";
    String EPIC_CREATED = "epic_created";
    String EPIC_UPDATED = "epic_updated";
    String EPIC_DELETED = "epic_deleted";
    String EPIC_STAGE_UPDATED = "epic_stage_updated";
    String EPIC_PRIORITY_UPDATED = "epic_priority_updated";
    String EPIC_TARGET_DATE_UPDATED = "epic_target_date_updated";
    String STORY_CREATED = "story_created";
    String STORY_UPDATED = "story_updated";
    String STORY_DELETED = "story_deleted";
    String STORY_STAGE_UPDATED = "story_stage_updated";
    String STORY_PRIORITY_UPDATED = "story_priority_updated";
    String STORY_TARGET_DATE_UPDATED = "story_target_date_updated";
    String TASK_CREATED = "task_created";
    String TASK_UPDATED = "task_updated";
    String TASK_DELETED = "task_deleted";
    String TASK_STATUS_CHANGED = "task_status_changed";
    String NODE_DEF_CREATED = "node_def_created";
    String NODE_DEF_UPDATED = "node_def_updated";
    String NODE_DEF_DELETED = "node_def_deleted";

    /** Record an audit event. Org/actor/impersonation are supplied by the implementation. */
    void record(String action, String resourceType, UUID resourceId, String detail);
}
