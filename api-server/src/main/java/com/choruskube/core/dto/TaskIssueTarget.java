package com.choruskube.core.dto;

import java.util.UUID;

/**
 * A newly-created Task a {@code roadmap_extension} gate's write step wants the run's Worker to
 * file a GitHub issue for, resolved to its repository and carrying the issue's title and body.
 */
public record TaskIssueTarget(UUID taskId, UUID gitRepoId, String ownerRepo, String title, String body) {}
