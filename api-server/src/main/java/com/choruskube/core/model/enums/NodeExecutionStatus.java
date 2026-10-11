package com.choruskube.core.model.enums;

public enum NodeExecutionStatus {
    pending,
    running,
    paused,
    awaiting_human,
    finalizing,
    completed,
    failed,
    skipped,
    invalidated
}
