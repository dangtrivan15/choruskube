package com.choruskube.core.dto;

import java.util.UUID;

/**
 * {@code name} is derived from {@code url} via {@code RepoNameUtil.deriveRepoName}.
 */
public record RepoRef(UUID id, String url, String name) {}
