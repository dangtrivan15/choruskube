package com.choruskube.core.dto;

import com.choruskube.core.model.RuntimeRequirements;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RepoGroupResponse(
        UUID id,
        String name,
        String agentImage,
        String description,
        RuntimeRequirements runtimeRequirements,
        List<MemberView> members,
        Instant createdAt,
        Instant updatedAt) {

    public record MemberView(UUID gitRepoId, String name, int position) {}
}
