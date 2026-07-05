package com.choruskube.core.dto;

import jakarta.validation.constraints.NotBlank;

public record GitRepoRequest(
        @NotBlank String url,
        String defaultBranch,
        String testCommand,
        String agentImage,
        String secrets,
        Boolean enableDocker) {}
