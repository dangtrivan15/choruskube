package com.choruskube.core.dto;

import com.choruskube.core.model.RuntimeRequirements;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RepoGroupResponse(
        UUID id,
        String name,
        String agentImage,
        String dindImage,
        String agentMemoryRequest,
        String dindMemoryRequest,
        boolean enableDocker,
        String description,
        RuntimeRequirements runtimeRequirements,
        List<MemberView> members,
        Instant createdAt,
        Instant updatedAt,
        boolean testAffectedOnly,
        List<DependencyView> dependencies) {

    public record MemberView(UUID gitRepoId, String name, int position) {}

    public record DependencyView(UUID repoId, List<UUID> dependsOnRepoIds) {}
}
