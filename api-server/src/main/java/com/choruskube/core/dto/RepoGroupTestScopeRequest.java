package com.choruskube.core.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

public record RepoGroupTestScopeRequest(
        boolean affectedOnly, @NotNull List<@Valid @NotNull RepoDependency> dependencies) {

    public record RepoDependency(
            @NotNull UUID repoId, @NotNull List<@NotNull UUID> dependsOnRepoIds) {}
}
