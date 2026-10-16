package com.choruskube.core.dto;

import java.util.UUID;

/**
 * The {@code CloseTaskIssueWorkflow} input. Field names are the Go JSON tags of the orchestrator's
 * {@code CloseTaskIssueParams}: a rename here compiles, and the Go side silently reads zero values.
 */
public record CloseTaskIssueWorkflowInput(
        UUID taskId, UUID runId, String ownerRepo, int issueNumber, String workerTaskQueue) {}
