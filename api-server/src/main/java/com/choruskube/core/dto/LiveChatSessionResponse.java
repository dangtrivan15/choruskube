package com.choruskube.core.dto;

import java.time.Instant;
import java.util.UUID;

public record LiveChatSessionResponse(
        UUID id,
        UUID nodeExecutionId,
        UUID workflowRunId,
        UUID sourceNodeExecutionId,
        String status,
        String transcript,
        String chatPodName,
        Instant startedAt,
        Instant completedAt,
        Instant createdAt) {}
