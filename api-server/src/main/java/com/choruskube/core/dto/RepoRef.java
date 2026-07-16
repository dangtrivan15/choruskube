package com.choruskube.core.dto;

import java.util.UUID;

/**
 * Lightweight resolved git-repo reference embedded in an {@link EpicResponse}.
 * Returned by {@code SoftwareProject.resolveRepos()} and projected into this DTO at
 * response time. {@code name} is derived from {@code url} via
 * {@code RepoNameUtil.deriveRepoName}.
 */
public record RepoRef(UUID id, String url, String name) {}
