package com.choruskube.core.model.enums;

public enum NodeExecutionStatus {
    pending,
    running,
    paused,
    awaiting_human,
    completed,
    failed,
    skipped,
    invalidated,
    live_chat
}
