package com.choruskube.core.model.enums;

public enum WorkflowRunStatus {
    pending,
    running,
    paused,
    completed,
    failed,
    cancelled,
    awaiting_human,
    awaiting_retry,
    live_chat
}
