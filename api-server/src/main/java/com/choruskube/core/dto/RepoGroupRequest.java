package com.choruskube.core.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.UUID;

public record RepoGroupRequest(
        @NotBlank String name,
        String agentImage,
        String description,
        @NotEmpty List<UUID> memberRepoIds) {}
