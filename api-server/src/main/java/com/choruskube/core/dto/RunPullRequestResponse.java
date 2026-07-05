package com.choruskube.core.dto;

import java.time.Instant;
import java.util.UUID;

public record RunPullRequestResponse(
        UUID id,
        UUID workflowRunId,
        UUID gitRepoId,
        UUID nodeExecutionId,
        String prUrl,
        Integer prNumber,
        String title,
        String repoName,
        String repoUrl,
        Instant createdAt) {}
