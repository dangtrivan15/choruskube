package com.choruskube.core.dto;

import java.time.Instant;
import java.util.UUID;

public record ReviewHistoryResponse(
        UUID id,
        String loopGroup,
        int iteration,
        String reviewerType,
        String decision,
        String result,
        String status,
        String artifactRefs,
        String nodeLabel,
        Instant timestamp) {}
