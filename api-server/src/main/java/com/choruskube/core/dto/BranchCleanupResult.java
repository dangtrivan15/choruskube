package com.choruskube.core.dto;

import java.util.UUID;

/**
 * @param outcome one of {@code DELETED}, {@code KEPT_AHEAD}, {@code NOT_FOUND}, {@code
 *     SKIPPED_ERROR}
 */
public record BranchCleanupResult(UUID gitRepoId, String repoName, String branch, String outcome) {}
