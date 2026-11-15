package com.choruskube.core.model;

import java.io.Serializable;
import java.util.UUID;

public record RepoGroupDependencyId(UUID repoGroupId, UUID gitRepoId, UUID dependsOnGitRepoId)
        implements Serializable {}
