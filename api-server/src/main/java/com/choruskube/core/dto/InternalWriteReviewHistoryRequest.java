package com.choruskube.core.dto;

import java.util.UUID;

public record InternalWriteReviewHistoryRequest(
        String loopGroup, int iteration, String reviewerType, String artifactRefs, UUID nodeExecutionId) {}
