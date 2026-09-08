package com.choruskube.core.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

public record GitRepoResponse(
        UUID id,
        String url,
        String defaultBranch,
        String testCommand,
        String agentImage,
        String dindImage,
        JsonNode secrets,
        boolean enableDocker,
        Instant createdAt,
        Instant updatedAt) {}
